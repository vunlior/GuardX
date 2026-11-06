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
import dev.guardx.util.LagComp;
import dev.guardx.util.MoveUtil;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;

/**
 * Килаура (часть, которой нужен мир): удары мимо хитбокса цели и удары через стены.
 * Мульти-ауру и удары без взмаха руки ловит пакетный слой.
 */
public final class KillAuraCheck extends Check {

    public KillAuraCheck(GuardX plugin) {
        super(plugin, "killaura");
    }

    @Override
    public boolean onAttack(Player p, PlayerData d, Entity target, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;

        boolean cancel = false;
        boolean cancelHits = bool("cancel-invalid-hits", true);
        double bufMax = num("buffer", 4);

        Location eye = p.getEyeLocation();
        Vector toTarget = target.getBoundingBox().getCenter().subtract(eye.toVector());
        double dist = toTarget.length();
        if (dist < 0.0001) return false;

        // 1) Луч взгляда должен попадать в хитбокс цели (с запасом на лаги и поворот мыши).
        //    Это намного точнее старой проверки "угол до центра цели < 60°".
        if (bool("hitbox", true)) {
            List<BoundingBox> boxes = LagComp.boxes(plugin, p, target, now);
            if (!aimsAt(d, eye, boxes, num("hitbox-expand", 0.35)
                    + (target instanceof Player ? 0.0 : Math.min(Math.max(dev.guardx.util.Compat.ping(p), 0), 400) / 1000.0 * 5.0))) {
                double buf = d.addBuffer("killaura-hitbox", 1.0);
                // Первый промах не отменяем (мог быть лаг), повторные — да
                if (buf >= 2) cancel = cancelHits;
                if (buf >= bufMax) {
                    d.setBuffer("killaura-hitbox", 0);
                    flag(p, d, t("f-killaura-hitbox", "dist", Lang.n(dist, 1)), 1.0);
                }
            } else {
                d.addBuffer("killaura-hitbox", -0.5);
            }
        }

        // 2) Удар через стену: между глазами и целью есть твёрдый блок
        if (bool("check-walls", true) && dist > 1.0) {
            Vector dir = toTarget.clone().normalize();
            RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, dist, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitPosition().distance(eye.toVector()) < dist - 0.8) {
                double buf = d.addBuffer("killaura-wall", 1.0);
                if (buf >= bufMax) {
                    d.setBuffer("killaura-wall", 0);
                    flag(p, d, t("f-killaura-wall"), 1.5);
                }
                if (buf >= 2) cancel = cancelHits;
            } else {
                d.addBuffer("killaura-wall", -0.5);
            }
        }
        return cancel;
    }

    /**
     * Клиент бьёт по направлению взгляда, который сервер ещё не получил (пакет поворота идёт ПОСЛЕ пакета удара),
     * поэтому проверяем три возможных направления: текущее, прошлое и экстраполированное.
     */
    private boolean aimsAt(PlayerData d, Location eye, List<BoundingBox> boxes, double expand) {
        Vector origin = eye.toVector();
        float yaw = eye.getYaw();
        float pitch = eye.getPitch();

        Vector[] dirs = {
                MoveUtil.direction(yaw, pitch),
                MoveUtil.direction(d.prevYaw, d.prevPitch),
                MoveUtil.direction(yaw + wrap(yaw - d.prevYaw), clampPitch(pitch + (pitch - d.prevPitch)))
        };

        for (BoundingBox b : boxes) {
            BoundingBox e = b.clone().expand(expand);
            if (e.contains(origin)) return true;
            for (Vector dir : dirs) {
                if (e.rayTrace(origin, dir, 8.0) != null) return true;
            }
        }
        return false;
    }

    private static float wrap(float angle) {
        angle %= 360f;
        if (angle > 180f) angle -= 360f;
        if (angle < -180f) angle += 360f;
        return angle;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }
}
