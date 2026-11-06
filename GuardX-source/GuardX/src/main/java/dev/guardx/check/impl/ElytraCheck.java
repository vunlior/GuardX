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
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Скорость на элитрах. Остальные проверки движения полностью пропускают игроков в полёте на элитрах,
 * поэтому «ElytraFly / ElytraBoost» без этой проверки не ловились бы вообще.
 * Берётся полная (3D) скорость за тик с большим запасом: фейерверки и пикирование дают очень высокие значения.
 * Откат не используется: у игрока в воздухе нет безопасной точки, куда его можно вернуть.
 */
public final class ElytraCheck extends Check {

    public ElytraCheck(GuardX plugin) {
        super(plugin, "elytra");
    }

    @Override
    public void onMove(MoveContext c) {
        Player p = c.player;
        PlayerData d = c.data;
        if (!c.positionChanged || !p.isGliding()) {
            d.addBuffer("elytra", -0.5);
            return;
        }

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) return;
        if (p.isInsideVehicle() || p.isDead()) return;
        // Телепорт, отбрасывание от плагина, только что зашёл — скорость может быть любой
        if (c.now - d.lastTeleport < 1500 || c.now - d.lastVelocity < 1500 || c.now - d.joinTime < 3000) return;

        double speed = Math.sqrt(c.horizontal * c.horizontal + c.dy * c.dy);
        double max = num("max-speed", 4.5);
        if (speed <= max) {
            d.addBuffer("elytra", -0.5);
            return;
        }

        double buf = d.addBuffer("elytra", 1.0);
        if (buf >= num("buffer", 3)) {
            d.setBuffer("elytra", 0);
            flag(p, d, t("f-elytra", "speed", Lang.n(speed, 2), "max", Lang.n(max, 2)), 1.0);
        }
    }
}
