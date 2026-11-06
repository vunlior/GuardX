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
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Locale;
import java.util.Map;

/**
 * Плейсхолдеры PlaceholderAPI:
 * %guardx_vl% — суммарный VL игрока, %guardx_vl_&lt;проверка&gt;% — VL по одной проверке,
 * %guardx_suspect% — true/false (под усиленным контролем после жалобы),
 * %guardx_reports_open% — число открытых жалоб, %guardx_version% — версия плагина.
 */
public final class GuardPlaceholders extends PlaceholderExpansion {

    private final GuardX plugin;

    public GuardPlaceholders(GuardX plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "guardx";
    }

    @Override
    public String getAuthor() {
        return plugin.authors();
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // не выгружать при /papi reload
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("version")) return plugin.getDescription().getVersion();
        if (p.equals("reports_open")) return String.valueOf(plugin.reports().open().size());
        if (player == null) return "";

        Map<String, Double> vl = plugin.violations().get(player.getUniqueId());
        if (p.equals("vl") || p.equals("vl_total")) {
            double sum = 0;
            for (double v : vl.values()) if (Double.isFinite(v) && v > 0) sum += v;
            return String.format(Locale.US, "%.1f", sum);
        }
        if (p.startsWith("vl_")) {
            Double v = vl.get(p.substring(3));
            return String.format(Locale.US, "%.1f", v == null ? 0.0 : v);
        }
        if (p.equals("suspect")) return String.valueOf(plugin.reports().isSuspect(player.getUniqueId()));
        return null;
    }
}
