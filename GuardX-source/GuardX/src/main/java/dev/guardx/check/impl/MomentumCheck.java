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
import dev.guardx.util.Compat;
import dev.guardx.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * Momentum — "физический конверт" горизонтального движения.
 *
 * В ванили сдвиг за тик складывается из двух частей: инерции прошлого тика (сдвиг, умноженный на трение)
 * и ускорения от нажатых клавиш, которое ограничено сверху. Поэтому
 *
 *     |сдвиг| <= |прошлый сдвиг| * трение + макс. ускорение (+0.2 в тик прыжка при спринте)
 *
 * Трение зависит от того, стоял ли игрок на земле в начале прошлого тика (0.546 на земле, 0.91 в воздухе),
 * ускорение — от того, стоит ли он на земле сейчас (скорость атрибута на земле, 0.026 в воздухе).
 * Любой чит, дающий скорость сверх этой физики (speed, bhop, longjump, "рывки"), выходит за конверт,
 * даже если держится ниже простых лимитов проверки Speed.
 *
 * Лёд и прочие "особые" поверхности пропускаются: там другое трение.
 */
public final class MomentumCheck extends Check {

    private static final double GROUND_FRICTION = 0.6 * 0.91;
    private static final double AIR_FRICTION = 0.91;

    public MomentumCheck(GuardX plugin) {
        super(plugin, "momentum");
    }

    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged) return;
        PlayerData d = c.data;
        Player p = c.player;

        if (c.exempt() || c.nearSpecial() || c.now - d.lastIce < 2000) {
            d.momValid = false;
            return;
        }

        // Состояние прошлого тика. Если событий не было дольше 150 мс — данные устарели, пропускаем.
        boolean valid = d.momValid && c.now - d.momPrevTime <= 150;
        double prev = d.momPrevHor;
        boolean g1 = d.momG1;
        boolean g2 = d.momG2;

        // Сохраняем состояние для следующего тика
        d.momValid = true;
        d.momPrevHor = c.horizontal;
        d.momPrevTime = c.now;
        d.momG2 = g1;
        d.momG1 = c.serverGround;

        if (!valid) return;

        // Множитель скорости: зелье и скорость ходьбы (сам спринт, х1.3, закладываем всегда)
        double mult = 1.0;
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) mult *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        mult *= Math.max(Math.max(1.0, p.getWalkSpeed() / 0.2), Compat.moveSpeedMult(p));

        double accel = g1 ? 0.1 * mult * 1.3 : 0.02 * 1.3;
        double friction = g2 ? GROUND_FRICTION : AIR_FRICTION;
        double jumpBoost = (g1 && c.dy > 0.3) ? 0.2 : 0.0; // спринт-прыжок добавляет 0.2 вперёд

        double bound = prev * friction + accel + jumpBoost + num("tolerance", 0.03);

        if (c.horizontal <= bound) {
            d.addBuffer("momentum", -0.5);
            return;
        }

        double excess = c.horizontal - bound;
        double buf = d.addBuffer("momentum", 1.0 + excess * 10.0);
        if (buf >= num("buffer", 5)) {
            d.setBuffer("momentum", 0);
            flagMove(c, t("f-momentum", "speed", Lang.n(c.horizontal, 3), "max", Lang.n(bound, 3),
                    "state", t(c.serverGround ? "f-ground" : "f-air")), 1.0 + excess * 5.0);
        }
    }
}
