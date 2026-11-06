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

package dev.guardx.util;

import dev.guardx.GuardX;
import dev.guardx.data.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** Компенсация задержки: набор мест, где цель могла быть с точки зрения клиента атакующего. */
public final class LagComp {

    private LagComp() {
    }

    /**
     * Текущий хитбокс цели + (для игроков) её хитбоксы за последние "пинг атакующего + запас" мс.
     * Пинг ограничен lag-compensation.max-ping-ms, чтобы читер не мог раздуть окно искусственным лагом.
     * Боксы нельзя изменять.
     */
    public static List<BoundingBox> boxes(GuardX plugin, Player attacker, Entity target, long now) {
        List<BoundingBox> out = new ArrayList<>(24);
        out.add(target.getBoundingBox());

        if (target instanceof Player tp) {
            PlayerData td = plugin.peekData(tp.getUniqueId());
            if (td != null) {
                long maxPing = plugin.getConfig().getLong("lag-compensation.max-ping-ms", 400);
                long extra = plugin.getConfig().getLong("lag-compensation.extra-ms", 100);
                long rewind = Math.min(Math.max(Compat.ping(attacker), 0), maxPing) + extra;
                td.history.collect(now, rewind, out);
            }
        }
        return out;
    }
}
