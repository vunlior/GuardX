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
import dev.guardx.util.Compat;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Скорость ударов (CPS) в бою — с учётом оружия.
 *
 * Считаются реальные удары (EntityDamageByEntityEvent), а не взмахи руки:
 * автокликер-спам взмахами уже ловит проверка autoclicker, здесь важна частота УРОНА.
 *
 * Лимит "полных" ударов = атрибут attack_speed (меч ~1.6/с, топор ~0.8-3.1/с, кулак ~4/с):
 * ванильный кулдаун не даёт бить полный урон чаще. Читер, игнорирующий кулдаун
 * (KillAura/Aim с packet-bypass), выдаёт такие удары подряд — это почти не даёт ложных.
 *
 * Все удары (включая слабые, на перезарядке) тоже считаются, но с большим запасом:
 * спам-клик легально бьёт часто, просто с уроном ~0 — поэтому порог выше.
 *
 * Sweeping Edge бьёт несколько целей за один взмах — удары ближе sweep-window-ms
 * друг к другу считаются одним.
 */
public final class CpsCheck extends Check {

    public CpsCheck(GuardX plugin) {
        super(plugin, "cps");
    }

    @Override
    public boolean onAttack(Player p, PlayerData d, Entity target, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)
                || p.isDead() || p.isInsideVehicle() || p.isGliding() || p.isRiptiding()) {
            return false;
        }

        // Один взмах (sweeping edge, мультихит) — одно событие CPS
        long prev = d.lastCpsSwing;
        d.lastCpsSwing = now;
        if (prev != 0 && now - prev < num("sweep-window-ms", 50)) return false;

        double speed = Compat.attackSpeed(p);           // полных ударов в секунду по оружию
        double cooldownMs = 1000.0 / speed;             // длительность перезарядки в мс
        boolean full = prev == 0 || (now - prev) >= cooldownMs * num("full-charge", 0.9);

        Deque<Long> all = d.cpsTimes;
        all.addLast(now);
        while (!all.isEmpty() && now - all.peekFirst() > 1000) all.pollFirst();
        int cps = all.size();

        double maxAll = speed * num("cps-multiplier", 2.5);
        int bufMax = integer("buffer", 3);

        if (cps > maxAll) {
            double buf = d.addBuffer("cps", 1.0);
            if (buf >= bufMax) {
                d.setBuffer("cps", 0);
                flag(p, d, t("f-cps", "cps", cps, "max", Lang.n(maxAll, 1)), 1.0);
            }
            return false;
        }
        d.addBuffer("cps", -0.5);

        if (!full) return false;
        Deque<Long> fulls = d.fullTimes;
        fulls.addLast(now);
        while (!fulls.isEmpty() && now - fulls.peekFirst() > 1000) fulls.pollFirst();
        int fullCps = fulls.size();

        double maxFull = Math.max(2.0, speed * num("full-multiplier", 1.3));
        if (fullCps > maxFull) {
            double buf = d.addBuffer("cpsfull", 1.0);
            if (buf >= bufMax) {
                d.setBuffer("cpsfull", 0);
                flag(p, d, t("f-cps-full", "cps", fullCps, "max", Lang.n(maxFull, 1), "weapon", Lang.n(speed, 1)), 1.5);
            }
        } else {
            d.addBuffer("cpsfull", -0.5);
        }
        return false;
    }
}
