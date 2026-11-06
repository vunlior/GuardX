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

import dev.guardx.GuardX;
import dev.guardx.check.Check;
import dev.guardx.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Консервативная проверка аномально частых действий в интерфейсе. */
public final class InventoryCheck extends Check {
    public InventoryCheck(GuardX plugin) { super(plugin, "inventory"); }

    @Override
    public void onInventoryClick(Player p, PlayerData d, InventoryClickEvent event, long now) {
        if (p.getGameMode() == GameMode.CREATIVE || plugin.bypass(p) || p.isDead()) return;
        d.inventoryClickTimes.addLast(now);
        long window = integer("window-ms", 1000);
        while (!d.inventoryClickTimes.isEmpty() && now - d.inventoryClickTimes.peekFirst() > window)
            d.inventoryClickTimes.pollFirst();
        int max = integer("max-clicks-per-window", 45);
        if (d.inventoryClickTimes.size() > max) {
            double buffer = d.addBuffer("inventory", 1.0);
            if (buffer >= num("buffer", 5)) {
                d.setBuffer("inventory", 0);
                flag(p, d, t("f-inventory", "count", d.inventoryClickTimes.size(), "window", window), 1.0);
            }
        } else {
            d.addBuffer("inventory", -0.25);
        }
    }
}
