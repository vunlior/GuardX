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

package dev.guardx.check.impl;

import dev.guardx.GuardX;
import dev.guardx.check.Check;
import dev.guardx.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import java.util.Locale;

/**
 * FastEat / FastUse: еда и зелья съедаются быстрее, чем позволяет игра (минимум 16 тиков у сушёных водорослей).
 * Время считаем по серверным тикам, пока игрок держит предмет поднятым.
 */
public final class FastUseCheck extends Check {

    public FastUseCheck(GuardX plugin) {
        super(plugin, "fastuse");
    }

    @Override
    public void onTick(Player p, PlayerData d, long now) {
        if (p.isHandRaised()) {
            if (d.useSince == 0) d.useSince = now;
        } else {
            d.useSince = 0;
        }
    }

    @Override
    public boolean onConsume(Player p, PlayerData d, PlayerItemConsumeEvent e, long now) {
        GameMode gm = p.getGameMode();
        long since = d.useSince;
        d.useSince = 0;
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;
        if (since == 0 || now - d.joinTime < 3000) return false; // не знаем, когда началось — не наказываем

        long elapsed = now - since;
        long min = integer("min-ms", 500);
        if (elapsed >= min) {
            d.addBuffer("fastuse", -0.5);
            return false;
        }
        double buf = d.addBuffer("fastuse", 1.0);
        if (buf >= num("buffer", 2)) {
            d.setBuffer("fastuse", 0);
            flag(p, d, t("f-fastuse", "elapsed", elapsed, "min", min), 1.0);
        }
        return bool("cancel-invalid", true);
    }
}
