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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * BlockReach: установка и ломание блоков дальше, чем можно дотянуться. Дальность до блоков в выживании
 * 4.5 (в творчестве 5.0). Сам сервер отсекает только совсем далёкие действия (около 5.5+),
 * а чит с дальностью 5–5.5 проходит. Расстояние считается от глаз до ближайшей точки блока.
 */
public final class BlockReachCheck extends Check {

    public BlockReachCheck(GuardX plugin) {
        super(plugin, "blockreach");
    }

    @Override
    public boolean onPlace(Player p, PlayerData d, Block placed, Block against, long now) {
        return test(p, d, against, t("f-blockreach-place")) && bool("cancel-invalid", true);
    }

    @Override
    public void onBreak(Player p, PlayerData d, BlockBreakEvent event, long now) {
        test(p, d, event.getBlock(), t("f-blockreach-break"));
    }

    /** @return true, если дальность превышена */
    private boolean test(Player p, PlayerData d, Block target, String what) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;

        Location eye = p.getEyeLocation();
        BoundingBox box = new BoundingBox(target.getX(), target.getY(), target.getZ(),
                target.getX() + 1, target.getY() + 1, target.getZ() + 1);
        double dist = MoveUtil.distanceToBox(eye.getX(), eye.getY(), eye.getZ(), box);
        double max = num("max-distance", 4.9);

        if (dist <= max) {
            d.addBuffer("blockreach", -0.25);
            return false;
        }
        double buf = d.addBuffer("blockreach", 1.0);
        if (buf >= num("buffer", 3)) {
            d.setBuffer("blockreach", 0);
            flag(p, d, t("f-blockreach", "what", what, "dist", Lang.n(dist, 2), "max", Lang.n(max, 2)), 1.0);
        }
        return true;
    }
}
