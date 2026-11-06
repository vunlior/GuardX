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

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Жалобы игроков на читеров (/guardx report, /report) и автоматические жалобы самого античита.
 * Жалоба хранит снимок VL подозреваемого на момент подачи — модератору не нужно гадать, что было.
 * Подозреваемый на время (suspect-minutes) попадает под ужесточение: его VL растут быстрее.
 */
public final class ReportManager {

    public static final UUID SYSTEM = new UUID(0L, 0L);

    public static final class Report {
        public final int id;
        public final long time;
        public final UUID reporter;
        public final String reporterName;
        public final UUID target;
        public final String targetName;
        public final String reason;
        public final String snapshot;   // "speed=12.0, fly=3.0"
        public final double totalVl;
        public final String world;
        public final int x, y, z;
        public volatile boolean closed;

        Report(int id, long time, UUID reporter, String reporterName, UUID target, String targetName,
               String reason, String snapshot, double totalVl, String world, int x, int y, int z, boolean closed) {
            this.id = id; this.time = time; this.reporter = reporter; this.reporterName = reporterName;
            this.target = target; this.targetName = targetName; this.reason = reason; this.snapshot = snapshot;
            this.totalVl = totalVl; this.world = world; this.x = x; this.y = y; this.z = z; this.closed = closed;
        }

        public boolean auto() { return SYSTEM.equals(reporter); }
    }

