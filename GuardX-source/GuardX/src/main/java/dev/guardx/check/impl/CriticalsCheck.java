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
import dev.guardx.data.PlayerData;
import dev.guardx.util.MoveUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * Criticals: критический удар требует падения (fallDistance > 0), а чит «Criticals» подделывает
 * микро-прыжки в пакетах. Если клиент говорит «я в воздухе», упал на ничтожные доли блока,
 * но сервер видит землю прямо под ногами — это не настоящий прыжок.
 * Настоящий падающий игрок на такой высоте уже имеет fallDistance около 1.2 (вершина прыжка).
 */
public final class CriticalsCheck extends Check {

    public CriticalsCheck(GuardX plugin) {
        super(plugin, "criticals");
    }

    @Override
    public boolean onAttack(Player p, PlayerData d, Entity target, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;
        if (p.isInsideVehicle() || p.isGliding() || p.isOnGround()) return false;
        if (now - d.lastTeleport < 1500 || now - d.joinTime < 3000 || now - d.lastVelocity < 1000) return false;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return false;

        float fall = p.getFallDistance();
        if (!(fall > 0.0f && fall <= (float) num("max-fall", 0.5))) {
            d.addBuffer("criticals", -0.25);
            return false;
        }

        Location l = p.getLocation();
        if (l.getWorld() == null) return false;
        if (MoveUtil.nearSpecial(p, l.getX(), l.getY(), l.getZ(), false)) return false;
        boolean ground = MoveUtil.onGround(l.getWorld(), l.getX(), l.getY(), l.getZ(), plugin.groundHeight());
        if (!ground) {
            d.addBuffer("criticals", -0.25);
            return false;
        }

        double buf = d.addBuffer("criticals", 1.0);
        if (buf >= num("buffer", 3)) {
            d.setBuffer("criticals", 0);
            flag(p, d, t("f-criticals", "fall", Lang.n(fall, 2)), 1.0);
        }
        return false;
    }
}
