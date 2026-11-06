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
import dev.guardx.util.Compat;
import dev.guardx.util.MoveUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Jesus: ходьба по воде (и лаве). Игрок держится у самой поверхности жидкости,
 * над ним воздух, твёрдой опоры нет, а по высоте он почти не двигается.
 * Настоящий пловец или прыгающий по воде всегда "качается" вверх-вниз.
 *
 * Две детали, которые важны для обнаружения:
 *  - чит может "парить" чуть выше поверхности (ноги уже в воздухе), поэтому
 *    ищем поверхность жидкости под ногами, а не только блок на уровне ног;
 *  - одиночный порог |dy| легко обойти джиттером, поэтому вместо него считается
 *    серия подозрительных тиков с контролем суммарного размаха высоты.
 */
public final class JesusCheck extends Check {

    public JesusCheck(GuardX plugin) {
        super(plugin, "jesus");
    }

    @Override
    public void onMove(MoveContext c) {
        PlayerData d = c.data;
        // Здесь нельзя использовать nearSpecial(): вода у него "особая" и проверка бы никогда не сработала
        if (!c.positionChanged || c.exempt() || c.serverGround || c.player.isSwimming()) {
            d.jesusTicks = 0;
            return;
        }

        World w = c.to.getWorld();
        double x = c.to.getX();
        double y = c.to.getY();
        double z = c.to.getZ();
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);

        // Ищем поверхность жидкости: сканируем вниз от ног до 2 блоков, пропуская
        // только проходимые блоки. Твёрдый блок или другая жидкость — поверхность не найдена
        // (игрок на дне бассейна или глубоко под водой сюда не попадает).
        int surfaceTop = Integer.MIN_VALUE;
        for (int yy = by; yy >= by - 2; yy--) {
            Block b = MoveUtil.loadedBlock(w, bx, yy, bz);
            if (b == null) break;
            Material m = b.getType();
            if (m == Material.WATER || m == Material.LAVA) {
                surfaceTop = yy + 1;
                break;
            }
            if (!b.isPassable() || b.isLiquid() || Compat.isClimbable(m)) break;
        }
        if (surfaceTop == Integer.MIN_VALUE) {
            d.jesusTicks = 0;
            return;
        }

        // Ноги должны быть у самой поверхности: чуть ниже, на ней или чуть выше
        // ("парение" чита). Глубже submerge — это уже плавание, а не ходьба по воде.
        double rel = y - surfaceTop;
        if (rel > num("hover", 0.5) || rel < -num("submerge", 0.25)) {
            d.jesusTicks = 0;
            return;
        }

        // Над головой (~1.8 блока) должно быть свободно и без жидкости —
        // иначе это плавание под потолком или в глубокой воде.
        int headTop = (int) Math.floor(y + 1.8);
        for (int yy = by + 1; yy <= headTop; yy++) {
            Block b = MoveUtil.loadedBlock(w, bx, yy, bz);
            if (b == null || !b.isPassable() || b.isLiquid() || Compat.isClimbable(b.getType())) {
                d.jesusTicks = 0;
                return;
            }
        }

        if (c.horizontal < num("min-speed", 0.1)) {
            d.jesusTicks = 0;
            return;
        }

        // Серия подозрительных тиков. Джиттер до max-tick-dy не сбрасывает серию,
        // но суммарный размах высоты за неё не должен превышать flat-range —
        // у настоящего пловца он заметно больше.
        if (d.jesusTicks == 0) {
            d.jesusMinY = y;
            d.jesusMaxY = y;
        }
        d.jesusTicks++;
        d.jesusMinY = Math.min(d.jesusMinY, y);
        d.jesusMaxY = Math.max(d.jesusMaxY, y);

        if (Math.abs(c.dy) > num("max-tick-dy", 0.1)
                || d.jesusMaxY - d.jesusMinY > num("flat-range", 0.2)) {
            d.jesusTicks = 0;
            return;
        }

        if (d.jesusTicks >= integer("ticks", 8)) {
            d.jesusTicks = 0;
            flagMove(c, t("f-jesus", "speed", Lang.n(c.horizontal, 2)), 1.0);
        }
    }
}
