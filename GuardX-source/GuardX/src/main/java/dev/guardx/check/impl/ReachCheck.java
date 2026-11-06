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
import dev.guardx.util.Compat;
import dev.guardx.GuardX;
import dev.guardx.check.Check;
import dev.guardx.data.PlayerData;
import dev.guardx.util.LagComp;
import dev.guardx.util.MoveUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * Удар по цели, которая находится дальше, чем можно дотянуться.
 * Для игроков используется компенсация задержки: берётся ближайшая точка из хитбоксов цели
 * за последние "пинг + запас" мс, поэтому лимит можно держать близко к ванильным 3.0.
 */
public final class ReachCheck extends Check {

    public ReachCheck(GuardX plugin) {
        super(plugin, "reach");
    }

    @Override
    public boolean onAttack(Player p, PlayerData d, Entity target, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;

        Location eye = p.getEyeLocation();
        double max = target instanceof Player ? num("max-distance", 3.15) : num("max-distance-mob", 3.5);

        if (!(target instanceof Player)) { // у мобов нет истории позиций: запас на пинг
            max += Math.min(Math.max(Compat.ping(p), 0), 400) / 1000.0 * num("mob-speed-bps", 5.0);
        }
        double dist = Double.MAX_VALUE;
        for (BoundingBox b : LagComp.boxes(plugin, p, target, now)) {
            dist = Math.min(dist, MoveUtil.distanceToBox(eye.getX(), eye.getY(), eye.getZ(), b));
        }

        if (dist <= max) {
            d.addBuffer("reach", -0.25);
            return false;
        }

        double buf = d.addBuffer("reach", 1.0);
        if (buf >= num("buffer", 3)) {
            d.setBuffer("reach", 0);
            flag(p, d, t("f-reach", "dist", Lang.n(dist, 2), "max", Lang.n(max, 2), "ping", Compat.ping(p)), 1.0);
        }
        return bool("cancel-invalid-hits", true);
    }
}
