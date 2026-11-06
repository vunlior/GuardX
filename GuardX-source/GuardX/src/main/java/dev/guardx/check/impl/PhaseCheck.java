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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

import java.util.Locale;

/**
 * Phase / NoClip: голова игрока оказалась внутри сплошного блока, хотя тиком раньше там был воздух.
 * Нормальная коллизия не пускает центр глаз в полный блок, поэтому такое движение возможно только с читом.
 */
public final class PhaseCheck extends Check {

    public PhaseCheck(GuardX plugin) {
        super(plugin, "phase");
    }

    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged || c.exempt()) return;
        if (c.horizontal < 0.05 && Math.abs(c.dy) < 0.05) return;

        Player p = c.player;
        PlayerData d = c.data;
        if (p.isSwimming() || p.isGliding() || p.isInsideVehicle()) return;
        Pose pose = p.getPose();
        if (pose != Pose.STANDING && pose != Pose.SNEAKING) return; // ползание: глаза ниже

        World w = c.to.getWorld();
        if (w == null) return;
        double eye = p.getEyeHeight();
        Block head = MoveUtil.loadedBlock(w, floor(c.to.getX()), floor(c.to.getY() + eye), floor(c.to.getZ()));
        if (head == null || !head.getType().isOccluding()) {
            d.addBuffer("phase", -0.5);
            return;
        }
        // Уже был внутри блока (упавший песок, поршень) — это не наш случай
        Block was = MoveUtil.loadedBlock(w, floor(c.from.getX()), floor(c.from.getY() + eye), floor(c.from.getZ()));
        if (was == null || was.getType().isOccluding()) return;

        double buf = d.addBuffer("phase", 1.0);
        if (buf >= num("buffer", 2)) {
            d.setBuffer("phase", 0);
            flagMove(c, t("f-phase", "block", head.getType().name().toLowerCase(Locale.ROOT),
                    "x", Lang.n(c.to.getX(), 0), "y", Lang.n(c.to.getY(), 0), "z", Lang.n(c.to.getZ(), 0)), 2.0);
        }
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