    private final GuardX plugin;
    private final List<Report> reports = new CopyOnWriteArrayList<>();
    private volatile long lastPrune;
    private final Map<UUID, Long> reporterCooldown = new ConcurrentHashMap<>();
    /** "reporter:target" -> время последней жалобы (защита от спама одной и той же жалобой). */
    private final Map<String, Long> pairCooldown = new ConcurrentHashMap<>();
    /** Подозреваемые: uuid -> до какого момента действует ужесточение. */
    private final Map<UUID, Long> suspects = new ConcurrentHashMap<>();
    /** Множитель VL для каждого подозреваемого (жалобы и сетевые баны могут задавать свой). */
    private final Map<UUID, Double> suspectMults = new ConcurrentHashMap<>();
    /** Когда последний раз создавалась авто-жалоба на игрока. */
    private final Map<UUID, Long> autoCooldown = new ConcurrentHashMap<>();
    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GuardX-ReportWriter");
        t.setDaemon(true);
        return t;
    });
    private int nextId = 1;

    public ReportManager(GuardX plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("reports.yml");
        load();
    }

    // ------------------------------------------------------------ подача

    public enum Result { OK, SELF, COOLDOWN, DUPLICATE, NO_REASON, EXEMPT }

    /** Жалоба от игрока. Вызывать из основного потока. */
    public Result submit(Player reporter, Player target, String reason) {
        if (reporter.getUniqueId().equals(target.getUniqueId())) return Result.SELF;
        if (target.hasPermission("guardx.bypass") || target.hasPermission("guardx.admin")) return Result.EXEMPT;
        reason = reason == null ? "" : reason.trim();
        int minLen = plugin.getConfig().getInt("reports.min-reason-length", 3);
        if (reason.length() < minLen) return Result.NO_REASON;
        int maxLen = plugin.getConfig().getInt("reports.max-reason-length", 100);
        if (reason.length() > maxLen) reason = reason.substring(0, maxLen);

        long now = System.currentTimeMillis();
        prune(now);
        long cd = plugin.getConfig().getLong("reports.cooldown-seconds", 30) * 1000L;
        Long last = reporterCooldown.get(reporter.getUniqueId());
        if (last != null && now - last < cd && !reporter.hasPermission("guardx.admin")) return Result.COOLDOWN;

        String pair = reporter.getUniqueId() + ":" + target.getUniqueId();
        long dupMs = plugin.getConfig().getLong("reports.duplicate-minutes", 10) * 60_000L;
        Long prev = pairCooldown.get(pair);
        if (prev != null && now - prev < dupMs) return Result.DUPLICATE;

        reporterCooldown.put(reporter.getUniqueId(), now);
        pairCooldown.put(pair, now);

        Report r = create(reporter.getUniqueId(), reporter.getName(), target, reason, now);
        markSuspect(target.getUniqueId());
        notifyStaff(r, distinctReporters(target.getUniqueId()));
        checkThreshold(target);
        return Result.OK;
    }

    /** Автоматическая жалоба от античита, когда суммарный VL игрока перешёл порог. */
    public void autoReport(Player target, String reason) {
        if (!plugin.getConfig().getBoolean("reports.auto.enabled", true)) return;
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("reports.auto.cooldown-minutes", 5) * 60_000L;
        Long last = autoCooldown.get(target.getUniqueId());
        if (last != null && now - last < cd) return;
        autoCooldown.put(target.getUniqueId(), now);
        Report r = create(SYSTEM, "GuardX", target, reason, now);
        markSuspect(target.getUniqueId());
        notifyStaff(r, distinctReporters(target.getUniqueId()));
    }

    private Report create(UUID reporter, String reporterName, Player target, String reason, long now) {
        Map<String, Double> vl = plugin.violations().get(target.getUniqueId());
        StringBuilder snap = new StringBuilder();
        double total = 0;
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(vl.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        for (Map.Entry<String, Double> e : sorted) {
            if (e.getValue() <= 0) continue;
            total += e.getValue();
            if (snap.length() > 0) snap.append(", ");
            snap.append(e.getKey()).append('=').append(String.format(Locale.US, "%.1f", e.getValue()));
        }
        Location l = target.getLocation();
        Report r;
        synchronized (this) {
            r = new Report(nextId++, now, reporter, reporterName, target.getUniqueId(), target.getName(),
                    reason, snap.toString(), total, l.getWorld() == null ? "?" : l.getWorld().getName(),
                    l.getBlockX(), l.getBlockY(), l.getBlockZ(), false);
            reports.add(r);
            trim();
        }
        save();
        return r;
    }

    private void trim() {
        int max = Math.max(20, plugin.getConfig().getInt("reports.max-stored", 300));
        while (reports.size() > max) {
            // сначала выбрасываем закрытые, иначе самые старые
            Report drop = null;
            for (Report r : reports) if (r.closed) { drop = r; break; }
            if (drop == null) drop = reports.get(0);
            reports.remove(drop);
        }
    }

    // ------------------------------------------------------------ подозреваемые

    private void markSuspect(UUID id) {
        markSuspect(id, plugin.getConfig().getLong("reports.suspect-minutes", 10),
                plugin.getConfig().getDouble("reports.suspect-vl-multiplier", 1.5));
    }

    /** Ужесточение VL на {@code minutes} минут с множителем {@code mult}. Используется и сетью серверов. */
    public void markSuspect(UUID id, long minutes, double mult) {
        if (minutes <= 0) return;
        suspects.put(id, System.currentTimeMillis() + minutes * 60_000L);
        suspectMults.put(id, mult);
    }

    /** Множитель VL для игрока: >1, пока на него есть свежие жалобы или записи в сети. */
    public double vlMultiplier(UUID id) {
        Long until = suspects.get(id);
        if (until == null) return 1.0;
        if (System.currentTimeMillis() > until) {
            suspects.remove(id);
            suspectMults.remove(id);
            return 1.0;
        }
        double fallback = plugin.getConfig().getDouble("reports.suspect-vl-multiplier", 1.5);
        double m = suspectMults.getOrDefault(id, fallback);
        return Double.isFinite(m) && m >= 1.0 ? Math.min(m, 5.0) : 1.0;
    }

    public boolean isSuspect(UUID id) {
        return vlMultiplier(id) > 1.0;
    }

    // ------------------------------------------------------------ порог жалоб

    public int distinctReporters(UUID target) {
        long window = plugin.getConfig().getLong("reports.threshold.window-minutes", 15) * 60_000L;
        long now = System.currentTimeMillis();
        java.util.Set<UUID> set = new java.util.HashSet<>();
        for (Report r : reports) {
            if (r.closed || r.auto() || !r.target.equals(target)) continue;
            if (now - r.time <= window) set.add(r.reporter);
        }
        return set.size();
    }

    private void checkThreshold(Player target) {
        int need = plugin.getConfig().getInt("reports.threshold.count", 3);
        if (need <= 0) return;
        int have = distinctReporters(target.getUniqueId());
        if (have < need) return;

        String text = plugin.lang().msg("report-threshold", "player", target.getName(), "count", have);
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("guardx.alerts")) admin.sendMessage(text);
        }

        plugin.notifications().threshold(target.getName(), have);

        // Команда на достижение порога (например, наблюдение/spec или временный бан). Пусто = выключено.
        String cmd = plugin.getConfig().getString("reports.threshold.command", "");
        // только в момент достижения порога, а не на каждой следующей жалобе
        if (have == need && cmd != null && !cmd.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("%player%", target.getName()).replace("%count%", String.valueOf(have)));
        }
    }

    // ------------------------------------------------------------ оповещение модераторов

    private void notifyStaff(Report r, int reporters) {
        var lang = plugin.lang();
        String plain = lang.msg(r.auto() ? "report-auto" : "report-new", "id", r.id, "reporter", r.reporterName,
                "target", r.targetName, "reason", r.reason);

        StringBuilder hover = new StringBuilder();
        hover.append(lang.tr("report-hover-reporters", "count", reporters)).append('\n');
        hover.append(lang.tr("report-hover-vl", "vl", String.format(Locale.US, "%.1f", r.totalVl))).append('\n');
        hover.append("§7").append(r.snapshot.isEmpty() ? lang.plain("report-hover-none") : r.snapshot).append('\n');
        hover.append(lang.tr("report-hover-click"));

        // Bungee chat API есть и на Spigot, и на Paper всех версий 1.16+ (не зависит от Adventure)
        BaseComponent[] msg = null;
        try {
            msg = TextComponent.fromLegacyText(plain);
            HoverEvent he = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(TextComponent.fromLegacyText(hover.toString())));
            ClickEvent ce = new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/guardx goto " + r.targetName);
            for (BaseComponent b : msg) {
                b.setHoverEvent(he);
                b.setClickEvent(ce);
            }
        } catch (Throwable ex) {
            msg = null; // очень старый API — отправим обычным текстом
        }

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (!admin.hasPermission("guardx.alerts")) continue;
            if (msg != null) admin.spigot().sendMessage(msg);
            else admin.sendMessage(plain);
        }
        plugin.notifications().report(r);
        plugin.getLogger().info("REPORT#" + r.id + " " + r.reporterName + " -> " + r.targetName + ": " + r.reason
                + (r.snapshot.isEmpty() ? "" : " [" + r.snapshot + "]"));
    }

    // ------------------------------------------------------------ чтение / управление

    public List<Report> open() {
        List<Report> out = new ArrayList<>();
        for (Report r : reports) if (!r.closed) out.add(r);
        out.sort(Comparator.comparingLong((Report r) -> r.time).reversed());
        return out;
    }

    public List<Report> openFor(String name) {
        List<Report> out = new ArrayList<>();
        for (Report r : open()) if (r.targetName.equalsIgnoreCase(name)) out.add(r);
        return out;
    }

    public Report byId(int id) {
        for (Report r : reports) if (r.id == id) return r;
        return null;
    }

    /** Закрывает жалобу; @return reporter, которого нужно уведомить (или null). */
    public boolean close(Report r) {
        if (r.closed) return false;
        r.closed = true;
        save();
        return true;
    }

    public int closeAllFor(String name) {
        int n = 0;
        for (Report r : reports) {
            if (!r.closed && r.targetName.equalsIgnoreCase(name)) { r.closed = true; n++; }
        }
        if (n > 0) save();
        return n;
    }

    public int openCount() {
        int n = 0;
        for (Report r : reports) if (!r.closed) n++;
        return n;
    }

    // ------------------------------------------------------------ хранение

    private void load() {
        if (!Files.exists(file)) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
            nextId = Math.max(1, y.getInt("next-id", 1));
            ConfigurationSection sec = y.getConfigurationSection("reports");
            if (sec == null) return;
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    reports.add(new Report(id, s.getLong("time"),
                            UUID.fromString(s.getString("reporter", SYSTEM.toString())),
                            s.getString("reporter-name", "?"),
                            UUID.fromString(s.getString("target", SYSTEM.toString())),
                            s.getString("target-name", "?"),
                            s.getString("reason", ""), s.getString("snapshot", ""),
                            s.getDouble("total-vl"), s.getString("world", "?"),
                            s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getBoolean("closed")));
                    nextId = Math.max(nextId, id + 1);
                } catch (IllegalArgumentException ignored) {
                    // битая запись — пропускаем
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("reports.yml: " + ex.getMessage());
        }
    }

    /** Сериализует в основном потоке, пишет на диск — в отдельном. */
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Report r : reports) {
            String p = "reports." + r.id + ".";
            y.set(p + "time", r.time);
            y.set(p + "reporter", r.reporter.toString());
            y.set(p + "reporter-name", r.reporterName);
            y.set(p + "target", r.target.toString());
            y.set(p + "target-name", r.targetName);
            y.set(p + "reason", r.reason);
            y.set(p + "snapshot", r.snapshot);
            y.set(p + "total-vl", r.totalVl);
            y.set(p + "world", r.world);
            y.set(p + "x", r.x);
            y.set(p + "y", r.y);
            y.set(p + "z", r.z);
            y.set(p + "closed", r.closed);
        }
        String data = y.saveToString();
        try {
            writer.execute(() -> {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, data, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    plugin.getLogger().warning("reports.yml: " + ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // плагин выключается
        }
    }

    public void shutdown() {
        save();
        writer.shutdown();
        try {
            writer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void forget(UUID id) {
        reporterCooldown.remove(id);
    }

    /** Чистит устаревшие записи карт кулдаунов и подозреваемых, чтобы они не росли на долгоживущем сервере. */
    private void prune(long now) {
        if (now - lastPrune < 60_000L) return;
        lastPrune = now;
        long dupMs = plugin.getConfig().getLong("reports.duplicate-minutes", 10) * 60_000L;
        long autoMs = plugin.getConfig().getLong("reports.auto.cooldown-minutes", 5) * 60_000L;
        long repMs = plugin.getConfig().getLong("reports.cooldown-seconds", 30) * 1000L;
        pairCooldown.values().removeIf(t -> now - t >= dupMs);
        autoCooldown.values().removeIf(t -> now - t >= autoMs);
        reporterCooldown.values().removeIf(t -> now - t >= repMs);
        suspects.entrySet().removeIf(e -> {
            if (now <= e.getValue()) return false;
            suspectMults.remove(e.getKey());
            return true;
        });
    }
}
