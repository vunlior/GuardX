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
import dev.guardx.util.MoveUtil;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

/** Ищет только серию неправдоподобно частых разрушений; одиночные быстрые блоки не учитываются. */
public final class FastBreakCheck extends Check {
    public FastBreakCheck(GuardX plugin) { super(plugin, "fastbreak"); }

    @Override
    public void onBreak(Player p, PlayerData d, BlockBreakEvent event, long now) {
        if (p.getGameMode() != GameMode.SURVIVAL || plugin.bypass(p) || p.isDead()) return;
        // Блок, который игрок не начинал ломать сам (VeinMiner, TreeCapitator и т.п. ломают через API),
        // не учитываем: у такого блока нет предшествующего BlockDamageEvent.
        if (d.lastDigKey != MoveUtil.blockKey(event.getBlock())) return;
        String type = event.getBlock().getType().getKey().toString();
        long interval = d.lastBreakAt == 0 ? Long.MAX_VALUE : now - d.lastBreakAt;
        // Порог намеренно высокий: не вмешивается в одиночные блоки и обычную добычу с Efficiency.
        if (interval < integer("min-interval-ms", 65) && !type.equals(d.lastBreakType)) {
            d.consecutiveFastBreaks++;
        } else {
            d.consecutiveFastBreaks = Math.max(0, d.consecutiveFastBreaks - 1);
        }
        d.lastBreakAt = now;
        d.lastBreakType = type;
        if (d.consecutiveFastBreaks >= integer("required-consecutive", 7)) {
            d.consecutiveFastBreaks = 0;
            flag(p, d, t("f-fastbreak"), 1.0);
        }
    }
}
