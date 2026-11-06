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
import dev.guardx.Lang;
import dev.guardx.check.Check;
import dev.guardx.check.MoveContext;
import dev.guardx.data.PlayerData;
import dev.guardx.util.Compat;
import dev.guardx.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/**
 * NoRotate: чит-клиент отбрасывает поворот камеры, который сервер задаёт при телепорте.
 *
 * Как это видно серверу. После телепорта сервер игнорирует пакеты движения, пока клиент не подтвердит
 * телепорт, поэтому первое событие движения после него несёт то, что клиент реально считает своим поворотом.
 * Ванильный клиент принимает поворот сервера (его значение плюс то, что игрок успел сделать мышью).
 * NoRotate-клиент остаётся на старом повороте.
 *
 * Алгоритм:
 *  1) onTeleport: запоминаем старый и заданный поворот. Если сервер повернул игрока меньше чем на
 *     min-change градусов, такой телепорт не проверяем (разницу не отличить от движения мыши);
 *  2) onMove: берём первое событие после телепорта и считаем углы между направлением взгляда клиента,
 *     старым и заданным направлением. Нарушение: клиент далеко от заданного, но близко к старому;
 *  3) буфер: сильный случай (почти точно старый поворот при развороте от 90°) даёт +2, обычный +1.
 *
 * Ограничение: видит только телепорты, прошедшие через PlayerTeleportEvent. Поворот через
 * Player#setRotation и подобные вызовы без телепорта события не даёт и сюда не попадает.
 */
public final class NoRotateCheck extends Check {

    public NoRotateCheck(GuardX plugin) {
        super(plugin, "norotate");
    }

    @Override
    public void onTeleport(Player p, PlayerData d, PlayerTeleportEvent e, long now) {
        d.nrPending = false;
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to == null || plugin.bypass(p)) return;

        double change = angle(from.getYaw(), from.getPitch(), to.getYaw(), to.getPitch());
        if (change < num("min-change", 45)) return;

        d.nrPending = true;
        d.nrTime = now;
        d.nrYaw = to.getYaw();
        d.nrPitch = to.getPitch();
        d.nrOldYaw = from.getYaw();
        d.nrOldPitch = from.getPitch();
        d.nrChange = change;
    }

    @Override
    public void onMove(MoveContext c) {
        PlayerData d = c.data;
        if (!d.nrPending) return;

        if (c.now - d.nrTime > integer("window-ms", 3000)) {
            d.nrPending = false;
            return;
        }
        d.nrPending = false; // судим только первое событие после телепорта

        Player p = c.player;
        if (plugin.bypass(p)) return;

        double change = d.nrChange;
        float yaw = c.to.getYaw();
        float pitch = c.to.getPitch();
        double dNew = angle(yaw, pitch, d.nrYaw, d.nrPitch);
        double dOld = angle(yaw, pitch, d.nrOldYaw, d.nrOldPitch);

        // Допуск на мышь: базовый + ~0.2° на каждую мс пинга (за это время игрок успевает повернуться)
        double tol = num("tolerance", 25) + Compat.ping(p) * 0.2;

        boolean farFromTarget = dNew > Math.max(tol, change * 0.7);
        boolean nearOld = dOld <= change * 0.3;
        if (!(farFromTarget && nearOld)) {
            d.addBuffer("norotate", -1.0);
            return;
        }

        boolean strong = dOld <= 3.0 && change >= 90.0;
        double buf = d.addBuffer("norotate", strong ? 2.0 : 1.0);
        if (buf >= num("buffer", 2)) {
            d.setBuffer("norotate", 0);
            flag(p, d, t("f-norotate",
                    "change", Lang.n(change, 0),
                    "old", Lang.n(dOld, 0),
                    "new", Lang.n(dNew, 0)), 1.0);
        }
    }

    /** Угол между двумя направлениями взгляда в градусах (учитывает переход через 360° по yaw). */
    private static double angle(float yaw1, float pitch1, float yaw2, float pitch2) {
        Vector a = MoveUtil.direction(yaw1, pitch1);
        Vector b = MoveUtil.direction(yaw2, pitch2);
        return Math.toDegrees(a.angle(b));
    }
}
