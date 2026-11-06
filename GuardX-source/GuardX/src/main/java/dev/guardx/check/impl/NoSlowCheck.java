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
 * NoSlow: игрок ест, пьёт, держит щит или натягивает лук, но бежит с обычной скоростью.
 * В ванили использование предмета замедляет движение в 5 раз (~0.04 б/тик), лимит с запасом.
 * Отключи проверку, если на сервере есть предметы с кастомным замедлением (data components).
 */
public final class NoSlowCheck extends Check {

    public NoSlowCheck(GuardX plugin) {
        super(plugin, "noslow");
    }

    @Override
    public void onMove(MoveContext c) {
        PlayerData d = c.data;
        Player p = c.player;

        if (!c.positionChanged || c.exempt() || c.nearSpecial() || !c.serverGround
                || !p.isHandRaised() || c.now - d.lastIce < 2000) {
            d.noSlowTicks = 0;
            return;
        }
        // Первые тики после начала использования — инерция прежнего бега, не считаем
        if (++d.noSlowTicks < 3) return;

        double limit = num("max-speed", 0.15);
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) limit *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        limit *= Math.max(Math.max(1.0, p.getWalkSpeed() / 0.2), Compat.moveSpeedMult(p));

        if (c.horizontal <= limit) {
            d.addBuffer("noslow", -0.5);
            return;
        }

        double buf = d.addBuffer("noslow", 1.0);
        if (buf >= num("buffer", 8)) {
            d.setBuffer("noslow", 0);
            flagMove(c, t("f-noslow", "speed", Lang.n(c.horizontal, 3), "max", Lang.n(limit, 3)), 1.0);
        }
    }
}
