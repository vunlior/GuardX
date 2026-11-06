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
import dev.guardx.check.MoveContext;
import dev.guardx.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

import java.util.Locale;

/**
 * InvMove: игрок бегает с открытым контейнером (сундук, наковальня, торговля и т.п.).
 * Клиент при открытом экране не принимает клавиши движения, так что после затухания инерции
 * (пара тиков) скорость на земле падает почти до нуля. Своё собственное окно инвентаря сервер не видит,
 * поэтому проверяются только контейнеры. На серверах с Bedrock-игроками (Geyser) лучше отключить.
 */
public final class InvMoveCheck extends Check {

    public InvMoveCheck(GuardX plugin) {
        super(plugin, "invmove");
    }

    @Override
    public void onTick(Player p, PlayerData d, long now) {
        if (p.getOpenInventory().getType() == InventoryType.CRAFTING) {
            d.invOpenSince = 0;
        } else if (d.invOpenSince == 0) {
            d.invOpenSince = now;
        }
    }

    @Override
    public void onMove(MoveContext c) {
        PlayerData d = c.data;
        if (d.invOpenSince == 0 || !c.positionChanged || c.exempt() || c.nearSpecial()
                || !c.serverGround || c.now - d.lastIce < 2000) {
            d.invMoveTicks = 0;
            return;
        }

        // Клиент узнаёт об открытии не сразу, плюс инерция прежнего бега — даём запас
        long grace = Math.min(Math.max(Compat.ping(c.player), 0), 400) + 500;
        if (c.now - d.invOpenSince < grace) return;

        if (c.horizontal < num("min-speed", 0.12)) {
            d.invMoveTicks = 0;
            return;
        }
        if (++d.invMoveTicks >= integer("ticks", 8)) {
            d.invMoveTicks = 0;
            flagMove(c, t("f-invmove", "speed", Lang.n(c.horizontal, 3)), 1.0);
        }
    }
}
