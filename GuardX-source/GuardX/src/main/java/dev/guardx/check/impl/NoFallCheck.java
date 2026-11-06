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

/**
 * Подделка флага onGround, чтобы не получать урон от падения.
 *
 * Считаем "спуф" только когда игрок уже провисел в воздухе минимум min-air-ticks тиков
 * (серверных, не клиентских): одиночные сбойные пакеты на приземлении/краю блока
 * больше не дают флаг. Порог падения настраивается (min-dy).
 * Опционально (ascend) ловим и подделку на подъёме: легитный клиент никогда
 * не заявляет "я на земле", двигаясь вверх.
 */
public final class NoFallCheck extends Check {

    public NoFallCheck(GuardX plugin) {
        super(plugin, "nofall");
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged || c.exempt()) return;

        PlayerData d = c.data;
        double minDy = num("min-dy", 0.08);

        boolean falling = c.clientGround && !c.serverGround && c.dy < -minDy;
        boolean rising = bool("ascend", false)
                && c.clientGround && !c.serverGround && c.dy > num("ascend-dy", 0.15);

        if (!falling && !rising) {
            d.addBuffer("nofall", -0.5);
            return;
        }
        // Слишком мало времени в воздухе — скорее сбой пакета, чем чит
        if (d.airTicks < integer("min-air-ticks", 5)) {
            d.addBuffer("nofall", -0.25);
            return;
        }
        if (c.nearSpecial()) return;

        double buf = d.addBuffer("nofall", rising ? 0.5 : 1.0);
        if (buf >= num("buffer", 3)) {
            d.setBuffer("nofall", 0);
            flagMove(c, t("f-nofall", "dy", Lang.n(c.dy, 2)), 1.0);
        }
    }
}
