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

import dev.guardx.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class GuardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("help", "reload", "alerts", "debug", "info", "reset",
            "diagnostics", "checks", "inspect", "report", "reports", "close", "history", "dump", "goto", "top", "exempt", "stats", "layers", "notifytest", "netcheck", "netrevoke", "netlist");
    private static final List<String> REASONS = List.of("killaura", "fly", "speed", "reach", "autoclicker", "scaffold", "velocity", "other");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final GuardX plugin;

    public GuardCommand(GuardX plugin) { this.plugin = plugin; }

    private String msg(String key, Object... kv) {
        return plugin.lang().msg(key, kv);
    }

    private String tr(String key, Object... kv) {
        return plugin.lang().tr(key, kv);
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("guardx.admin")) return true;
        sender.sendMessage(msg("no-permission"));
        return false;
    }

    private Player findPlayer(CommandSender sender, String[] args) {
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) sender.sendMessage(msg("player-not-found"));
        return target;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        // /report <игрок> <причина> — короткий алиас для жалобы
        if (cmd.getName().equalsIgnoreCase("report")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = "report";
            System.arraycopy(args, 0, shifted, 1, args.length);
            args = shifted;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!admin(s)) return true;
                plugin.reloadAll();
                s.sendMessage(msg("reloaded"));
            }
            case "alerts" -> {
                if (!(s instanceof Player p)) { s.sendMessage(tr("players-only")); return true; }
                if (!p.hasPermission("guardx.alerts")) { s.sendMessage(msg("no-permission")); return true; }
                boolean on = plugin.violations().toggleAlerts(p.getUniqueId());
                s.sendMessage(msg(on ? "alerts-on" : "alerts-off"));
            }
            case "debug" -> {
                if (!admin(s)) return true;
                if (!(s instanceof Player p)) { s.sendMessage(tr("players-only")); return true; }
                s.sendMessage(msg(plugin.violations().toggleDebug(p.getUniqueId()) ? "debug-on" : "debug-off"));
            }
            case "info" -> {
                if (!admin(s)) return true;
                Player t = findPlayer(s, args); if (t == null) return true;
                Map<String, Double> vl = plugin.violations().get(t.getUniqueId());
                s.sendMessage(tr("info-title", "player", t.getName()));
                boolean any = false;
                for (Map.Entry<String, Double> en : vl.entrySet()) {
                    if (en.getValue() <= 0) continue;
                    any = true;
                    s.sendMessage(tr("vl-line", "check", en.getKey(), "vl", Lang.n(en.getValue(), 1)));
                }
                if (!any) s.sendMessage(tr("no-violations"));
            }
            case "inspect" -> {
                if (!admin(s)) return true;
                Player t = findPlayer(s, args); if (t == null) return true;
                Location loc = t.getLocation();
                s.sendMessage(tr("inspect-title", "player", t.getName()));
                s.sendMessage(tr("inspect-uuid", "value", t.getUniqueId()));
                s.sendMessage(tr("inspect-ping", "ping", Compat.ping(t), "mode", t.getGameMode()));
                s.sendMessage(tr("inspect-world", "value", t.getWorld().getName()));
                s.sendMessage(tr("inspect-pos", "x", Lang.n(loc.getX(), 2), "y", Lang.n(loc.getY(), 2), "z", Lang.n(loc.getZ(), 2)));
                s.sendMessage(tr("inspect-health", "hp", Lang.n(t.getHealth(), 1), "food", t.getFoodLevel()));
                Map<String, Double> vl = plugin.violations().get(t.getUniqueId());
                if (vl.isEmpty()) s.sendMessage(tr("inspect-vl-none"));
                else vl.entrySet().stream().filter(e -> e.getValue() > 0)
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .forEach(e -> s.sendMessage(tr("vl-line", "check", e.getKey(), "vl", Lang.n(e.getValue(), 1))));
                int open = plugin.reports().openFor(t.getName()).size();
                if (open > 0) s.sendMessage(tr("inspect-open-reports", "count", open)
                        + (plugin.reports().isSuspect(t.getUniqueId()) ? tr("inspect-suspect") : ""));
            }
            case "report" -> {
                // Админ без причины: старое поведение — сохранить подробный отчёт в файл
                if (args.length == 2 && s.hasPermission("guardx.admin")) {
                    dump(s, args);
                    return true;
                }
                if (!(s instanceof Player reporter)) { s.sendMessage(tr("players-only")); return true; }
                if (!reporter.hasPermission("guardx.report")) { s.sendMessage(msg("no-permission")); return true; }
                if (args.length < 3) {
                    s.sendMessage(msg("report-usage"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) { s.sendMessage(msg("player-not-found")); return true; }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
                switch (plugin.reports().submit(reporter, t, reason)) {
                    case OK -> s.sendMessage(msg("report-sent", "player", t.getName()));
                    case SELF -> s.sendMessage(msg("report-self"));
                    case COOLDOWN -> s.sendMessage(msg("report-cooldown"));
                    case DUPLICATE -> s.sendMessage(msg("report-duplicate"));
                    case NO_REASON -> s.sendMessage(msg("report-no-reason"));
                    case EXEMPT -> s.sendMessage(msg("report-exempt"));
                }
            }
            case "reports" -> {
                if (!admin(s)) return true;
                java.util.List<ReportManager.Report> open = plugin.reports().open();
                if (open.isEmpty()) { s.sendMessage(tr("reports-none")); return true; }
                int page = 1;
                if (args.length > 1) {
                    try { page = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) { }
                }
                int pages = (open.size() + 7) / 8;
                page = Math.min(page, pages);
                s.sendMessage(tr("reports-title", "count", open.size(), "page", page, "pages", pages));
                for (int i = (page - 1) * 8; i < Math.min(open.size(), page * 8); i++) {
                    ReportManager.Report r = open.get(i);
                    int count = plugin.reports().distinctReporters(r.target);
                    s.sendMessage(tr("reports-line", "id", r.id, "target", r.targetName, "from", tr("reports-from"),
                            "reporter", r.reporterName, "reason", r.reason, "vl", Lang.n(r.totalVl, 0),
                            "count", count > 1 ? tr("reports-count", "count", count) : ""));
                }
                s.sendMessage(tr("reports-footer"));
            }
            case "close" -> {
                if (!admin(s)) return true;
                if (args.length < 2) { s.sendMessage(tr("close-usage")); return true; }
                int closed = 0;
                java.util.List<ReportManager.Report> toNotify = new ArrayList<>();
                try {
                    ReportManager.Report r = plugin.reports().byId(Integer.parseInt(args[1]));
                    if (r != null && plugin.reports().close(r)) { closed = 1; toNotify.add(r); }
                } catch (NumberFormatException ex) {
                    toNotify.addAll(plugin.reports().openFor(args[1]));
                    closed = plugin.reports().closeAllFor(args[1]);
                }
                if (closed == 0) { s.sendMessage(tr("close-not-found")); return true; }
                s.sendMessage(tr("close-done", "count", closed));
                // Благодарим тех, кто пожаловался
                for (ReportManager.Report r : toNotify) {
                    if (r.auto()) continue;
                    Player who = Bukkit.getPlayer(r.reporter);
                    if (who != null) who.sendMessage(msg("report-closed", "player", r.targetName));
                }
            }
            case "history" -> {
                if (!admin(s)) return true;
                if (args.length < 2) { s.sendMessage(msg("player-not-found")); return true; }
                Player online = Bukkit.getPlayerExact(args[1]);
                UUID id = online != null ? online.getUniqueId() : plugin.history().find(args[1]);
                if (id == null) { s.sendMessage(msg("player-not-found")); return true; }
                String name = online != null ? online.getName() : args[1];
                var all = new ArrayList<>(plugin.history().entries(id, plugin.history().maxEntries()));
                java.util.Collections.reverse(all); // новые первыми
                int pages = Math.max(1, (all.size() + 9) / 10);
                int page = 1;
                if (args.length > 2) {
                    try { page = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { page = 1; }
                }
                page = Math.max(1, Math.min(page, pages));
                s.sendMessage(tr("history-title", "player", name));
                if (all.isEmpty()) s.sendMessage(tr("no-violations"));
                for (int i = (page - 1) * 10; i < Math.min(all.size(), page * 10); i++) {
                    var e = all.get(i);
                    s.sendMessage(tr("history-line", "time", HistoryStore.time(e), "check", e.check(),
                            "info", e.info(), "vl", Lang.n(e.vl(), 1)));
                }
                if (pages > 1) s.sendMessage(tr("history-footer", "page", page, "pages", pages, "player", name));
            }
            case "layers" -> {
                if (!admin(s)) return true;
                var all = plugin.layers().all();
                s.sendMessage(tr("layers-title"));
                if (all.isEmpty()) s.sendMessage(tr("layers-none"));
                String any = tr("layers-all");
                for (var l : all) {
                    String area = l.min() == null ? any : String.format(Locale.US, "%.0f,%.0f,%.0f → %.0f,%.0f,%.0f",
                            l.min()[0], l.min()[1], l.min()[2], l.max()[0], l.max()[1], l.max()[2]);
                    s.sendMessage(tr("layers-line", "name", l.name(),
                            "worlds", l.worlds().isEmpty() ? any : String.join(", ", l.worlds()),
                            "checks", l.allChecks() ? any : String.join(", ", l.checks()),
                            "area", area, "permission", l.permission() == null ? any : l.permission()));
                }
                if (args.length > 1) {
                    Player t = findPlayer(s, args); if (t == null) return true;
                    var active = plugin.layers().activeFor(t);
                    if (active.isEmpty()) s.sendMessage(tr("layers-active-none", "player", t.getName()));
                    else s.sendMessage(tr("layers-active", "player", t.getName(),
                            "layers", String.join(", ", active.stream().map(dev.guardx.LayerManager.Layer::name).toList())));
                }
            }
            case "notifytest" -> {
                if (!admin(s)) return true;
                List<String> sent = plugin.notifications().test();
                if (sent.isEmpty()) s.sendMessage(tr("notifytest-none"));
                else s.sendMessage(tr("notifytest-sent", "channels", String.join(", ", sent)));
            }
            case "netcheck" -> {
                if (!admin(s)) return true;
                if (args.length < 2) { s.sendMessage(msg("player-not-found")); return true; }
                if (!plugin.network().enabled()) { s.sendMessage(tr("network-disabled")); return true; }
                Player online = Bukkit.getPlayerExact(args[1]);
                String shown = online != null ? online.getName() : args[1];
                plugin.network().lookup(online != null ? online.getUniqueId() : null, shown, false, found -> {
                    if (found == null) { s.sendMessage(tr("network-error")); return; }
                    s.sendMessage(tr("network-title", "player", shown));
                    if (found.isEmpty()) s.sendMessage(tr("network-none"));
                    for (var r : found) {
                        s.sendMessage(tr("network-line", "time", NetworkStore.time(r.time()), "server", r.server(),
                                "check", r.check(), "info", r.info(), "vl", Lang.n(r.vl(), 1)));
                    }
                });
            }
            case "netrevoke" -> {
                if (!admin(s)) return true;
                if (args.length < 2) { s.sendMessage(msg("player-not-found")); return true; }
                if (!plugin.network().enabled()) { s.sendMessage(tr("network-disabled")); return true; }
                Player online = Bukkit.getPlayerExact(args[1]);
                String shown = online != null ? online.getName() : args[1];
                plugin.network().revoke(online != null ? online.getUniqueId() : null, shown, changed -> {
                    if (changed < 0) s.sendMessage(tr("network-error"));
                    else if (changed == 0) s.sendMessage(tr("network-revoke-none", "player", shown));
                    else s.sendMessage(tr("network-revoked", "player", shown, "count", changed));
                });
            }
            case "netlist" -> {
                if (!admin(s)) return true;
                if (!plugin.network().enabled()) { s.sendMessage(tr("network-disabled")); return true; }
                int requested = 1;
                if (args.length > 1) {
                    try { requested = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) { }
                }
                final int reqPage = requested;
                final int size = 8;
                plugin.network().listRecent((reqPage - 1) * size, size, result -> {
                    if (result == null) { s.sendMessage(tr("network-error")); return; }
                    int pages = Math.max(1, (result.total() + size - 1) / size);
                    int page = Math.min(reqPage, pages);
                    s.sendMessage(tr("netlist-title", "total", result.total(), "page", page, "pages", pages));
                    if (result.rows().isEmpty()) s.sendMessage(tr("network-none"));
                    for (var r : result.rows()) {
                        s.sendMessage(tr("network-line", "time", NetworkStore.time(r.time()), "server", r.server(),
                                "check", r.check(), "info", r.info(), "vl", Lang.n(r.vl(), 1)));
                    }
                    s.sendMessage(tr("netlist-footer", "page", page, "pages", pages));
                });
            }
            case "dump" -> {
                if (!admin(s)) return true;
                dump(s, args);
            }
            case "goto" -> {
                if (!admin(s)) return true;
                if (!(s instanceof Player admin)) { s.sendMessage(tr("players-only")); return true; }
                Player t = findPlayer(s, args); if (t == null) return true;
                admin.teleport(t.getLocation());
                s.sendMessage(tr("goto-done", "player", t.getName()));
            }
            case "top" -> {
                if (!admin(s)) return true;
                s.sendMessage(tr("top-title"));
                List<Map.Entry<Player, Double>> rows = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    double total = plugin.violations().get(player.getUniqueId()).values().stream()
                            .filter(Double::isFinite).mapToDouble(v -> Math.max(0, v)).sum();
                    if (total > 0) rows.add(Map.entry(player, total));
                }
                rows.sort(Map.Entry.<Player, Double>comparingByValue().reversed());
                if (rows.isEmpty()) s.sendMessage(tr("top-none"));
                for (int i = 0; i < Math.min(10, rows.size()); i++) {
                    var row = rows.get(i);
                    s.sendMessage(tr("top-line", "n", i + 1, "player", row.getKey().getName(), "vl", Lang.n(row.getValue(), 1)));
                }
            }
            case "diagnostics" -> {
                if (!admin(s)) return true;
                s.sendMessage(tr("diag-title"));
                s.sendMessage(tr("diag-players", "value", Bukkit.getOnlinePlayers().size()));
                s.sendMessage(tr("diag-packets", "value", plugin.packetLayer() == null ? "-" : plugin.packetLayer().name()));
                s.sendMessage(tr("diag-checks", "value", plugin.checks().all().size()));
                s.sendMessage(tr("diag-calls", "value", plugin.checks().calls()));
                s.sendMessage(tr("diag-avg", "value", String.format(Locale.US, "%.2f", plugin.checks().averageMicros())));
                s.sendMessage(tr("diag-max", "value", String.format(Locale.US, "%.2f", plugin.checks().maxMicros())));
                s.sendMessage(tr("diag-errors", "value", plugin.checks().brokenCount()));
                if (!plugin.checks().brokenChecks().isEmpty())
                    s.sendMessage(tr("diag-broken", "value", String.join(", ", plugin.checks().brokenChecks())));
            }
            case "checks" -> {
                if (!admin(s)) return true;
                s.sendMessage(tr("checks-title"));
                for (var check : plugin.checks().all()) {
                    boolean enabled = check.enabled();
                    s.sendMessage((enabled ? ChatColor.GREEN : ChatColor.RED) + check.id()
                            + tr(enabled ? "checks-enabled" : "checks-disabled"));
                }
            }
            case "exempt" -> {
                if (!admin(s)) return true;
                Player t = findPlayer(s, args); if (t == null) return true;
                if (args.length > 2 && args[2].equalsIgnoreCase("off")) {
                    plugin.setExempt(t, 0L);
                    s.sendMessage(tr("exempt-off", "player", t.getName()));
                    return true;
                }
                int minutes = 10;
                if (args.length > 2) {
                    try {
                        minutes = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        s.sendMessage(tr("exempt-usage"));
                        return true;
                    }
                }
                minutes = Math.max(1, Math.min(minutes, 1440));
                plugin.setExempt(t, minutes * 60_000L);
                s.sendMessage(tr("exempt-on", "player", t.getName(), "minutes", minutes));
            }
            case "stats" -> {
                if (!admin(s)) return true;
                s.sendMessage(tr("stats-title"));
                List<Map.Entry<String, Long>> rows = new ArrayList<>(plugin.violations().flagTotals().entrySet());
                rows.sort(Map.Entry.<String, Long>comparingByValue().reversed());
                if (rows.isEmpty()) s.sendMessage(tr("stats-none"));
                for (int i = 0; i < Math.min(10, rows.size()); i++) {
                    s.sendMessage(tr("stats-line", "check", rows.get(i).getKey(), "count", rows.get(i).getValue()));
                }
                s.sendMessage(tr("stats-punishments", "count", plugin.violations().punishTotal()));
            }
            case "reset" -> {
                if (!admin(s)) return true;
                Player t = findPlayer(s, args); if (t == null) return true;
                plugin.violations().reset(t.getUniqueId());
                s.sendMessage(tr("reset-done", "player", t.getName()));
            }
            default -> sendHelp(s);
        }
        return true;
    }

    private void dump(CommandSender s, String[] args) {
        Player t = findPlayer(s, args); if (t == null) return;
        try {
            Path dir = plugin.getDataFolder().toPath().resolve("reports");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(FILE_TIME);
            Path file = dir.resolve(t.getName() + "-" + stamp + ".txt");
            Files.writeString(file, buildReport(t), StandardCharsets.UTF_8);
            s.sendMessage(tr("dump-saved", "file", plugin.getDataFolder().toPath().relativize(file)));
        } catch (IOException ex) {
            plugin.getLogger().warning(plugin.lang().plain("warn-dump", "error", String.valueOf(ex.getMessage())));
            s.sendMessage(tr("dump-failed"));
        }
    }

    private String buildReport(Player p) {
        Location l = p.getLocation();
        Lang lg = plugin.lang();
        String none = lg.plain("dump-none");
        StringBuilder out = new StringBuilder();
        out.append(lg.plain("dump-header")).append('\n')
                .append(lg.plain("dump-generated", "value", LocalDateTime.now())).append('\n');
        out.append(lg.plain("dump-name", "value", p.getName())).append('\n')
                .append(lg.plain("dump-uuid", "value", p.getUniqueId())).append('\n');
        out.append(lg.plain("dump-ping", "value", Compat.ping(p))).append('\n')
                .append(lg.plain("dump-gamemode", "value", p.getGameMode())).append('\n');
        out.append(lg.plain("dump-world", "value", p.getWorld().getName())).append('\n');
        out.append(lg.plain("dump-position", "x", Lang.n(l.getX(), 3), "y", Lang.n(l.getY(), 3), "z", Lang.n(l.getZ(), 3))).append('\n');
        out.append(lg.plain("dump-health", "value", Lang.n(p.getHealth(), 1))).append('\n')
                .append(lg.plain("dump-food", "value", p.getFoodLevel())).append('\n');
        out.append('\n').append(lg.plain("dump-vl-title")).append('\n');
        Map<String, Double> vl = plugin.violations().get(p.getUniqueId());
        if (vl.isEmpty()) out.append(lg.plain("dump-no-vl")).append('\n');
        else vl.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> out.append(e.getKey()).append(": ").append(Lang.n(e.getValue(), 2)).append('\n'));
        out.append('\n').append(lg.plain("dump-flags-title")).append('\n');
        java.util.List<String> hist = plugin.violations().history(p.getUniqueId());
        if (hist.isEmpty()) out.append(none).append('\n');
        for (String line : hist) out.append(line).append('\n');
        out.append('\n').append(lg.plain("dump-reports-title")).append('\n');
        java.util.List<ReportManager.Report> rs = plugin.reports().openFor(p.getName());
        if (rs.isEmpty()) out.append(none).append('\n');
        for (ReportManager.Report r : rs) out.append(lg.plain("dump-report-line", "id", r.id,
                "reporter", r.reporterName, "reason", r.reason, "snapshot", r.snapshot)).append('\n');
        out.append('\n').append(lg.plain("dump-diag-title")).append('\n');
        out.append(lg.plain("dump-calls", "value", plugin.checks().calls())).append('\n');
        out.append(lg.plain("dump-avg", "value", Lang.n(plugin.checks().averageMicros(), 2))).append('\n');
        out.append(lg.plain("dump-max", "value", Lang.n(plugin.checks().maxMicros(), 2))).append('\n');
        out.append(lg.plain("dump-broken", "value", plugin.checks().brokenChecks())).append('\n');
        return out.toString();
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(tr("help-title", "version", plugin.getDescription().getVersion(), "author", plugin.authors()));
        s.sendMessage(tr("help-alerts"));
        s.sendMessage(tr("help-info"));
        s.sendMessage(tr("help-inspect"));
        s.sendMessage(tr("help-report"));
        if (s.hasPermission("guardx.admin")) {
            s.sendMessage(tr("help-reload"));
            s.sendMessage(tr("help-debug"));
            s.sendMessage(tr("help-reports"));
            s.sendMessage(tr("help-close"));
            s.sendMessage(tr("help-history"));
            s.sendMessage(tr("help-dump"));
            s.sendMessage(tr("help-goto"));
            s.sendMessage(tr("help-exempt"));
            s.sendMessage(tr("help-stats"));
            s.sendMessage(tr("help-layers"));
            s.sendMessage(tr("help-notifytest"));
            s.sendMessage(tr("help-netcheck"));
            s.sendMessage(tr("help-netrevoke"));
            s.sendMessage(tr("help-netlist"));
        }
        s.sendMessage(tr("help-top"));
        s.sendMessage(tr("help-reset"));
        s.sendMessage(tr("help-diagnostics"));
        s.sendMessage(tr("help-checks"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("report")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = "report";
            System.arraycopy(args, 0, shifted, 1, args.length);
            args = shifted;
        } else if (args.length == 1) {
            for (String sub : SUB) {
                if (!sub.startsWith(args[0].toLowerCase(Locale.ROOT))) continue;
                if (!sub.equals("report") && !sub.equals("help") && !sub.equals("alerts") && !s.hasPermission("guardx.admin")) continue;
                out.add(sub);
            }
            return out;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("info", "reset", "inspect", "report", "history", "dump", "goto", "close", "exempt", "layers", "netcheck", "netrevoke").contains(first)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))
                        && !(first.equals("report") && s instanceof Player me && me.getUniqueId().equals(p.getUniqueId())))
                    out.add(p.getName());
            }
        } else if (args.length == 3 && first.equals("exempt")) {
            for (String o : List.of("off", "10", "30", "60")) if (o.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(o);
        } else if (args.length == 3 && first.equals("report")) {
            for (String r : REASONS) if (r.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(r);
        }
        return out;
    }
}
