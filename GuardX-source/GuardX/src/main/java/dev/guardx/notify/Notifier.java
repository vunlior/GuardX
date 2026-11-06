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

package dev.guardx.notify;

import dev.guardx.GuardX;
import dev.guardx.NetworkStore;
import dev.guardx.ReportManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Общая часть внешних оповещений (Discord, Telegram): какие события отправлять, тексты из языковых файлов,
 * отдельный поток отправки с ограниченной очередью, повтор при лимите (HTTP 429).
 * Наследник описывает только свой формат: {@link #enabled()} и {@link #deliver}.
 * Настройки лежат в секции конфига с именем {@link #name()} (notify.punish / notify.reports / notify.threshold).
 */
public abstract class Notifier {

    protected static final int COLOR_RED = 0xE74C3C;
    protected static final int COLOR_ORANGE = 0xE67E22;
    protected static final int COLOR_YELLOW = 0xF1C40F;

    protected final GuardX plugin;
    private final String name;
    private final HttpClient defaultClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ThreadPoolExecutor sender;

    protected Notifier(GuardX plugin, String name) {
        this.plugin = plugin;
        this.name = name;
        this.sender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(50), r -> {
            Thread t = new Thread(r, "GuardX-" + name);
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());
    }

    /** Имя секции в config.yml и название канала в логах (discord, telegram). */
    public final String name() {
        return name;
    }

    /** Включено в конфиге и настроено правильно. */
    public abstract boolean enabled();

    /** Выполняется в потоке отправки: собрать запрос в нужном формате и вызвать {@link #post}. */
    protected abstract void deliver(String title, int color, String body);

    /** HTTP-клиент; наследник может подменить (например, прокси). Вызывается только из потока отправки. */
    protected HttpClient client() {
        return defaultClient;
    }

    /** Секрет (токен), который нельзя показывать в логах. */
    protected String secret() {
        return "";
    }

    /** Через сколько мс повторить запрос после HTTP 429. */
    protected long retryAfterMs(HttpResponse<String> res) {
        return res.headers().firstValue("Retry-After").map(v -> {
            try {
                return (long) (Double.parseDouble(v) * 1000);
            } catch (NumberFormatException e) {
                return 2000L;
            }
        }).orElse(2000L);
    }

    private boolean on(String what) {
        return plugin.getConfig().getBoolean(name + ".notify." + what, true) && enabled();
    }

    // ------------------------------------------------------------ события

    public final void punish(String player, String check, double vl, int count, String command) {
        if (!on("punish")) return;
        var l = plugin.lang();
        send(l.plain("notify-punish-title", "player", player), COLOR_RED,
                l.plain("notify-punish-body", "check", check, "vl", fmt(vl), "count", count, "cmd", command));
    }

    public final void report(ReportManager.Report r) {
        if (!on("reports")) return;
        var l = plugin.lang();
        String snapshot = r.snapshot.isEmpty() ? "—" : r.snapshot;
        if (r.auto()) {
            send(l.plain("notify-auto-title", "target", r.targetName), COLOR_YELLOW,
                    l.plain("notify-auto-body", "reason", r.reason, "vl", fmt(r.totalVl), "snapshot", snapshot));
        } else {
            send(l.plain("notify-report-title", "id", r.id), COLOR_ORANGE,
                    l.plain("notify-report-body", "target", r.targetName, "reporter", r.reporterName,
                            "reason", r.reason, "vl", fmt(r.totalVl), "snapshot", snapshot));
        }
    }

    public final void threshold(String player, int count) {
        if (!on("threshold")) return;
        var l = plugin.lang();
        send(l.plain("notify-threshold-title", "player", player), COLOR_RED,
                l.plain("notify-threshold-body", "count", count));
    }

    public final void network(String player, String server, String check, double vl, int count, String ago, String info) {
        if (!on("network")) return;
        var l = plugin.lang();
        if (NetworkStore.MANUAL.equals(check)) {
            send(l.plain("notify-network-title", "player", player), COLOR_ORANGE,
                    l.plain("notify-network-manual-body", "server", server, "info", cut(info, 300), "ago", ago, "count", count));
            return;
        }
        send(l.plain("notify-network-title", "player", player), COLOR_ORANGE,
                l.plain("notify-network-body", "server", server, "check", check, "vl", fmt(vl), "ago", ago, "count", count));
    }

    /** Тестовое сообщение (/guardx notifytest). @return false, если канал выключен или настроен неверно */
    public final boolean test() {
        if (!enabled()) return false;
        var l = plugin.lang();
        send(l.plain("notify-test-title"), COLOR_ORANGE, l.plain("notify-test-body", "name", name));
        return true;
    }

    // ------------------------------------------------------------ отправка

    private void send(String title, int color, String body) {
        try {
            sender.execute(() -> {
                try {
                    deliver(title, color, body);
                } catch (RuntimeException ex) {
                    fail(String.valueOf(ex.getMessage()));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // плагин выключается
        }
    }

    /** POST с JSON-телом. Лимит 429 — ждём и пробуем ещё раз; между сообщениями пауза. */
    protected final void post(String url, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpClient http = client();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 429) {
                Thread.sleep(Math.min(retryAfterMs(res), 5000L));
                res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            if (res.statusCode() >= 400) {
                fail("HTTP " + res.statusCode() + " " + cut(String.valueOf(res.body()), 200));
            }
            Thread.sleep(400);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            fail(String.valueOf(ex.getMessage()));
        }
    }

    private void fail(String message) {
        String secret = secret();
        if (!secret.isEmpty()) message = message.replace(secret, "***");
        plugin.getLogger().warning(plugin.lang().plain("warn-notify-send", "name", name, "error", message));
    }

    public final void shutdown() {
        sender.shutdownNow();
    }

    // ------------------------------------------------------------ общие помощники

    protected final String footer() {
        return "GuardX v" + plugin.getDescription().getVersion() + " · " + plugin.authors();
    }

    protected static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    protected static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Строка в JSON с экранированием. */
    protected static String q(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
