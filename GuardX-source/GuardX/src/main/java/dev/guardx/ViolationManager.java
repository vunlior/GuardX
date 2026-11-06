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

import dev.guardx.api.GuardFlagEvent;
import dev.guardx.api.GuardPunishEvent;
import dev.guardx.data.PlayerData;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.LongAdder;

/** Хранит VL, рассылает оповещения, пишет лог и выдаёт наказание. */
public final class ViolationManager {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GuardX plugin;
    private final Map<UUID, Map<String, Double>> vls = new ConcurrentHashMap<>();
    /** Сколько раз игрока уже наказывали (живёт до перезапуска сервера, не сбрасывается при выходе). */
    private final Map<UUID, Integer> punishCount = new ConcurrentHashMap<>();
    private final Set<UUID> debugSubs = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<UUID> mutedAlerts = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Сколько раз сработала каждая проверка с момента запуска (для /guardx stats). */
    private final Map<String, LongAdder> flagTotals = new ConcurrentHashMap<>();
    private final LongAdder punishTotal = new LongAdder();
    private final Path logFile;
    private final ExecutorService logWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GuardX-LogWriter");
        thread.setDaemon(true);
        return thread;
    });

    public ViolationManager(GuardX plugin) {
        this.plugin = plugin;
        this.logFile = plugin.getDataFolder().toPath().resolve("violations.log");
    }

    public void flag(Player p, PlayerData d, String check, String info, double vl) {
        if (!Double.isFinite(vl) || vl <= 0) return;
        // На игрока, на которого пожаловались, смотрим строже
        vl *= plugin.reports().vlMultiplier(p.getUniqueId());
        Map<String, Double> m = vls.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        // API: другие плагины могут отменить нарушение
        double preview = m.getOrDefault(check, 0.0) + vl;
        GuardFlagEvent flagEvent = new GuardFlagEvent(p, check, info, vl, preview);
        Bukkit.getPluginManager().callEvent(flagEvent);
        if (flagEvent.isCancelled()) return;

        flagTotals.computeIfAbsent(check, k -> new LongAdder()).increment();
        double total = m.merge(check, vl, Double::sum);
        // Топ нарушенных проверок с VL — уходит в запись о бане в сети серверов как доказательство
        String evidence = evidenceOf(m);
        long now = System.currentTimeMillis();
        record(p, check, info, total);
        sendDebug(p, check, info, vl, total);

        FileConfiguration cfg = plugin.getConfig();
        long cooldown = cfg.getLong("alert-cooldown-ms", 700);
        Long last = d.lastAlert.get(check);
        if (last == null || now - last >= cooldown) {
            d.lastAlert.put(check, now);
            sendAlert(p, check, info, total);
        }

        double sum = 0;
        for (double v : m.values()) if (Double.isFinite(v) && v > 0) sum += v;

        // Авто-жалоба: игрок набрал много VL суммарно — модераторы увидят его в /guardx reports
        double autoVl = cfg.getDouble("reports.auto.vl", 40.0);
        if (autoVl > 0 && sum >= autoVl) {
            plugin.reports().autoReport(p, plugin.lang().plain("auto-reason", "vl", String.format(Locale.US, "%.0f", sum), "check", check));
        }

        if (cfg.getBoolean("punish.enabled", true)) {
            double limit = cfg.getDouble("checks." + check + ".punish-vl", cfg.getDouble("punish.vl", 25.0));
            // punish-vl <= 0 отключает наказание для проверки: остаются только оповещения.
            if (limit > 0 && total >= limit) {
                m.put(check, 0.0);
                punish(p, check, total, evidence);
                return;
            }
            // Суммарный порог: читеры с набором «мелких» флагов по многим проверкам сразу
            double totalLimit = cfg.getDouble("punish.total-vl", 0.0);
            if (totalLimit > 0 && sum >= totalLimit) {
                m.replaceAll((k, v) -> 0.0);
                punish(p, "multi-check", sum, evidence);
            }
        }
    }

    private void record(Player p, String check, String info, double total) {
        plugin.history().add(p, check, info, total);
    }

    private void sendDebug(Player p, String check, String info, double add, double total) {
        if (debugSubs.isEmpty()) return;
        String line = plugin.lang().tr("debug-line", "player", p.getName(), "check", check,
                "add", String.format(Locale.US, "%.1f", add), "total", String.format(Locale.US, "%.1f", total), "info", info);
        for (UUID id : debugSubs) {
            Player admin = Bukkit.getPlayer(id);
            if (admin != null && admin.hasPermission("guardx.admin")) admin.sendMessage(line);
        }
    }

    /** @return true, если режим отладки теперь включён */
    public boolean toggleDebug(UUID id) {
        if (debugSubs.remove(id)) return false;
        debugSubs.add(id);
        return true;
    }

    /** Последние нарушения игрока из памяти в виде строк (новые в конце). Полная история — plugin.history(). */
    public List<String> history(UUID id) {
        List<String> out = new ArrayList<>();
        for (var e : plugin.history().recent(id)) out.add(HistoryStore.line(e));
        return out;
    }

    private void sendAlert(Player p, String check, String info, double total) {
        String msg = plugin.lang().msg("alert", "player", p.getName(), "check", check,
                "info", info, "vl", String.format(Locale.US, "%.1f", total));
        broadcast(msg, p.getName());
        writeLog(p.getName() + " " + check + " " + info + " VL=" + String.format(Locale.US, "%.1f", total));
    }

    /**
     * Сообщение всем модераторам с правом guardx.alerts (кроме выключивших оповещения).
     * Для админов клик по сообщению телепортирует к игроку {@code teleportTo}. Использует и сеть серверов.
     */
    public void broadcast(String msg, String teleportTo) {
        // Клик по оповещению — телепорт к игроку (/guardx goto). Bungee chat API есть на Spigot и Paper 1.16+.
        BaseComponent[] clickable = null;
        if (plugin.getConfig().getBoolean("alert-click-teleport", true)) {
            try {
                clickable = TextComponent.fromLegacyText(msg);
                HoverEvent he = new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text(TextComponent.fromLegacyText(plugin.lang().tr("alert-hover", "player", teleportTo))));
                ClickEvent ce = new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/guardx goto " + teleportTo);
                for (BaseComponent b : clickable) {
                    b.setHoverEvent(he);
                    b.setClickEvent(ce);
                }
            } catch (Throwable ex) {
                clickable = null;
            }
        }

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (!admin.hasPermission("guardx.alerts") || mutedAlerts.contains(admin.getUniqueId())) continue;
            if (clickable != null && admin.hasPermission("guardx.admin")) admin.spigot().sendMessage(clickable);
            else admin.sendMessage(msg);
        }
        plugin.getLogger().info(ChatColor.stripColor(msg));
    }

    /** Топ-3 проверки с наибольшим VL: "speed=12.0, fly=3.0" (пусто, если VL нет). */
    private static String evidenceOf(Map<String, Double> m) {
        StringBuilder sb = new StringBuilder();
        m.entrySet().stream()
                .filter(e -> Double.isFinite(e.getValue()) && e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(e.getKey()).append('=').append(String.format(Locale.US, "%.1f", e.getValue()));
                });
        return sb.toString();
    }

    private void punish(Player p, String check, double total, String evidence) {
        FileConfiguration cfg = plugin.getConfig();
        if (punishCount.size() > 5000) punishCount.clear(); // защита от бесконечного роста на долгоживущем сервере
        int times = punishCount.getOrDefault(p.getUniqueId(), 0) + 1;

        // Рецидивист: после N наказаний вместо обычной команды выполняется строгая (например, бан)
        String template = cfg.getString("punish.command", "kick %player%");
        int repeatAfter = cfg.getInt("punish.repeat.count", 0);
        String repeatCmd = cfg.getString("punish.repeat.command", "");
        if (repeatAfter > 0 && times >= repeatAfter && repeatCmd != null && !repeatCmd.isBlank()) {
            template = repeatCmd;
        }

        String cmd = template
                .replace("%player%", p.getName())
                .replace("%check%", check)
                .replace("%reason%", plugin.lang().plain("punish-reason", "check", check))
                .replace("%count%", String.valueOf(times));
        GuardPunishEvent punishEvent = new GuardPunishEvent(p, check, total, times, cmd);
        Bukkit.getPluginManager().callEvent(punishEvent);
        if (punishEvent.isCancelled()) return;
        punishCount.put(p.getUniqueId(), times);
        punishTotal.increment();
        plugin.notifications().punish(p.getName(), check, total, times, cmd);
        plugin.network().recordPunish(p, check, total, cmd, evidence);

        writeLog("PUNISH#" + times + " " + p.getName() + " " + check + " VL=" + String.format(Locale.US, "%.1f", total) + " -> " + cmd);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private void writeLog(String line) {
        if (!plugin.getConfig().getBoolean("log-to-file", true)) return;
        String row = "[" + LocalDateTime.now().format(TIME) + "] " + line + System.lineSeparator();
        final long maxBytes = plugin.getConfig().getLong("log-max-size-mb", 5) * 1024L * 1024L;
        try {
            logWriter.execute(() -> {
                try {
                    Files.createDirectories(logFile.getParent());
                    // Ротация: когда файл разросся, старый уезжает в violations.old.log
                    if (maxBytes > 0 && Files.exists(logFile) && Files.size(logFile) > maxBytes) {
                        Files.move(logFile, logFile.resolveSibling("violations.old.log"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.writeString(logFile, row, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    plugin.getLogger().warning("violations.log: " + ex.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Плагин уже выключается: новые записи больше не принимаются.
        }
    }

    /** Завершает очередь записи логов при выключении плагина. */
    public void shutdown() {
        logWriter.shutdown();
    }

    /** Вызывается раз в 10 секунд: понемногу "прощаем" нарушения. */
    public void decay() {
        double amount = plugin.getConfig().getDouble("vl-decay-per-10s", 1.0);
        for (Map<String, Double> m : vls.values()) {
            m.replaceAll((k, v) -> Math.max(0.0, v - amount));
        }
    }

    /** Сколько раз сработала каждая проверка с запуска сервера. */
    public Map<String, Long> flagTotals() {
        Map<String, Long> out = new java.util.HashMap<>();
        flagTotals.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    public long punishTotal() {
        return punishTotal.sum();
    }

    public Map<String, Double> get(UUID id) {
        return vls.getOrDefault(id, Collections.emptyMap());
    }

    /** Immutable point-in-time copy for admin reports and leaderboards. */
    public Map<UUID, Map<String, Double>> snapshot() {
        Map<UUID, Map<String, Double>> copy = new ConcurrentHashMap<>();
        vls.forEach((id, values) -> copy.put(id, Map.copyOf(values)));
        return Map.copyOf(copy);
    }

    public void reset(UUID id) {
        vls.remove(id);
    }

    public void clear(UUID id) {
        vls.remove(id);
        plugin.history().unload(id);
        mutedAlerts.remove(id);
        debugSubs.remove(id);
    }

    /** @return true, если оповещения теперь включены */
    public boolean toggleAlerts(UUID id) {
        if (mutedAlerts.remove(id)) return true;
        mutedAlerts.add(id);
        return false;
    }

    public Set<UUID> mutedSnapshot() {
        return new HashSet<>(mutedAlerts);
    }
}
