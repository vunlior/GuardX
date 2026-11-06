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

import dev.guardx.check.CheckManager;
import dev.guardx.data.PlayerData;
import dev.guardx.hook.Hooks;
import dev.guardx.notify.Notifications;
import dev.guardx.packet.PacketLayer;
import dev.guardx.packet.PacketSettings;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.GameMode;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuardX extends JavaPlugin {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private ViolationManager violations;
    private ReportManager reports;
    private volatile boolean lowTps;
    private CheckManager checks;
    private Lang lang;
    private Notifications notifications;
    private HistoryStore history;
    private NetworkStore network;
    private LayerManager layers;
    private boolean placeholdersHooked;
    /** Пакетный слой: PacketEvents, если он установлен, иначе встроенный. Может быть не запущен (packets.backend: off). */
    private PacketLayer packets;
    private long enabledAtMs;
    private volatile PacketSettings settings;
    private volatile double groundHeight = 0.2;
    /** Растёт при каждой перезагрузке конфига: по нему проверки сбрасывают свой кэш настроек. */
    private volatile int configVersion;

    /**
     * Первый запуск: кладём config.yml на языке сервера (русский для ru_*, иначе английский).
     * Язык можно сменить потом: параметр language в конфиге и файлы в lang/.
     */
    private void installDefaultConfig() {
        java.io.File target = new java.io.File(getDataFolder(), "config.yml");
        if (target.exists()) return;
        boolean ru = "ru".equalsIgnoreCase(java.util.Locale.getDefault().getLanguage());
        if (!ru) {
            try (java.io.InputStream in = getResource("config_en.yml")) {
                if (in != null) {
                    getDataFolder().mkdirs();
                    java.nio.file.Files.copy(in, target.toPath());
                    return;
                }
            } catch (java.io.IOException ex) {
                getLogger().warning("config_en.yml: " + ex.getMessage());
            }
        }
        saveDefaultConfig();
    }

    @Override
    public void onEnable() {
        long startedAt = System.currentTimeMillis();
        installDefaultConfig();
        lang = new Lang(this);
        lang.load(getConfig().getString("language", "ru"));
        notifications = new Notifications(this);
        layers = new LayerManager(this);
        checkServerVersion();
        validateConfig();
        settings = new PacketSettings(getConfig());
        groundHeight = getConfig().getDouble("ground-height", 0.2);

        reports = new ReportManager(this);
        history = new HistoryStore(this);
        network = new NetworkStore(this);
        violations = new ViolationManager(this);
        checks = new CheckManager(this);

        getServer().getPluginManager().registerEvents(new GuardListener(this), this);

        // Пакетный слой: PacketEvents необязателен (softdepend). Нет его — работает встроенный слой на Netty.
        packets = new PacketLayer(this);
        packets.start();

        GuardCommand command = new GuardCommand(this);
        if (getCommand("guardx") != null) {
            getCommand("guardx").setExecutor(command);
            getCommand("guardx").setTabCompleter(command);
        }
        if (getCommand("report") != null) {
            getCommand("report").setExecutor(command);
            getCommand("report").setTabCompleter(command);
        }

        // каждый тик запоминаем хитбоксы игроков — для компенсации пинга в Reach и KillAura
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player p : getServer().getOnlinePlayers()) {
                PlayerData d = data(p);
                d.history.add(now, p.getBoundingBox());
                checks.handleTick(p, d, now);
            }
        }, 1L, 1L);

        // защита от ложных срабатываний при просадке TPS: пока сервер лагает, проверки движения не работают
        getServer().getScheduler().runTaskTimer(this, () -> {
            double min = getConfig().getDouble("lag-protect.min-tps", 16.0);
            if (min <= 0) { lowTps = false; return; }
            try {
                double[] tps = getServer().getTPS();
                lowTps = tps != null && tps.length > 0 && tps[0] < min;
            } catch (Throwable ex) {
                lowTps = false; // API недоступен (не Paper) — защиту пропускаем
            }
        }, 100L, 40L);

        // раз в 10 секунд снижаем VL
        getServer().getScheduler().runTaskTimer(this, () -> violations.decay(), 200L, 200L);

        placeholdersHooked = Hooks.registerPlaceholders(this);
        startMetrics();

        enabledAtMs = System.currentTimeMillis();
        printEnableBanner(enabledAtMs - startedAt);
    }

    @Override
    public void onDisable() {
        int tracked = data.size();
        // Снимаем пакетный слой до очистки данных, чтобы он не продолжал вызывать наш код.
        if (packets != null) {
            packets.stop();
        }
        if (violations != null) violations.shutdown();
        if (reports != null) reports.shutdown();
        data.clear();
        if (notifications != null) notifications.shutdown();
        if (history != null) history.shutdown();
        if (network != null) network.shutdown();
        if (lang != null) printDisableBanner(tracked);
    }

    /** Анонимная статистика bStats (https://bstats.org/plugin/bukkit/GuardX/35139). Владелец сервера может отключить её в plugins/bStats/config.yml. */
    private void startMetrics() {
        try {
            Metrics metrics = new Metrics(this, 35139);
            metrics.addCustomChart(new SimplePie("language", () -> lang.code()));
            metrics.addCustomChart(new SimplePie("punishments",
                    () -> getConfig().getBoolean("punish.enabled", true) ? "enabled" : "observe-only"));
        } catch (Throwable ex) {
            getLogger().warning("bStats: " + ex);
        }
    }

    // ---------------------------------------------------------------- красивый лог

    private void console(String msg) {
        getServer().getConsoleSender().sendMessage(msg);
    }

    public String authors() {
        var list = getDescription().getAuthors();
        return list.isEmpty() ? "Vunlior" : String.join(", ", list);
    }

    private void printEnableBanner(long tookMs) {
        PacketSettings st = settings;
        StringBuilder layers = new StringBuilder();
        if (st != null) {
            if (st.timer) layers.append("Timer, ");
            if (st.clicks) layers.append("AutoClicker, ");
            if (st.killaura) layers.append("KillAura, ");
            if (st.aim) layers.append("Aim, ");
            if (st.noSwing) layers.append("NoSwing, ");
            if (st.badPackets) layers.append("BadPackets, ");
        }
        String packetModules = layers.length() == 0 ? "-" : layers.substring(0, layers.length() - 2);

        StringBuilder hooks = new StringBuilder();
        if (placeholdersHooked) hooks.append("PlaceholderAPI, ");
        if (notifications != null) {
            for (var channel : notifications.channels()) {
                if (channel.enabled()) {
                    String n = channel.name();
                    hooks.append(Character.toUpperCase(n.charAt(0))).append(n.substring(1)).append(", ");
                }
            }
        }
        String hookList = hooks.length() == 0 ? "-" : hooks.substring(0, hooks.length() - 2);

        String punish = lang.tr(getConfig().getBoolean("punish.enabled", true) ? "banner-punish-on" : "banner-punish-off");

        console(lang.tr("banner-line"));
        console(lang.tr("banner-enabled", "version", getDescription().getVersion()));
        console(lang.tr("banner-line"));
        console(lang.tr("banner-author", "value", authors()));
        String mcVersion;
        try {
            mcVersion = getServer().getMinecraftVersion(); // Paper 1.16.5+
        } catch (Throwable ex) {
            mcVersion = getServer().getBukkitVersion();
        }
        console(lang.tr("banner-server", "value", getServer().getName() + " " + mcVersion));
        console(lang.tr("banner-backend", "value", packets == null ? "-" : packets.name()));
        console(lang.tr("banner-checks", "value", checks.all().size()));
        console(lang.tr("banner-packet", "value", packetModules));
        console(lang.tr("banner-punish", "value", punish));
        console(lang.tr("banner-hooks", "value", hookList));
        console(lang.tr("banner-lang", "value", lang.code()));
        console(lang.tr("banner-startup", "value", tookMs));
        console(lang.tr("banner-line"));
    }

    private void printDisableBanner(int trackedPlayers) {
        long uptime = enabledAtMs == 0 ? 0 : (System.currentTimeMillis() - enabledAtMs) / 1000;
        String time = lang.tr("uptime-format", "h", uptime / 3600,
                "m", String.format("%02d", (uptime % 3600) / 60), "s", String.format("%02d", uptime % 60));
        console(lang.tr("banner-line"));
        console(lang.tr("banner-disabled", "version", getDescription().getVersion()));
        console(lang.tr("banner-line"));
        console(lang.tr("banner-uptime", "value", time));
        console(lang.tr("banner-cleared", "value", trackedPlayers));
        console(lang.tr("banner-author", "value", authors()));
        console(lang.tr("banner-line"));
    }

    public void reloadAll() {
        reloadConfig();
        configVersion++;
        lang.load(getConfig().getString("language", "ru"));
        layers.reload();
        if (network != null) network.reconnect();
        validateConfig();
        settings = new PacketSettings(getConfig());
        groundHeight = getConfig().getDouble("ground-height", 0.2);
    }

    /** Поддерживаются версии Minecraft 1.16 — 1.21.x. На остальных работаем, но предупреждаем: пороги там не проверялись. */
    private void checkServerVersion() {
        String v;
        try {
            v = getServer().getMinecraftVersion();
        } catch (Throwable ex) {
            v = getServer().getBukkitVersion();
        }
        if (v == null || !v.matches("1\\.(1[6-9]|2[01])(\\..*)?(-.*)?")) {
            getLogger().warning(lang.plain("warn-unsupported-version", "version", String.valueOf(v)));
        }
    }

    /** Проверяет опасные/некорректные значения конфигурации и сообщает о них без изменения наказаний. */
    private void validateConfig() {
        double decay = getConfig().getDouble("vl-decay-per-10s", 1.0);
        if (!Double.isFinite(decay) || decay < 0) {
            getLogger().warning(lang.plain("warn-decay"));
        }
        double globalVl = getConfig().getDouble("punish.vl", 25.0);
        if (!Double.isFinite(globalVl) || globalVl <= 0) {
            getLogger().warning(lang.plain("warn-punish-vl"));
        }
        if (getConfig().getBoolean("checks.fastbreak.enabled", false)) {
            getLogger().warning(lang.plain("warn-fastbreak"));
        }
        // Сеть серверов: типичные ошибки конфигурации, которые молча ломают общий бан-лист
        if (getConfig().getBoolean("network.enabled", false)) {
            String id = getConfig().getString("network.server-id", "server-1");
            if (id == null || id.isBlank() || id.trim().equals("server-1")) {
                getLogger().warning(lang.plain("warn-network-server-id"));
            }
            if (getConfig().getBoolean("network.ip-match", true)
                    && getConfig().getString("network.ip-salt", "").isBlank()) {
                getLogger().warning(lang.plain("warn-network-salt"));
            }
            if (!getConfig().getBoolean("network.storage.use-ssl", false)
                    && !"sqlite".equalsIgnoreCase(getConfig().getString("network.storage.type", "mysql"))) {
                getLogger().warning(lang.plain("warn-network-ssl"));
            }
            String storage = getConfig().getString("network.storage.type", "mysql");
            if (!"sqlite".equalsIgnoreCase(storage)
                    && getConfig().getString("network.storage.password", "").isBlank()) {
                getLogger().warning(lang.plain("warn-network-password"));
            }
        }
    }

    public int configVersion() {
        return configVersion;
    }

    /** TPS сервера (1 минута). Вне Paper метода нет — тогда считаем, что сервер не лагает. */
    public double tps() {
        try {
            double[] t = getServer().getTPS();
            return t != null && t.length > 0 ? t[0] : 20.0;
        } catch (Throwable ex) {
            return 20.0;
        }
    }

    /** Игрок не проверяется: право guardx.bypass или временное исключение (/guardx exempt). */
    public boolean bypass(Player p) {
        if (p.hasPermission("guardx.bypass")) return true;
        PlayerData d = data.get(p.getUniqueId());
        return d != null && d.exemptUntil > System.currentTimeMillis();
    }

    /** Временно (millis > 0) или снова (millis <= 0) проверять игрока: /guardx exempt и GuardAPI. */
    public void setExempt(Player p, long millis) {
        data(p).exemptUntil = millis > 0 ? System.currentTimeMillis() + millis : 0L;
    }

    /**
     * Нужно ли вообще учитывать нарушение {@code check} этого игрока: не обход, не наблюдатель, не отключено слоем.
     * Общий шлюз для нарушений из сетевого потока и из API.
     */
    public boolean shouldCheck(Player p, String check) {
        return !bypass(p) && p.getGameMode() != GameMode.SPECTATOR && !layers.skips(p, check);
    }

    public PlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), PlayerData::new);
    }

    /** Данные игрока, если они уже есть. Безопасно вызывать из сетевого потока. */
    public PlayerData peekData(UUID id) {
        return data.get(id);
    }

    public void removeData(UUID id) {
        data.remove(id);
        if (packets != null) packets.forget(id);
    }

    /** Пакетный слой (может быть не запущен). Нужен слушателю входа/выхода игроков. */
    public PacketLayer packetLayer() {
        return packets;
    }

    public Lang lang() {
        return lang;
    }

    public Notifications notifications() {
        return notifications;
    }

    public HistoryStore history() {
        return history;
    }

    public NetworkStore network() {
        return network;
    }

    public LayerManager layers() {
        return layers;
    }

    public ViolationManager violations() {
        return violations;
    }

    public ReportManager reports() {
        return reports;
    }

    /** Сервер сейчас сильно лагает: проверки движения и боя пропускаются, чтобы не наказывать за лаги. */
    public boolean lowTps() {
        return lowTps;
    }

    public CheckManager checks() {
        return checks;
    }

    public double groundHeight() {
        return groundHeight;
    }

    public PacketSettings settings() {
        return settings;
    }

    /**
     * Нарушение, найденное в сетевом потоке. Всё, что трогает игрока или мир,
     * выполняется уже в основном потоке сервера.
     */
    public void flagAsync(UUID id, String check, String info, double vl, boolean setback) {
        if (!isEnabled()) return;
        try {
            getServer().getScheduler().runTask(this, () -> {
                Player p = getServer().getPlayer(id);
                if (p == null || !shouldCheck(p, check)) return;

                PlayerData d = data(p);
                violations.flag(p, d, check, info, vl);

                if (setback && getConfig().getBoolean("setback", true)) {
                    Location safe = d.lastSafe;
                    if (safe != null && safe.getWorld() != null && safe.getWorld().equals(p.getWorld())) {
                        Location back = safe.clone();
                        back.setYaw(p.getLocation().getYaw());
                        back.setPitch(p.getLocation().getPitch());
                        d.ignoreTeleportUntil = System.currentTimeMillis() + 200;
                        d.resetMovement();
                        p.teleport(back);
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // плагин выключается — нарушение уже не важно
        }
    }
}
