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
import dev.guardx.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * Strafe / AirControl: в воздухе направление движения почти не управляется.
 *
 * В ванили сдвиг за тик в воздухе = (прошлый сдвиг * 0.91) + ускорение от клавиш, а оно не больше 0.026.
 * То есть ВЕКТОР сдвига может отличаться от "прошлый * 0.91" не больше чем на 0.026. Это строже, чем Momentum:
 * та проверяет только длину вектора, а эта ловит и резкие повороты в воздухе (air strafe, "воздушное такси").
 *
 * Проверяется только когда игрок в воздухе уже три тика подряд, движется заметно быстро (иначе события
 * движения могут склеиваться) и рядом нет стен (удар о стену обнуляет составляющую скорости).
 */
public final class StrafeCheck extends Check {

    private static final double AIR_FRICTION = 0.91;
    private static final double AIR_ACCEL = 0.02 * 1.3; // спринт: x1.3

    public StrafeCheck(GuardX plugin) {
        super(plugin, "strafe");
    }

    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged) return;
        PlayerData d = c.data;

        if (c.exempt() || c.nearSpecial() || c.now - d.lastIce < 2000) {
            d.strValid = false;
            return;
        }

        boolean valid = d.strValid && c.now - d.strPrevTime <= 150;
        double pdx = d.strPrevDx;
        double pdz = d.strPrevDz;
        boolean air1 = d.strAir1;
        boolean air2 = d.strAir2;

        d.strValid = true;
        d.strPrevDx = c.dx;
        d.strPrevDz = c.dz;
        d.strPrevTime = c.now;
        d.strAir2 = air1;
        d.strAir1 = !c.serverGround;

        if (!valid || c.serverGround || !air1 || !air2) return;

        double prevH = Math.hypot(pdx, pdz);
        double minH = num("min-speed", 0.08);
        if (prevH < minH || c.horizontal < minH) return;

        // Рядом стена: после удара о неё скорость по этой оси обнуляется, и модель перестаёт работать
        Location t = c.to;
        World w = t.getWorld();
        if (w == null) return;
        BoundingBox around = new BoundingBox(t.getX() - 0.5, t.getY() + 0.1, t.getZ() - 0.5,
                t.getX() + 0.5, t.getY() + 1.7, t.getZ() + 0.5);
        if (MoveUtil.collides(w, around)) return;

        double rx = c.dx - AIR_FRICTION * pdx;
        double rz = c.dz - AIR_FRICTION * pdz;
        double residual = Math.hypot(rx, rz);
        double limit = AIR_ACCEL + num("tolerance", 0.02);

        if (residual <= limit) {
            d.addBuffer("strafe", -0.5);
            return;
        }

        double buf = d.addBuffer("strafe", 1.0 + (residual - limit) * 10.0);
        if (buf >= num("buffer", 4)) {
            d.setBuffer("strafe", 0);
            flagMove(c, t("f-strafe", "res", Lang.n(residual, 3), "max", Lang.n(limit, 3)),
                    1.0 + (residual - limit) * 5.0);
        }
    }
}
