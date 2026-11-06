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

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Языковые файлы: plugins/GuardX/lang/ru.yml и en.yml (создаются при первом запуске, можно править).
 * Порядок поиска ключа: файл на диске -> встроенный файл выбранного языка -> встроенный английский -> сам ключ.
 */
public final class Lang {

    private final GuardX plugin;
    private YamlConfiguration disk;
    private YamlConfiguration bundled;
    private YamlConfiguration english;
    private String code = "ru";

    public Lang(GuardX plugin) {
        this.plugin = plugin;
    }

    public void load(String language) {
        String c = language == null ? "ru" : language.trim().toLowerCase(Locale.ROOT);
        code = c.matches("[a-z]{2,8}") ? c : "ru";

        english = bundled("en");
        bundled = bundled(code);
        if (bundled == null) bundled = english;

        for (String name : new String[]{"ru", "en"}) {
            File f = new File(plugin.getDataFolder(), "lang/" + name + ".yml");
            if (!f.exists()) {
                try {
                    plugin.saveResource("lang/" + name + ".yml", false);
                } catch (IllegalArgumentException ignored) {
                    // файла нет в jar — не страшно
                }
            }
        }

        disk = null;
        File f = new File(plugin.getDataFolder(), "lang/" + code + ".yml");
        if (f.isFile()) {
            try (InputStreamReader r = new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8)) {
                disk = YamlConfiguration.loadConfiguration(r);
            } catch (Exception ex) {
                plugin.getLogger().warning("lang/" + code + ".yml: " + ex.getMessage());
            }
        }
    }

    private YamlConfiguration bundled(String name) {
        try (InputStream in = plugin.getResource("lang/" + name + ".yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }

    /** Число с фиксированным числом знаков, всегда с точкой (не зависит от локали сервера). */
    public static String n(double v, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    public String code() {
        return code;
    }

    private String raw(String key) {
        String s = disk == null ? null : disk.getString(key);
        if (s == null && bundled != null) s = bundled.getString(key);
        if (s == null && english != null) s = english.getString(key);
        return s == null ? key : s;
    }

    /** Текст с цветами. Пары kv: "имя", значение, ... Значения подставляются ПОСЛЕ перевода цветов (чужой текст не красится). */
    public String tr(String key, Object... kv) {
        String s = ChatColor.translateAlternateColorCodes('&', raw(key));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    /** То же, но с префиксом [GuardX] в начале. */
    public String msg(String key, Object... kv) {
        return tr("prefix") + tr(key, kv);
    }

    /** Без цветов (для логов и Discord). */
    public String plain(String key, Object... kv) {
        return ChatColor.stripColor(tr(key, kv));
    }
}
