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
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Locale;

/**
 * FastBow: выстрел с большой силой без нужного времени натяжения.
 * Сила выстрела зависит только от времени натяжения: f = t/20 тиков, сила = (f*f + 2f)/3, максимум 1.0 при 1 секунде.
 * Время считается между нажатием ПКМ (PlayerInteractEvent) и самим выстрелом на сервере, поэтому пинг роли не играет.
 */
public final class FastBowCheck extends Check {

    public FastBowCheck(GuardX plugin) {
        super(plugin, "fastbow");
    }

    @Override
    public void onInteract(Player p, PlayerData d, PlayerInteractEvent e, long now) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getItem() != null && e.getItem().getType() == Material.BOW) {
            d.useStart = now;
        }
    }

    @Override
    public boolean onShoot(Player p, PlayerData d, EntityShootBowEvent e, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;
        if (e.getBow() == null || e.getBow().getType() != Material.BOW) return false;
        if (d.useStart == 0) return false;

        double force = e.getForce();
        if (force < 0.3) return false;

        // Обратная формула: время натяжения (в секундах), нужное для такой силы
        double neededSeconds = -1.0 + Math.sqrt(1.0 + 3.0 * Math.min(force, 1.0));
        double neededMs = neededSeconds * 1000.0;
        long elapsed = now - d.useStart;
        double allowed = neededMs * num("ratio", 0.75) - num("slack-ms", 100);

        if (elapsed >= allowed) {
            d.addBuffer("fastbow", -0.5);
            return false;
        }

        double buf = d.addBuffer("fastbow", 1.0);
        if (buf >= num("buffer", 3)) {
            d.setBuffer("fastbow", 0);
            flag(p, d, t("f-fastbow", "force", Lang.n(force, 2), "elapsed", elapsed, "needed", Lang.n(neededMs, 0)), 1.0);
        }
        return bool("cancel-invalid", true);
    }
}
