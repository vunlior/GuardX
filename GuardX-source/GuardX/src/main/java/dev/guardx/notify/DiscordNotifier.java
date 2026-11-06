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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** Оповещения в Discord через вебхук (embed с цветом, подписью и временем). */
public final class DiscordNotifier extends Notifier {

    private final AtomicBoolean warnedUrl = new AtomicBoolean();

    public DiscordNotifier(GuardX plugin) {
        super(plugin, "discord");
    }

    @Override
    public boolean enabled() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("discord.enabled", false)) return false;
        String url = cfg.getString("discord.webhook-url", "");
        boolean ok = url != null && url.startsWith("https://") && url.contains("/api/webhooks/");
        if (!ok && warnedUrl.compareAndSet(false, true)) {
            plugin.getLogger().warning(plugin.lang().plain("warn-discord-url"));
        }
        return ok;
    }

    @Override
    protected String secret() {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        return url == null ? "" : url;
    }

    @Override
    protected void deliver(String title, int color, String body) {
        String url = plugin.getConfig().getString("discord.webhook-url", "");
        String name = plugin.getConfig().getString("discord.username", "GuardX");
        String json = "{\"username\":" + q(name)
                + ",\"allowed_mentions\":{\"parse\":[]}"
                + ",\"embeds\":[{\"title\":" + q(cut(title, 250))
                + ",\"description\":" + q(cut(body, 3500))
                + ",\"color\":" + color
                + ",\"footer\":{\"text\":" + q(footer()) + "}"
                + ",\"timestamp\":" + q(Instant.now().toString()) + "}]}";
        post(url, json);
    }
}
