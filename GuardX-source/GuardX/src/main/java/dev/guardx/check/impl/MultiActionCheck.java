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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * MultiAction / AutoBlock: удар во время использования предмета (еда, щит, лук, трезубец).
 * Клиент не даёт атаковать, пока руки заняты, поэтому такой удар возможен только с модифицированным клиентом.
 * Небольшая задержка допускается: сервер мог ещё не успеть завершить использование предмета.
 */
public final class MultiActionCheck extends Check {

    public MultiActionCheck(GuardX plugin) {
        super(plugin, "multiaction");
    }

    @Override
    public void onTick(Player p, PlayerData d, long now) {
        if (p.isHandRaised()) {
            if (d.handRaisedSince == 0) d.handRaisedSince = now;
        } else {
            d.handRaisedSince = 0;
        }
    }

    @Override
    public boolean onAttack(Player p, PlayerData d, Entity target, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;

        if (!p.isHandRaised() || d.handRaisedSince == 0) {
            d.addBuffer("multiaction", -0.5);
            return false;
        }
        long held = now - d.handRaisedSince;
        if (held < integer("grace-ms", 250)) return false;

        double buf = d.addBuffer("multiaction", 1.0);
        if (buf >= num("buffer", 3)) {
            d.setBuffer("multiaction", 0);
            flag(p, d, t("f-multiaction", "held", held), 1.0);
        }
        return bool("cancel-invalid-hits", true);
    }
}
