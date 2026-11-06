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

package dev.guardx.hook;

import dev.guardx.GuardX;
import org.bukkit.Bukkit;

/** Необязательные интеграции. Каждая в try/catch: без нужного плагина GuardX работает как обычно. */
public final class Hooks {

    private Hooks() {}

    /** @return true, если плейсхолдеры зарегистрированы */
    public static boolean registerPlaceholders(GuardX plugin) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return false;
        try {
            return new GuardPlaceholders(plugin).register();
        } catch (Throwable ex) {
            plugin.getLogger().warning("PlaceholderAPI: " + ex);
            return false;
        }
    }
}
