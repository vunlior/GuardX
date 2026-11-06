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

import dev.guardx.api.GuardNetworkAlertEvent;
import dev.guardx.api.GuardNetworkRecord;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Сеть серверов: общая база банов за читы. Один сервер записывает бан, другие при входе игрока
 * предупреждают модераторов, что игрок уже был забанен в сети.
 * <ul>
 *   <li>MySQL/MariaDB (общая для всех серверов) или SQLite (только для проверки на одном сервере);</li>
 *   <li>все обращения к базе идут в одном фоновом потоке, основной поток сервера никогда не ждёт базу;</li>
 *   <li>результат возвращается в основной поток через планировщик Bukkit.</li>
 * </ul>
 * Оповещение модераторам идёт через {@link ViolationManager#broadcast} и {@link dev.guardx.notify.Notifications},
 * своих каналов здесь нет.
 */
public final class NetworkStore {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int MAX_ROWS = 20;
    /** Значение check у записей, сделанных по команде администратора, а не античитом. */
    public static final String MANUAL = "manual";

    /** Свежие сетевые оповещения для дайджеста модераторам при входе (хранится только в памяти). */
    private final Deque<Alert> recentAlerts = new ArrayDeque<>();

    private record Alert(long time, String text) {
    }

    private final GuardX plugin;
    /** Когда последний раз предупреждали о входе игрока: защита от спама при перезаходах. */
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GuardX-Network");
        t.setDaemon(true);
        return t;
    });

    // Поля ниже трогает только поток GuardX-Network.
    private Connection conn;
    private long lastWarnMs;

    public NetworkStore(GuardX plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("network.enabled", false);
    }

    /** Имя этого сервера в сети (network.server-id). */
    public String serverId() {
        String id = plugin.getConfig().getString("network.server-id", "server-1");
        return id == null || id.isBlank() ? "server-1" : id.trim();
    }

    /** После /guardx reload: закрываем соединение, следующее обращение откроет его с новыми настройками. */
    public void reconnect() {
        submit(this::closeQuietly);
    }

    public void shutdown() {
        submit(this::closeQuietly);
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ запись

    /**
     * Вызывается из ViolationManager после наказания. Записывает в сеть только баны:
     * первое слово команды должно быть в network.record-commands (пустой список = любое наказание).
     */
    public void recordPunish(Player p, String check, double vl, String command, String evidence) {
        if (!enabled()) return;
        List<String> allowed = plugin.getConfig().getStringList("network.record-commands");
        if (!allowed.isEmpty()) {
            String first = command.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            final String word = first.startsWith("/") ? first.substring(1) : first;
            if (allowed.stream().noneMatch(word::equalsIgnoreCase)) return;
        }
        String info = command;
        if (evidence != null && !evidence.isEmpty()) info = command + " | vl: " + evidence;
        record(p.getUniqueId(), p.getName(), check, vl, info, ipHash(p), parseExpires(command));
    }

    /** Записать бан в общую базу (используется и из {@link dev.guardx.api.GuardAPI}). Асинхронно. */
    public void record(UUID id, String name, String check, double vl, String info) {
        record(id, name, check, vl, info, null, 0L);
    }

    /**
     * Записать бан в общую базу. {@code ipHash} — хеш IP игрока (не сам IP, см. {@link #ipHash}),
     * {@code expires} — момент истечения бана (0 = бессрочно).
     */
    public void record(UUID id, String name, String check, double vl, String info, String ipHash, long expires) {
        if (!enabled()) return;
        final String server = serverId();
        final long now = System.currentTimeMillis();
        final String n = clip(name, 32);
        final String c = clip(check, 32);
        final String i = clip(info, 255);
        submit(() -> {
            String sql = "INSERT INTO " + table()
                    + " (uuid, name, server_id, check_id, vl, info, ip_hash, expires, created)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                ps.setString(1, id.toString());
                ps.setString(2, n);
                ps.setString(3, server);
                ps.setString(4, c);
                ps.setDouble(5, vl);
                ps.setString(6, i);
                ps.setString(7, ipHash);
                ps.setLong(8, expires);
                ps.setLong(9, now);
                ps.executeUpdate();
            } catch (SQLException ex) {
                warn(ex);
            }
        });
    }

    /**
     * Снять записи о банах игрока (ошибочный бан, разбан). Ищет по UUID, а если его нет, по нику.
     * Результат (число снятых записей, -1 при ошибке базы) приходит в основной поток.
     */
    public void revoke(UUID id, String name, Consumer<Integer> done) {
        if (id == null && name == null) {
            done.accept(0);
            return;
        }
        final String where = id != null ? "uuid = ?" : "LOWER(name) = ?";
        final String key = id != null ? id.toString() : name.toLowerCase(Locale.ROOT);
        submit(() -> {
            int changed = -1;
            String sql = "UPDATE " + table() + " SET revoked = 1 WHERE revoked = 0 AND " + where;
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                ps.setString(1, key);
                changed = ps.executeUpdate();
            } catch (SQLException ex) {
                warn(ex);
            }
            final int result = changed;
            sync(() -> done.accept(result));
        });
    }

    // ------------------------------------------------------------ чтение

    /**
     * Записи о банах игрока (новые первыми, не больше {@value #MAX_ROWS}). Ищет по UUID, а если его нет, по нику.
     * В основной поток приходит список, а при ошибке базы {@code null}.
     *
     * @param otherServersOnly true = только записи с других серверов сети
     */
    public void lookup(UUID id, String name, boolean otherServersOnly, Consumer<List<GuardNetworkRecord>> done) {
        if (id == null && name == null) {
            done.accept(List.of());
            return;
        }
        final String where = id != null ? "uuid = ?" : "LOWER(name) = ?";
        final String key = id != null ? id.toString() : name.toLowerCase(Locale.ROOT);
        final String server = serverId();
        submit(() -> {
            List<GuardNetworkRecord> found = null;
            // expires = 0 — бессрочный бан; истёкшие временные баны не показываем и не учитываем
            String sql = "SELECT name, server_id, check_id, vl, info, created FROM " + table()
                    + " WHERE revoked = 0 AND (expires = 0 OR expires > ?) AND " + where
                    + (otherServersOnly ? " AND server_id <> ?" : "")
                    + " ORDER BY created DESC LIMIT " + MAX_ROWS;
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, key);
                if (otherServersOnly) ps.setString(3, server);
                try (ResultSet rs = ps.executeQuery()) {
                    List<GuardNetworkRecord> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new GuardNetworkRecord(rs.getLong("created"), rs.getString("name"),
                                rs.getString("server_id"), rs.getString("check_id"),
                                rs.getDouble("vl"), rs.getString("info")));
                    }
                    found = rows;
                }
            } catch (SQLException ex) {
                warn(ex);
            }
            final List<GuardNetworkRecord> result = found;
            sync(() -> done.accept(result));
        });
    }

    /**
     * Записи по хешу IP (обход бана с нового аккаунта с того же адреса). Ищет только по ip_hash,
     * UUID/ник не учитываются — это дополнительный сигнал, а не замена.
     */
    public void lookupIp(String ipHash, boolean otherServersOnly, Consumer<List<GuardNetworkRecord>> done) {
        if (ipHash == null || ipHash.isEmpty()) {
            done.accept(List.of());
            return;
        }
        final String server = serverId();
        submit(() -> {
            List<GuardNetworkRecord> found = null;
            String sql = "SELECT name, server_id, check_id, vl, info, created FROM " + table()
                    + " WHERE revoked = 0 AND ip_hash = ? AND (expires = 0 OR expires > ?)"
                    + (otherServersOnly ? " AND server_id <> ?" : "")
                    + " ORDER BY created DESC LIMIT " + MAX_ROWS;
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                ps.setString(1, ipHash);
                ps.setLong(2, System.currentTimeMillis());
                if (otherServersOnly) ps.setString(3, server);
                try (ResultSet rs = ps.executeQuery()) {
                    List<GuardNetworkRecord> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new GuardNetworkRecord(rs.getLong("created"), rs.getString("name"),
                                rs.getString("server_id"), rs.getString("check_id"),
                                rs.getDouble("vl"), rs.getString("info")));
                    }
                    found = rows;
                }
            } catch (SQLException ex) {
                warn(ex);
            }
            final List<GuardNetworkRecord> result = found;
            sync(() -> done.accept(result));
        });
    }

    /** Страница списка последних банов сети для /guardx netlist. */
    public record NetworkPage(List<GuardNetworkRecord> rows, int total) {
    }

    /** Последние незакрытые записи сети (новые первые). {@code offset} — смещение, {@code limit} — размер страницы. */
    public void listRecent(int offset, int limit, Consumer<NetworkPage> done) {
        if (!enabled()) {
            done.accept(new NetworkPage(List.of(), 0));
            return;
        }
        submit(() -> {
            NetworkPage page = null;
            try {
                int total = 0;
                try (PreparedStatement ps = connection().prepareStatement(
                        "SELECT COUNT(*) FROM " + table() + " WHERE revoked = 0")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) total = rs.getInt(1);
                    }
                }
                List<GuardNetworkRecord> rows = new ArrayList<>();
                String sql = "SELECT name, server_id, check_id, vl, info, created FROM " + table()
                        + " WHERE revoked = 0 ORDER BY created DESC LIMIT ? OFFSET ?";
                try (PreparedStatement ps = connection().prepareStatement(sql)) {
                    ps.setInt(1, Math.min(Math.max(limit, 1), 50));
                    ps.setInt(2, Math.max(offset, 0));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new GuardNetworkRecord(rs.getLong("created"), rs.getString("name"),
                                    rs.getString("server_id"), rs.getString("check_id"),
                                    rs.getDouble("vl"), rs.getString("info")));
                        }
                    }
                }
                page = new NetworkPage(rows, total);
            } catch (SQLException ex) {
                warn(ex);
            }
            final NetworkPage result = page;
            sync(() -> done.accept(result));
        });
    }

    // ------------------------------------------------------------ баны администраторов

    /**
     * Команда администратора (из игры или консоли): бан попадает в сеть как запись {@value #MANUAL},
     * разбан снимает записи игрока. Какие команды считаются баном и разбаном, задаёт network.admin-bans.
     *
     * @param sender      ник админа или CONSOLE
     * @param commandLine команда с косой чертой или без
     */
    public void onAdminCommand(String sender, String commandLine) {
        FileConfiguration cfg = plugin.getConfig();
        if (!enabled() || !cfg.getBoolean("network.admin-bans.enabled", true)) return;
        String line = commandLine.trim();
        if (line.startsWith("/")) line = line.substring(1);
        String[] t = line.split("\\s+");
        if (t.length < 2) return;

        String label = t[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1); // minecraft:ban, litebans:ban
        final String cmdLabel = label;
        boolean ban = cfg.getStringList("network.admin-bans.commands").stream().anyMatch(cmdLabel::equalsIgnoreCase);
        boolean unban = !ban && cfg.getBoolean("network.admin-bans.auto-revoke", true)
                && cfg.getStringList("network.admin-bans.unban-commands").stream().anyMatch(cmdLabel::equalsIgnoreCase);
        if (!ban && !unban) return;

        // цель: первое слово без флагов вроде -s; IP-адреса (бан по IP) пропускаем
        String target = null;
        for (int i = 1; i < t.length; i++) {
            if (!t[i].startsWith("-")) {
                target = t[i];
                break;
            }
        }
        if (target == null || !target.matches("[A-Za-z0-9_.]{1,32}") || target.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return;

        if (ban) {
            List<String> words = cfg.getStringList("network.admin-bans.reason-contains");
            if (!words.isEmpty()) {
                final String low = line.toLowerCase(Locale.ROOT);
                if (words.stream().noneMatch(w -> low.contains(w.toLowerCase(Locale.ROOT)))) return;
            }
        }

        final String name = target;
        final String info = "by " + sender + ": " + line;
        final long expires = parseExpires(line);
        Player online = Bukkit.getPlayerExact(name);
        final UUID known = online != null ? online.getUniqueId() : null;
        // UUID офлайн-игрока может потребовать запрос к Mojang, поэтому не в основном потоке
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UUID id = known != null ? known : Bukkit.getOfflinePlayer(name).getUniqueId();
                if (ban) record(id, name, MANUAL, 0.0, info, online != null ? ipHash(online) : null, expires);
                else revoke(id, null, changed -> { });
            } catch (RuntimeException ex) {
                plugin.getLogger().warning(plugin.lang().plain("warn-network-io", "error", String.valueOf(ex.getMessage())));
            }
        });
    }

    // ------------------------------------------------------------ вход игрока

    /**
     * Игрок зашёл: если в сети на него есть бан с другого сервера (по UUID/нику, а при пустом результате —
     * по хешу IP), предупреждаем модераторов, помечаем игрока как подозрительного и при включённой
     * синхронизации банов выполняем команду бана.
     */
    public void onJoin(Player p) {
        if (!enabled()) return;
        final UUID id = p.getUniqueId();
        final String name = p.getName();
        final long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getLong("network.alert-cooldown-minutes", 10)) * 60_000L;
        Long last = lastAlert.get(id);
        if (last != null && now - last < cooldown) return;
        if (lastAlert.size() > 5000) lastAlert.clear();

        boolean includeOwn = plugin.getConfig().getBoolean("network.include-own-server", false);
        lookup(id, null, !includeOwn, records -> {
            if (records == null || !p.isOnline()) return;
            if (records.isEmpty()) {
                lookupIpMatch(p, includeOwn);
                return;
            }
            handleRecords(p, records, false);
        });
    }

    /** Совпадение по хешу IP: читер зашёл с нового аккаунта, но с того же адреса. */
    private void lookupIpMatch(Player p, boolean includeOwn) {
        if (!plugin.getConfig().getBoolean("network.ip-match", true) || !p.isOnline()) return;
        String hash = ipHash(p);
        if (hash == null) return;
        lookupIp(hash, !includeOwn, records -> {
            if (records == null || records.isEmpty() || !p.isOnline()) return;
            handleRecords(p, records, true);
        });
    }

    /** Общая обработка найденных записей: оповещение, suspect-множитель, синхронизация банов. */
    private void handleRecords(Player p, List<GuardNetworkRecord> records, boolean byIp) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        lastAlert.put(id, System.currentTimeMillis());

        GuardNetworkAlertEvent event = new GuardNetworkAlertEvent(p, records);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        GuardNetworkRecord r = records.get(0);
        String ago = ago(r.time());
        String key = byIp ? "network-alert-ip"
                : MANUAL.equals(r.check()) ? "network-alert-manual" : "network-alert";
        String text = plugin.lang().msg(key, "player", name, "server", r.server(),
                "check", r.check(), "info", r.info(), "vl", Lang.n(r.vl(), 1), "ago", ago,
                "count", records.size());
        plugin.violations().broadcast(text, name);

        // Запоминаем для дайджеста модераторам, которые зайдут позже
        synchronized (recentAlerts) {
            recentAlerts.addLast(new Alert(System.currentTimeMillis(), text));
            while (recentAlerts.size() > 50) recentAlerts.pollFirst();
        }

        // Усиленное наблюдение: VL растут быстрее network.suspect-*
        long sm = plugin.getConfig().getLong("network.suspect-minutes", 10);
        double smMult = plugin.getConfig().getDouble("network.suspect-vl-multiplier", 1.5);
        if (sm > 0) plugin.reports().markSuspect(id, sm, smMult);

        plugin.notifications().network(name, r.server(), r.check(), r.vl(), records.size(), ago, r.info());

        // Синхронизация банов: забанили на одном сервере сети — баним и здесь (по умолчанию выключено)
        if (plugin.getConfig().getBoolean("network.sync-bans", false)) {
            String banCmd = plugin.getConfig().getString("network.sync-command", "");
            if (banCmd != null && !banCmd.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), banCmd
                        .replace("%player%", name).replace("%server%", safe(r.server()))
                        .replace("%check%", safe(r.check())).replace("%info%", safe(r.info())));
            }
        }

        String template = plugin.getConfig().getString("network.join-command", "");
        if (template != null && !template.isBlank()) {
            String cmd = template.replace("%player%", name).replace("%server%", safe(r.server()))
                    .replace("%check%", safe(r.check()));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    /**
     * Дайджест для модератора при входе: последние сетевые оповещения за network.digest-minutes минут.
     * Показывается только игрокам с правом guardx.alerts.
     */
    public void digestOnJoin(Player p) {
        if (!enabled() || !p.hasPermission("guardx.alerts")) return;
        long window = Math.max(0, plugin.getConfig().getLong("network.digest-minutes", 30)) * 60_000L;
        if (window <= 0) return;
        int max = Math.max(1, plugin.getConfig().getInt("network.digest-count", 5));
        long now = System.currentTimeMillis();
        List<String> fresh = new ArrayList<>();
        synchronized (recentAlerts) {
            for (Alert a : recentAlerts) {
                if (now - a.time() <= window) fresh.add(a.text());
            }
        }
        if (fresh.isEmpty()) return;
        int from = Math.max(0, fresh.size() - max);
        p.sendMessage(plugin.lang().msg("network-digest-title"));
        for (int i = fresh.size() - 1; i >= from; i--) p.sendMessage(fresh.get(i));
    }

    /**
     * SHA-256 от "соль|IP" — в базу попадает только хеш, сам IP не хранится.
     * Одинаковая соль (network.ip-salt) должна быть на всех серверах сети.
     */
    public String ipHash(Player p) {
        if (p == null || p.getAddress() == null || p.getAddress().getAddress() == null) return null;
        String ip = p.getAddress().getAddress().getHostAddress();
        if (ip == null || ip.isEmpty()) return null;
        String salt = plugin.getConfig().getString("network.ip-salt", "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(((salt == null ? "" : salt) + "|" + ip).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Срок действия бана из команды наказания. Понимает tempban/tempbanip с длительностью
     * вида 30m, 12h, 7d, 2w (можно несколько, берётся первая). Обычный ban — 0 (бессрочно).
     */
    private static final java.util.regex.Pattern DURATION = java.util.regex.Pattern.compile("\\d{1,9}[smhdw]");

    static long parseExpires(String command) {
        String[] t = command == null ? new String[0] : command.trim().split("\\s+");
        if (t.length < 2) return 0L;
        String label = t[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!label.startsWith("temp")) return 0L;
        // t[0] — команда, t[1] — ник: срок ищем только среди остальных токенов и только по полному совпадению
        for (int i = 2; i < t.length; i++) {
            String tok = t[i].toLowerCase(Locale.ROOT);
            if (!DURATION.matcher(tok).matches()) continue;
            long value = Long.parseLong(tok.substring(0, tok.length() - 1));
            long mult = switch (tok.charAt(tok.length() - 1)) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                default -> 604_800_000L;
            };
            return System.currentTimeMillis() + value * mult;
        }
        return 0L;
    }

    // ------------------------------------------------------------ форматирование

    public static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis));
    }

    /** «3 дн.», «5 ч.», «12 мин.» (тексты из языковых файлов). */
    public String ago(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60_000L);
        if (minutes >= 1440) return plugin.lang().plain("network-ago-days", "n", minutes / 1440);
        if (minutes >= 60) return plugin.lang().plain("network-ago-hours", "n", minutes / 60);
        return plugin.lang().plain("network-ago-minutes", "n", minutes);
    }

    /** Значения из базы подставляются в консольные команды: оставляем только безопасные символы. */
    private static String safe(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[^A-Za-z0-9_.\\- ]", "_");
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------ база (поток GuardX-Network)

    private void submit(Runnable r) {
        try {
            io.execute(r);
        } catch (RejectedExecutionException ignored) {
            // плагин выключается
        }
    }

    /** Выполнить в основном потоке сервера. */
    private void sync(Runnable r) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (IllegalStateException ignored) {
            // плагин выключается
        }
    }

    private String table() {
        String prefix = plugin.getConfig().getString("network.storage.table-prefix", "guardx_");
        if (prefix == null || !prefix.matches("[A-Za-z0-9_]{0,32}")) prefix = "guardx_";
        return prefix + "network_bans";
    }

    private Connection connection() throws SQLException {
        if (conn != null) {
            try {
                if (conn.isValid(2)) return conn;
            } catch (SQLException ignored) {
                // переподключимся ниже
            }
            closeQuietly();
        }
        FileConfiguration c = plugin.getConfig();
        boolean sqlite = "sqlite".equalsIgnoreCase(c.getString("network.storage.type", "mysql"));
        Connection fresh;
        if (sqlite) {
            loadDriver("org.sqlite.JDBC");
            File file = new File(plugin.getDataFolder(), c.getString("network.storage.file", "network.db"));
            plugin.getDataFolder().mkdirs();
            fresh = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        } else {
            loadDriver("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver");
            String url = "jdbc:mysql://" + c.getString("network.storage.host", "localhost")
                    + ":" + c.getInt("network.storage.port", 3306)
                    + "/" + c.getString("network.storage.database", "guardx")
                    + "?useSSL=" + c.getBoolean("network.storage.use-ssl", false)
                    + "&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8"
                    + "&connectTimeout=5000&socketTimeout=8000";
            fresh = DriverManager.getConnection(url, c.getString("network.storage.user", "guardx"),
                    c.getString("network.storage.password", ""));
        }
        try {
            initSchema(fresh, sqlite);
        } catch (SQLException ex) {
            try {
                fresh.close();
            } catch (SQLException ignored) {
                // уже сломано
            }
            throw ex;
        }
        conn = fresh;
        return conn;
    }

    private static void loadDriver(String... classNames) throws SQLException {
        for (String name : classNames) {
            try {
                Class.forName(name);
                return;
            } catch (ClassNotFoundException ignored) {
                // пробуем следующий
            }
        }
        throw new SQLException("JDBC driver not found: " + classNames[0]);
    }

    private void initSchema(Connection c, boolean sqlite) throws SQLException {
        String t = table();
        try (Statement st = c.createStatement()) {
            if (sqlite) {
                st.execute("CREATE TABLE IF NOT EXISTS " + t + " (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "uuid TEXT NOT NULL, name TEXT NOT NULL, server_id TEXT NOT NULL, check_id TEXT NOT NULL, "
                        + "vl REAL NOT NULL, info TEXT NOT NULL, ip_hash TEXT, expires INTEGER NOT NULL DEFAULT 0, "
                        + "created INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE INDEX IF NOT EXISTS " + t + "_uuid ON " + t + " (uuid)");
            } else {
                st.execute("CREATE TABLE IF NOT EXISTS " + t + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "uuid CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, server_id VARCHAR(64) NOT NULL, "
                        + "check_id VARCHAR(32) NOT NULL, vl DOUBLE NOT NULL, info VARCHAR(255) NOT NULL, "
                        + "ip_hash VARCHAR(64) NULL, expires BIGINT NOT NULL DEFAULT 0, "
                        + "created BIGINT NOT NULL, revoked TINYINT(1) NOT NULL DEFAULT 0, "
                        + "INDEX idx_uuid (uuid), INDEX idx_name (name), INDEX idx_ip (ip_hash)) DEFAULT CHARSET=utf8mb4");
            }
            // Миграция для таблиц, созданных до появления ip_hash/expires: дубли колонок просто игнорируем
            try { st.execute("ALTER TABLE " + t + " ADD COLUMN ip_hash VARCHAR(64) NULL"); } catch (SQLException ignored) { }
            try { st.execute("ALTER TABLE " + t + " ADD COLUMN expires BIGINT NOT NULL DEFAULT 0"); } catch (SQLException ignored) { }
            // Индекс по ip_hash после миграции колонок. MySQL не знает IF NOT EXISTS для индексов (это только MariaDB/SQLite):
            // обычный CREATE INDEX, ошибку «индекс уже есть» игнорируем
            try {
                st.execute(sqlite ? "CREATE INDEX IF NOT EXISTS " + t + "_ip ON " + t + " (ip_hash)"
                        : "CREATE INDEX idx_ip ON " + t + " (ip_hash)");
            } catch (SQLException ignored) { }
        }
        int days = plugin.getConfig().getInt("network.retention-days", 180);
        if (days > 0) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE created < ?")) {
                ps.setLong(1, System.currentTimeMillis() - days * 86_400_000L);
                ps.executeUpdate();
            }
        }
    }

    private void closeQuietly() {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
            // закрываем и так
        }
        conn = null;
    }

    /** Не чаще раза в минуту, иначе недоступная база засыпала бы консоль при каждом входе. */
    private void warn(SQLException ex) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 60_000L) return;
        lastWarnMs = now;
        String message = String.valueOf(ex.getMessage());
        String password = plugin.getConfig().getString("network.storage.password", "");
        if (password != null && !password.isEmpty()) message = message.replace(password, "***");
        plugin.getLogger().warning(plugin.lang().plain("warn-network-io", "error", message));
    }
}
