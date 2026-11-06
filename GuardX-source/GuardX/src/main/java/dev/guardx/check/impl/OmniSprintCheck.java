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
import dev.guardx.util.Compat;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * OmniSprint: бег не вперёд. Спринт в ванили возможен только при нажатой клавише "вперёд", поэтому
 * направление движения на земле не может сильно отличаться от направления взгляда. Инерция после резкого
 * поворота мыши гаснет за 2-3 тика, а чит держит такое движение сколько угодно.
 */
public final class OmniSprintCheck extends Check {

    public OmniSprintCheck(GuardX plugin) {
        super(plugin, "sprint");
    }

    @Override
    public void onMove(MoveContext c) {
        PlayerData d = c.data;
        Player p = c.player;

        if (!c.positionChanged || !p.isSprinting() || !c.serverGround
                || c.exempt() || c.nearSpecial() || c.now - d.lastIce < 2000) {
            d.sprintBackTicks = 0;
            return;
        }

        // Быстрее, чем можно идти спиной/боком без спринта (0.216 б/тик; с учётом зелья скорости)
        double mult = 1.0;
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) mult *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        mult *= Math.max(Math.max(1.0, p.getWalkSpeed() / 0.2), Compat.moveSpeedMult(p));
        if (c.horizontal < num("min-speed", 0.22) * mult) {
            d.sprintBackTicks = 0;
            return;
        }

        // Направление движения в тех же градусах, что и yaw: dx = -sin(yaw) * h, dz = cos(yaw) * h
        double moveYaw = Math.toDegrees(Math.atan2(-c.dx, c.dz));
        double diff = Math.abs(wrap(moveYaw - c.to.getYaw()));
        if (diff <= num("max-angle", 100)) {
            d.sprintBackTicks = 0;
            return;
        }

        if (++d.sprintBackTicks >= integer("ticks", 6)) {
            d.sprintBackTicks = 0;
            double buf = d.addBuffer("sprint", 1.0);
            if (buf >= num("buffer", 2)) {
                d.setBuffer("sprint", 0);
                flagMove(c, t("f-omnisprint", "angle", Lang.n(diff, 0)), 1.0);
            }
        }
    }

    private static double wrap(double a) {
        a %= 360.0;
        if (a > 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }
}
