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

import dev.guardx.Lang;
import dev.guardx.GuardX;
import dev.guardx.check.Check;
import dev.guardx.check.MoveContext;
import dev.guardx.data.PlayerData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/** Полёт, зависание в воздухе, слишком высокие прыжки. */
public final class FlyCheck extends Check {

    public FlyCheck(GuardX plugin) {
        super(plugin, "fly");
    }

    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged || c.exempt() || c.nearSpecial()) return;
        if (c.serverGround) {
            c.data.addBuffer("fly", -0.5);
            return;
        }

        PlayerData d = c.data;
        double bufMax = num("buffer", 4);

        // 1) Слишком высоко над последней точкой опоры
        double maxRise = num("max-rise", 1.35);
        PotionEffect jb = dev.guardx.util.Compat.effect(c.player, dev.guardx.util.Compat.JUMP_BOOST);
        if (jb != null) maxRise += (jb.getAmplifier() + 1) * num("jump-boost-per-level", 0.6);

        double rise = c.to.getY() - d.groundY;
        if (rise > maxRise) {
            flagMove(c, t("f-fly-height", "rise", Lang.n(rise, 2), "max", Lang.n(maxRise, 2)), 1.0);
            return;
        }

        // 2) Вертикальная физика: в воздухе скорость падения подчиняется (vy - 0.08) * 0.98.
        //    Если игрок движется вверх/держится на месте заметно "лучше" предсказания — он не падает по законам игры.
        if (d.airTicks >= 5) {
            double expected = (d.lastDeltaY - 0.08) * 0.98;
            double diff = c.dy - expected;
            if (diff > num("predict-tolerance", 0.04)) {
                double buf = d.addBuffer("fly", 1.0);
                if (buf >= bufMax) {
                    d.setBuffer("fly", 0);
                    flagMove(c, t("f-fly-physics", "dy", Lang.n(c.dy, 3), "expected", Lang.n(expected, 3)), 1.0);
                }
            } else {
                d.addBuffer("fly", -0.25);
            }
        }
    }
}
