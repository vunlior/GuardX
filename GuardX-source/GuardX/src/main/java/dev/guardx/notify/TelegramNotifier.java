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

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Оповещения в Telegram через Bot API (sendMessage, parse_mode=HTML).
 * Тексты те же, что и для Discord: {@code **жирный**} и {@code `код`} из языковых файлов превращаются в HTML.
 */
public final class TelegramNotifier extends Notifier {

    private static final Pattern TOKEN = Pattern.compile("\\d{5,}:[A-Za-z0-9_-]{30,}");
    private static final Pattern RETRY = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL);
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    private final AtomicBoolean warned = new AtomicBoolean();
    private HttpClient cached;
    private String cachedProxy;

    public TelegramNotifier(GuardX plugin) {
        super(plugin, "telegram");
    }

    @Override
    public boolean enabled() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("telegram.enabled", false)) return false;
        String token = cfg.getString("telegram.bot-token", "");
        String chat = cfg.getString("telegram.chat-id", "");
        boolean ok = token != null && TOKEN.matcher(token.trim()).matches() && chat != null && !chat.isBlank();
        if (!ok && warned.compareAndSet(false, true)) {
            plugin.getLogger().warning(plugin.lang().plain("warn-telegram-config"));
        }
        return ok;
    }

    @Override
    protected String secret() {
        String token = plugin.getConfig().getString("telegram.bot-token", "");
        return token == null ? "" : token.trim();
    }

    @Override
    protected HttpClient client() {
        String proxy = plugin.getConfig().getString("telegram.proxy", "");
        proxy = proxy == null ? "" : proxy.trim();
        if (cached == null || !proxy.equals(cachedProxy)) {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
            int i = proxy.lastIndexOf(':');
            if (i > 0) {
                try {
                    b.proxy(ProxySelector.of(new InetSocketAddress(proxy.substring(0, i), Integer.parseInt(proxy.substring(i + 1)))));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning(plugin.lang().plain("warn-telegram-proxy", "value", proxy));
                }
            }
            cached = b.build();
            cachedProxy = proxy;
        }
        return cached;
    }

    @Override
    protected long retryAfterMs(HttpResponse<String> res) {
        Matcher m = RETRY.matcher(String.valueOf(res.body()));
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1)) * 1000L;
            } catch (NumberFormatException ignored) {
                // используем значение по умолчанию
            }
        }
        return super.retryAfterMs(res);
    }

    @Override
    protected void deliver(String title, int color, String body) {
        var cfg = plugin.getConfig();
        String token = secret();
        String chat = cfg.getString("telegram.chat-id", "").trim();
        String api = cfg.getString("telegram.api-url", "https://api.telegram.org").trim();
        if (api.endsWith("/")) api = api.substring(0, api.length() - 1);

        String icon = color == COLOR_YELLOW ? "🟡" : color == COLOR_ORANGE ? "🟠" : "🔴";
        String escaped = esc(cut(body, 3000));
        String marked = markup(escaped);
        // Текст с ** и ` из полей, введённых игроком, может дать неправильно вложенные теги (Telegram ответит 400).
        // Тогда отправляем простой текст без разметки.
        boolean html = validNesting(marked);
        String text = html
                ? "<b>" + icon + " " + esc(title) + "</b>\n" + marked + "\n\n<i>" + esc(footer()) + "</i>"
                : icon + " " + title + "\n" + cut(body, 3000).replace("**", "").replace("`", "") + "\n\n" + footer();

        StringBuilder json = new StringBuilder("{\"chat_id\":").append(q(chat))
                .append(",\"text\":").append(q(text));
        if (html) json.append(",\"parse_mode\":\"HTML\"");
        json.append(",\"disable_web_page_preview\":true");
        if (cfg.getBoolean("telegram.silent", false)) json.append(",\"disable_notification\":true");
        int thread = cfg.getInt("telegram.thread-id", 0);
        if (thread > 0) json.append(",\"message_thread_id\":").append(thread);
        json.append('}');

        post(api + "/bot" + token + "/sendMessage", json.toString());
    }

    /** Проверяет, что теги &lt;b&gt; и &lt;code&gt; закрываются в правильном порядке. */
    private static boolean validNesting(String s) {
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        Matcher m = Pattern.compile("</?(b|code)>").matcher(s);
        while (m.find()) {
            String tag = m.group(1);
            if (m.group().charAt(1) != '/') stack.push(tag);
            else if (stack.isEmpty() || !stack.pop().equals(tag)) return false;
        }
        return stack.isEmpty();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Markdown-подобная разметка языковых файлов в HTML Telegram (текст уже экранирован). */
    private static String markup(String s) {
        s = BOLD.matcher(s).replaceAll("<b>$1</b>");
        return CODE.matcher(s).replaceAll("<code>$1</code>");
    }
}
