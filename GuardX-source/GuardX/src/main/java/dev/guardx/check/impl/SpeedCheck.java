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
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Deque;
import java.util.Locale;

/**
 * Слишком быстрое горизонтальное движение (speed, bhop-хаки, "быстрая ходьба").
 * Две проверки: лимит на один тик (ловит грубые рывки) и скользящее среднее за ~секунду
 * (ловит хаки, которые аккуратно держатся чуть ниже лимита одного тика).
 *
 * Сдвиг нормируется на время между событиями движения: при лагах и батчинге пакетов
 * один PlayerMoveEvent может покрывать несколько тиков — без нормировки это давало
 * и ложные срабатывания, и обход проверки редкими большими пакетами.
 */
public final class SpeedCheck extends Check {

    public SpeedCheck(GuardX plugin) {
        super(plugin, "speed");
    }

    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged) return;
        PlayerData d = c.data;

        // Сдвиг за тик: events могут приходить реже или чаще тика
        long dt = d.lastMoveAt <= 0 ? 50L : c.now - d.lastMoveAt;
        d.lastMoveAt = c.now;
        double ticks = Math.min(6.0, Math.max(1.0, dt / 50.0));
        double perTick = c.horizontal / ticks;

        if (c.exempt() || c.nearSpecial()) {
            d.speedWindow.clear();
            return;
        }

        Player p = c.player;

        // Множитель от зелья скорости, атрибута movement_speed и скорости ходьбы
        double mult = 1.0;
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) mult *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        mult *= Math.max(Math.max(1.0, p.getWalkSpeed() / 0.2), Compat.moveSpeedMult(p));

        boolean ice = c.now - d.lastIce < 2000;
        double limit = (c.serverGround ? num("max-ground", 0.42) : num("max-air", 0.55)) * mult;
        if (ice) limit *= num("ice-multiplier", 2.2);

        // 1) Скользящее среднее за последние N перемещений
        Deque<Double> w = d.speedWindow;
        if (ice) {
            w.clear();
        } else {
            w.addLast(perTick / mult);
            int size = Math.max(10, integer("avg-window", 20));
            while (w.size() > size) w.pollFirst();
            if (w.size() >= size) {
                double sum = 0;
                for (double v : w) sum += v;
                double avg = sum / w.size();
                double maxAvg = num("max-avg", 0.42);
                if (avg > maxAvg) {
                    double buf = d.addBuffer("speed-avg", 1.0);
                    if (buf >= num("avg-buffer", 10)) {
                        d.setBuffer("speed-avg", 0);
                        w.clear();
                        flagMove(c, t("f-speed-avg", "avg", Lang.n(avg, 3), "max", Lang.n(maxAvg, 3)), 1.0);
                        return;
                    }
                } else {
                    d.addBuffer("speed-avg", -0.5);
                }
            }
        }

        // 2) Лимит одного тика
        if (perTick <= limit) {
            d.addBuffer("speed", -0.3);
            return;
        }

        double over = perTick / limit - 1.0;
        double buf = d.addBuffer("speed", 1.0 + over * 5.0);
        if (buf >= num("buffer", 5)) {
            d.setBuffer("speed", 0);
            flagMove(c, t("f-speed", "speed", Lang.n(perTick, 3), "max", Lang.n(limit, 3)), 1.0 + over);
        }
    }
}
