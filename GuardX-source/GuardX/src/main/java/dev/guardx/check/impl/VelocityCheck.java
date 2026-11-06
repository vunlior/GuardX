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
import dev.guardx.util.MoveUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Velocity / AntiKnockback: игрок, которого ударили, должен подскочить вверх.
 * Проверяем только вертикальную часть: она не зависит от того, куда игрок жмёт клавиши,
 * и срабатывает, только если игрок стоял на земле, над головой свободно, вокруг нет воды/паутины/слайма.
 * Результат оценивается по таймеру (пинг + настраиваемый запас), поэтому игрока,
 * который просто стоит на месте и игнорирует отбрасывание, тоже видно.
 *
 * Мелкие velocity (ниже min-vertical) НЕ сбрасывают текущее ожидание: иначе AntiKB
 * мог "пробить" проверку, отправив лишний слабый толчок следом за настоящим ударом.
 * Серия ударов подряд считается одним залпом (максимум ожидаемой высоты, накопленный
 * dy не затирается), а по burst-ms оценка форсируется — иначе непрерывный урон
 * (моб-грайндер, спам-клик) держал бы проверку вечно неоценённой.
 * Опционально (check-horizontal) проверяется и горизонтальная составляющая KB:
 * сколько игрок прошёл вдоль направления отбрасывания.
 */
public final class VelocityCheck extends Check {

    public VelocityCheck(GuardX plugin) {
        super(plugin, "velocity");
    }

    @Override
    public void onVelocity(Player p, PlayerData d, Vector vel, long now) {
        // Слабые толчки не считаем и не сбрасываем уже ожидающее отбрасывание
        if (vel.getY() < num("min-vertical", 0.25)) return;

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) {
            d.velPending = false;
            return;
        }
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isInsideVehicle()
                || p.isDead() || p.isSleeping() || p.isRiptiding()) {
            d.velPending = false;
            return;
        }

        Location l = p.getLocation();
        World w = l.getWorld();
        if (w == null
                || !MoveUtil.onGround(w, l.getX(), l.getY(), l.getZ(), plugin.groundHeight())
                || MoveUtil.nearSpecial(p, l.getX(), l.getY(), l.getZ(), false)
                || MoveUtil.collides(w, new BoundingBox(l.getX() - 0.3, l.getY() + 1.8, l.getZ() - 0.3,
                        l.getX() + 0.3, l.getY() + 2.8, l.getZ() + 0.3))) {
            d.velPending = false;
            return;
        }

        // Серия ударов подряд — один "залп": ожидаем максимум, накопленный dy не затираем.
        // Иначе спам-урон держал бы ожидание вечно и AntiKB не ловился бы вообще.
        boolean fresh = !d.velPending;
        if (fresh) {
            d.velSince = now;
            d.velBestDy = -10;
            d.velExpectedY = vel.getY();
            d.velExpectedHor = 0;
            d.velHorAlong = 0;
        } else {
            d.velExpectedY = Math.max(d.velExpectedY, vel.getY());
        }
        d.velPending = true;
        d.velTime = now;

        // Горизонтальная составляющая — опционально (зашумлена WASD игрока)
        double h = Math.hypot(vel.getX(), vel.getZ());
        if (bool("check-horizontal", false) && h >= num("min-horizontal", 0.15)) {
            if (fresh || d.velExpectedHor == 0) {
                d.velExpectedHor = h;
                d.velDirX = vel.getX() / h;
                d.velDirZ = vel.getZ() / h;
            } else {
                d.velExpectedHor = Math.max(d.velExpectedHor, h);
            }
        }
    }

    @Override
    public void onMove(MoveContext c) {
        PlayerData d = c.data;
        if (!d.velPending) return;
        if (c.exemptBase()) { // телепорт, смерть, транспорт и т.п. — ожидание отменяем
            d.velPending = false;
            return;
        }
        if (c.now >= d.velTime) {
            d.velBestDy = Math.max(d.velBestDy, c.dy);
            // Сколько прошли ВДОЛЬ направления отбрасывания (собственное движение игрока усредняется ~0)
            if (d.velExpectedHor > 0) {
                double along = c.dx * d.velDirX + c.dz * d.velDirZ;
                if (along > 0) d.velHorAlong += along;
            }
        }
    }

    @Override
    public void onTick(Player p, PlayerData d, long now) {
        if (!d.velPending) return;

        long ping = Math.min(Math.max(Compat.ping(p), 0), 400);
        boolean quiet = now - d.velTime >= ping + num("extra-ms", 600);
        boolean burstOver = now - d.velSince >= num("burst-ms", 2500);
        if (!quiet && !burstOver) return;
        d.velPending = false;

        if (p.isDead() || d.lastTeleport > d.velTime) return;
        if (plugin.tps() < 18.0) return; // сервер лагает — выводам верить нельзя

        if (d.velBestDy >= d.velExpectedY * num("pass-ratio", 0.5)) {
            d.addBuffer("velocity", -1.0);
        } else {
            double buf = d.addBuffer("velocity", 1.0);
            if (buf >= num("buffer", 3)) {
                d.setBuffer("velocity", 0);
                flag(p, d, t("f-velocity", "got", Lang.n(Math.max(d.velBestDy, 0.0), 2), "expected", Lang.n(d.velExpectedY, 2)), 1.0);
            }
        }

        // Горизонтальное отбрасывание (если включено): игрок должен был сместиться
        // хотя бы немного вдоль направления KB. Свой WASD даёт шум, поэтому порог очень мягкий.
        if (d.velExpectedHor > 0) {
            double got = Math.max(0.0, d.velHorAlong);
            double need = d.velExpectedHor * num("hor-pass-ratio", 0.3);
            if (got >= need) {
                d.addBuffer("velocityh", -1.0);
            } else {
                double hbuf = d.addBuffer("velocityh", 1.0);
                if (hbuf >= num("buffer", 3)) {
                    d.setBuffer("velocityh", 0);
                    flag(p, d, t("f-velocity-hor", "got", Lang.n(got, 2), "expected", Lang.n(need, 2)), 1.0);
                }
            }
        }
    }
}
