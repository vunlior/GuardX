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
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Locale;

/**
 * Spider / FastLadder: слишком быстрое лазание. В ванили по лестнице и лозам игрок поднимается
 * ровно на ~0.1176 блока за тик (0.2 после удара о стену, затем гравитация и сопротивление).
 * Проверка усредняет подъём за 10 тиков на лазаемых блоках (первые 4 тика пропускаются: там
 * ещё идёт инерция прыжка). Остальные проверки движения на лазаемых блоках отключены, поэтому
 * без этой чита вроде Spider просто не видно.
 */
public final class SpiderCheck extends Check {

    public SpiderCheck(GuardX plugin) {
        super(plugin, "spider");
    }

    @Override
    public void onMove(MoveContext c) {
        PlayerData d = c.data;

        if (!c.positionChanged || c.exempt() || c.serverGround) {
            reset(d);
            return;
        }

        World w = c.to.getWorld();
        Block feet = MoveUtil.loadedBlock(w, (int) Math.floor(c.to.getX()), (int) Math.floor(c.to.getY()),
                (int) Math.floor(c.to.getZ()));
        if (feet == null || feet.isLiquid() || !dev.guardx.util.Compat.isClimbable(feet.getType())) {
            reset(d);
            return;
        }

        if (++d.climbTicks <= 4) return;

        d.climbSum += c.dy;
        d.climbCount++;
        if (d.climbCount < integer("window", 10)) return;

        double avg = d.climbSum / d.climbCount;
        d.climbSum = 0;
        d.climbCount = 0;

        double max = num("max-avg", 0.17);
        if (avg <= max) {
            d.addBuffer("spider", -1.0);
            return;
        }
        double buf = d.addBuffer("spider", 1.0);
        if (buf >= num("buffer", 2)) {
            d.setBuffer("spider", 0);
            flagMove(c, t("f-spider", "avg", Lang.n(avg, 3)), 1.0);
        }
    }

    private static void reset(PlayerData d) {
        d.climbTicks = 0;
        d.climbCount = 0;
        d.climbSum = 0;
    }
}
