/*
 * Copyright 2026 Vunlior
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.guardx;

import dev.guardx.api.GuardHistoryEntry;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Единственное место, где живёт история нарушений игроков.
 * <ul>
 *   <li>последние записи online-игроков держим в памяти (быстрый доступ для /guardx dump и оповещений);</li>
 *   <li>если history.persist включён — каждая запись дописывается в plugins/GuardX/history/&lt;uuid&gt;.tsv,
 *       поэтому историю можно смотреть после перезапуска и у игроков, которых сейчас нет на сервере.</li>
 * </ul>
 * Всё чтение и запись файлов идёт в одном фоновом потоке, поэтому порядок записей сохраняется.
 */
public final class HistoryStore {

    private static final int CACHE_SIZE = 30;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final GuardX plugin;
    private final Path dir;
    private final Path namesFile;
    private final Map<UUID, Deque<GuardHistoryEntry>> cache = new ConcurrentHashMap<>();
    /** ник (в нижнем регистре) -> UUID: нужен, чтобы смотреть историю тех, кого нет на сервере. */
    private final Map<String, UUID> names = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GuardX-History");
        t.setDaemon(true);
        return t;
    });

    public HistoryStore(GuardX plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("history");
        this.namesFile = dir.resolve("names.tsv");
        if (persist()) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ex) {
                warn(ex);
            }
            loadNames();
            submit(this::cleanup);
        }
    }

    public boolean persist() {
        return plugin.getConfig().getBoolean("history.persist", true);
    }

    /** Сколько записей максимум хранится на одного игрока. */
    public int maxEntries() {
        return Math.max(10, plugin.getConfig().getInt("history.max-entries", 500));
    }

    // ------------------------------------------------------------ запись

    public void add(Player p, String check, String info, double total) {
        UUID id = p.getUniqueId();
        GuardHistoryEntry e = new GuardHistoryEntry(System.currentTimeMillis(), check, total, info);
        Deque<GuardHistoryEntry> q = cache.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(e);
            while (q.size() > CACHE_SIZE) q.pollFirst();
        }
        if (!persist()) return;

        String key = p.getName().toLowerCase(Locale.ROOT);
        if (!id.equals(names.put(key, id))) {
            String row = key + "\t" + id + "\n";
            submit(() -> append(namesFile, row));
        }
        String line = e.time() + "\t" + check + "\t" + String.format(Locale.US, "%.1f", total) + "\t" + clean(info) + "\n";
        submit(() -> append(dir.resolve(id + ".tsv"), line));
    }

    /** Игрок вышел: освобождаем память и при необходимости подрезаем файл. */
    public void unload(UUID id) {
        cache.remove(id);
        if (persist()) submit(() -> trim(id));
    }

    // ------------------------------------------------------------ чтение

    /** Последние записи из памяти (только для игроков, которые сейчас на сервере). Старые — первыми. */
    public List<GuardHistoryEntry> recent(UUID id) {
        Deque<GuardHistoryEntry> q = cache.get(id);
        if (q == null) return List.of();
        synchronized (q) {
            return new ArrayList<>(q);
        }
    }

    /**
     * Записи игрока из файла (до {@code limit} последних, старые первыми).
     * Блокирует вызывающий поток на время чтения файла (обычно миллисекунды), поэтому в игровом тике зови редко.
     */
    public List<GuardHistoryEntry> entries(UUID id, int limit) {
        if (!persist()) return tail(recent(id), limit);
        try {
            Future<List<GuardHistoryEntry>> f = io.submit(() -> readTail(id, limit));
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return tail(recent(id), limit);
        }
    }

    /** UUID игрока по нику из индекса истории (null — такого игрока в истории нет). */
    public UUID find(String name) {
        return name == null ? null : names.get(name.toLowerCase(Locale.ROOT));
    }

    /** Ник по UUID из индекса (null — неизвестен). */
    public String nameOf(UUID id) {
        for (Map.Entry<String, UUID> en : names.entrySet()) {
            if (en.getValue().equals(id)) return en.getKey();
        }
        return null;
    }

    /** Строка истории для консоли и файла-отчёта. */
    public static String line(GuardHistoryEntry e) {
        return "[" + TIME.format(Instant.ofEpochMilli(e.time())) + "] " + e.check() + " " + e.info()
                + " VL=" + String.format(Locale.US, "%.1f", e.vl());
    }

    public static String time(GuardHistoryEntry e) {
        return TIME.format(Instant.ofEpochMilli(e.time()));
    }

    public void shutdown() {
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ работа с файлами (поток GuardX-History)

    private void submit(Runnable r) {
        try {
            io.execute(r);
        } catch (RejectedExecutionException ignored) {
            // плагин выключается
        }
    }

    private static List<GuardHistoryEntry> tail(List<GuardHistoryEntry> all, int limit) {
        return all.size() <= limit ? all : new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private void append(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            warn(ex);
        }
    }

    private List<GuardHistoryEntry> readTail(UUID id, int limit) throws IOException {
        Path file = dir.resolve(id + ".tsv");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<GuardHistoryEntry> out = new ArrayList<>();
        for (int i = Math.max(0, lines.size() - limit); i < lines.size(); i++) {
            GuardHistoryEntry e = parse(lines.get(i));
            if (e != null) out.add(e);
        }
        return out;
    }

    private static GuardHistoryEntry parse(String line) {
        String[] p = line.split("\t", 4);
        if (p.length < 4) return null;
        try {
            return new GuardHistoryEntry(Long.parseLong(p[0]), p[1], Double.parseDouble(p[2]), p[3]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Оставляет в файле только последние history.max-entries записей (когда он заметно вырос). */
    private void trim(UUID id) {
        Path file = dir.resolve(id + ".tsv");
        try {
            int max = maxEntries();
            if (!Files.isRegularFile(file) || Files.size(file) < max * 160L) return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() <= max) return;
            Path tmp = dir.resolve(id + ".tmp");
            Files.write(tmp, lines.subList(lines.size() - max, lines.size()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            warn(ex);
        }
    }

    private void loadNames() {
        try {
            if (!Files.isRegularFile(namesFile)) return;
            for (String line : Files.readAllLines(namesFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length != 2) continue;
                try {
                    names.put(p[0], UUID.fromString(p[1]));
                } catch (IllegalArgumentException ignored) {
                    // битая строка — пропускаем
                }
            }
        } catch (IOException ex) {
            warn(ex);
        }
    }

    /** Удаляет файлы игроков, у которых нет новых нарушений дольше history.retention-days (0 = не удалять). */
    private void cleanup() {
        int days = plugin.getConfig().getInt("history.retention-days", 30);
        if (days <= 0) return;
        long limit = System.currentTimeMillis() - days * 86_400_000L;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".tsv") && !f.equals(namesFile)).forEach(f -> {
                try {
                    if (Files.getLastModifiedTime(f).toMillis() < limit) Files.deleteIfExists(f);
                } catch (IOException ex) {
                    warn(ex);
                }
            });
        } catch (IOException ex) {
            warn(ex);
        }
    }

    private void warn(IOException ex) {
        plugin.getLogger().warning(plugin.lang().plain("warn-history-io", "error", String.valueOf(ex.getMessage())));
    }
}
