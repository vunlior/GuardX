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
import dev.guardx.check.MoveContext;
import dev.guardx.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Бег при голоде или слепоте. В ванили клиент сам перестаёт бежать, когда еды не больше 6 (3 «окорока»)
 * или на игрока действует слепота. Читы (Sprint / NoHunger) этого не соблюдают.
 * Буфер длинный: после смены еды клиенту нужно несколько тиков, чтобы остановить бег.
 */
public final class FoodSprintCheck extends Check {

    public FoodSprintCheck(GuardX plugin) {
        super(plugin, "foodsprint");
    }

    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged || c.exempt()) return;
        Player p = c.player;
        PlayerData d = c.data;

        boolean cannotSprint = p.getFoodLevel() <= 6 || p.hasPotionEffect(PotionEffectType.BLINDNESS);
        if (!cannotSprint || !p.isSprinting() || c.horizontal < 0.1) {
            d.addBuffer("foodsprint", -0.5);
            return;
        }

        double buf = d.addBuffer("foodsprint", 1.0);
        if (buf >= num("buffer", 12)) {
            d.setBuffer("foodsprint", 0);
            flag(p, d, t("f-foodsprint", "food", p.getFoodLevel()), 1.0);
        }
    }
}
