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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Deque;

/**
 * Nuker / FastBreak-пачками: слишком много начатых ломаний блоков в секунду.
 * В ванили клиент начинает ломать новый блок не чаще, чем раз в 5 тиков (до ~4–6 в секунду).
 * Считаем именно начало ломания (BlockDamageEvent), поэтому плагины вроде VeinMiner, ломающие блоки
 * через API, не мешают.
 *
 * Игнорируются: креатив/спектатор (insta-break), мгновенно ломающиеся блоки (трава, цветы,
 * факелы — hardness <= 0) и недобиваемые (hardness < 0), а также повторные клики по тому же
 * блоку чаще, чем раз в 350 мс — BlockDamageEvent стреляет на каждый START_DESTROY_BLOCK,
 * даже если блок не ломается.
 */
public final class NukerCheck extends Check {

    private static final long SAME_BLOCK_MS = 350;

    public NukerCheck(GuardX plugin) {
        super(plugin, "nuker");
    }

    @Override
    public boolean onDig(Player p, PlayerData d, Block block, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p) || p.isDead()) return false;

        // Нецельные блоки (растения, факелы, снег, ковры, лианы) ломаются мгновенно и легально
        // идут пачками — автокликер "пробегает" их без остановки. Тоже пропускаем.
        if (!block.getType().isSolid()) return false;
        float hardness = block.getType().getHardness();
        if (hardness <= num("min-hardness", 0.1) || hardness >= 100f) return false;

        long key = MoveUtil.blockKey(block);
        if (key == d.nukerKey && now - d.nukerAt < SAME_BLOCK_MS) return false;
        d.nukerKey = key;
        d.nukerAt = now;

        Deque<Long> q = d.digTimes;
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();

        int max = integer("max-per-second", 12);
        if (q.size() > max) {
            flag(p, d, t("f-nuker", "count", q.size(), "max", max), 1.0);
            return bool("cancel-invalid", true);
        }
        return false;
    }
}
