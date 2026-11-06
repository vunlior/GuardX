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
import dev.guardx.data.PlayerData;
import dev.guardx.util.MoveUtil;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Deque;

/**
 * Скаффолд. Четыре слоя:
 *  1) скорость установки (с буфером, чтобы пачка пакетов после лага не давала VL);
 *  2) грань: нельзя кликнуть по грани, которая смотрит от глаз игрока (в ванили луч входит в блок снаружи);
 *  3) игрок должен смотреть на блок, к которому прикрепляет новый (учитываем ТЕКУЩИЙ и ПРЕДЫДУЩИЙ поворот,
 *     потому что пакет использования предмета приходит раньше пакета поворота того же тика);
 *  4) явно невозможный угол отменяется сразу, не дожидаясь буфера.
 */
public final class ScaffoldCheck extends Check {

    public ScaffoldCheck(GuardX plugin) {
        super(plugin, "scaffold");
    }

    @Override
    public boolean onPlace(Player p, PlayerData d, Block placed, Block against, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || plugin.bypass(p)) return false;

        boolean cancel = false;
        boolean cancelPlaces = bool("cancel-invalid-places", true);

        // 1) Скорость установки (с буфером)
        Deque<Long> q = d.placeTimes;
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > 1000) q.pollFirst();
        int max = integer("max-per-second", 14);
        if (q.size() > max) {
            double rateBuf = num("rate-buffer", 4);
            double buf = Math.min(d.addBuffer("scaffold-rate", 1.0), rateBuf + 2);
            d.setBuffer("scaffold-rate", buf);
            if (buf >= rateBuf) {
                flag(p, d, t("f-scaffold-rate", "count", q.size(), "max", max), 1.0);
                cancel = cancelPlaces;
            }
        } else {
            d.addBuffer("scaffold-rate", -0.5);
        }

        Location eye = p.getEyeLocation();

        // 2) Грань, по которой кликнули, должна быть "видна" глазам игрока
        if (bool("face", true) && now - d.lastTeleport >= 1500) {
            BlockFace face = against.getFace(placed);
            boolean eyeInside = eye.getBlockX() == against.getX() && eye.getBlockY() == against.getY()
                    && eye.getBlockZ() == against.getZ();
            if (face != null && face != BlockFace.SELF && face.isCartesian() && !eyeInside) {
                double side = faceSide(eye, against, face);
                if (side < -num("face-tolerance", 0.5)) {
                    double buf = d.addBuffer("scaffold-face", 1.0);
                    if (cancelPlaces) cancel = true;
                    if (buf >= num("face-buffer", 2)) {
                        d.setBuffer("scaffold-face", 0);
                        flag(p, d, t("f-scaffold-face", "dist", Lang.n(-side, 2)), 1.0);
                    }
                } else {
                    d.addBuffer("scaffold-face", -0.25);
                }
            }
        }

        // 3) Игрок смотрит на блок: пробуем текущее направление, затем предыдущее (поворот отстаёт на тик)
        double range = num("ray-distance", 6.0);
        Vector cur = eye.getDirection();
        boolean looking = hits(eye, cur, range, against);
        Vector prev = null;
        if (!looking) {
            prev = MoveUtil.direction(d.prevYaw, d.prevPitch);
            looking = hits(eye, prev, range, against);
        }
        if (looking) {
            d.addBuffer("scaffold", -0.5);
            return cancel;
        }

        Vector toBlock = against.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(eye.toVector());
        double angle = Math.toDegrees(cur.angle(toBlock));
        if (prev != null) angle = Math.min(angle, Math.toDegrees(prev.angle(toBlock)));

        if (angle > num("max-angle", 65)) {
            // 4) Явно невозможный угол (блок за спиной): отменяем сразу и считаем нарушение двойным
            boolean impossible = angle > num("hard-angle", 100);
            double buf = d.addBuffer("scaffold", impossible ? 2.0 : 1.0);
            if (impossible && cancelPlaces) cancel = true;
            if (buf >= num("buffer", 4)) {
                d.setBuffer("scaffold", 0);
                flag(p, d, t("f-scaffold-angle", "angle", Lang.n(angle, 0)), 1.0);
                cancel = cancelPlaces;
            }
        } else {
            d.addBuffer("scaffold", -0.25);
        }
        return cancel;
    }

    private static boolean hits(Location eye, Vector dir, double range, Block target) {
        RayTraceResult r = eye.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, false);
        return r != null && r.getHitBlock() != null && r.getHitBlock().equals(target);
    }

    /**
     * На сколько глаз находится СНАРУЖИ плоскости грани (отрицательно: за гранью, то есть кликнуть было нельзя).
     * Для полного куба плоскость берётся точно. У плит, ступенек, заборов и т.п. настоящая грань может быть
     * глубже, поэтому берём самую мягкую плоскость, чтобы не получить ложных срабатываний.
     */
    private static double faceSide(Location eye, Block b, BlockFace face) {
        BoundingBox bb = b.getBoundingBox();
        boolean full = bb.getWidthX() > 0.999 && bb.getHeight() > 0.999 && bb.getWidthZ() > 0.999;
        double eyeC;
        double lo;
        int sign;
        switch (face) {
            case EAST -> { eyeC = eye.getX(); lo = b.getX(); sign = 1; }
            case WEST -> { eyeC = eye.getX(); lo = b.getX(); sign = -1; }
            case UP -> { eyeC = eye.getY(); lo = b.getY(); sign = 1; }
            case DOWN -> { eyeC = eye.getY(); lo = b.getY(); sign = -1; }
            case SOUTH -> { eyeC = eye.getZ(); lo = b.getZ(); sign = 1; }
            case NORTH -> { eyeC = eye.getZ(); lo = b.getZ(); sign = -1; }
            default -> { return Double.MAX_VALUE; }
        }
        double plane = sign > 0 ? (full ? lo + 1 : lo) : (full ? lo : lo + 1);
        return (eyeC - plane) * sign;
    }
}
