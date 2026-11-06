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

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.Set;

/** Серверные проверки "стоит ли игрок на земле" и подобные. Не доверяем флагу onGround от клиента. */
public final class MoveUtil {

    private static final Set<Material> ICE = EnumSet.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    private MoveUtil() {
    }

    /** Блок, только если чанк уже загружен (чтобы не грузить чанки синхронно). */
    public static Block loadedBlock(World w, int x, int y, int z) {
        if (y < Compat.minHeight(w) || y >= Compat.maxHeight(w)) return null;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        return w.getBlockAt(x, y, z);
    }

    /** Есть ли твёрдая поверхность под ногами (полоса 0.5 блока под игроком). */
    public static boolean onGround(World w, double x, double y, double z) {
        return onGround(w, x, y, z, 0.5);
    }

    /**
     * Есть ли твёрдая поверхность прямо под ногами (полоса height блоков).
     * Чем тоньше полоса, тем точнее: при толстой полосе игрок, парящий в 0.4 блока над землёй,
     * считался бы "стоящим на земле", и Fly/NoFall его не видели.
     */
    public static boolean onGround(World w, double x, double y, double z, double height) {
        BoundingBox slab = new BoundingBox(x - 0.3, y - height, z - 0.3, x + 0.3, y, z + 0.3);
        return collides(w, slab);
    }

    /** Уникальный числовой ключ блока (для сравнения "тот же ли это блок"). */
    public static long blockKey(org.bukkit.block.Block b) {
        return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | ((long) b.getY() & 0xFFFL);
    }

    /** Единичный вектор взгляда по yaw/pitch (та же формула, что у Location#getDirection, но без аллокации Location). */
    public static Vector direction(float yaw, float pitch) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double xz = Math.cos(pr);
        return new Vector(-xz * Math.sin(yr), -Math.sin(pr), xz * Math.cos(yr));
    }

    public static boolean collides(World w, BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        // -1: у заборов, стен и калиток коллизия выше 1 блока (1.5), поэтому игрок на них стоит
        // над блоком, чей bounding box выходит в клетку выше. Без этого такие блоки не находились
        // и игрок на заборе получал ложные флаги Fly.
        int minY = (int) Math.floor(box.getMinY()) - 1;
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = loadedBlock(w, x, y, z);
                    if (b == null || b.isPassable()) continue;
                    for (BoundingBox bb : Compat.collisionBoxes(b)) {
                        bb.shift(x, y, z);
                        if (bb.overlaps(box)) return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isIceUnder(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (double dy : new double[]{-0.5, -1.5}) {
            Block b = loadedBlock(w, bx, (int) Math.floor(y + dy), bz);
            if (b != null && ICE.contains(b.getType())) return true;
        }
        return false;
    }

    private static boolean special(Block b) {
        Material m = b.getType();
        if (b.isLiquid()) return true;
        if (Compat.isClimbable(m) || Compat.isBed(m)) return true;
        return Compat.isSpecialMaterial(m);
    }

    /**
     * Рядом вода, лестница, паутина, слайм и т.п. — там обычная физика не работает,
     * поэтому проверки движения пропускаем. Лодки и шалкеры тоже считаются "землёй".
     */
    public static boolean nearSpecial(Player p, double x, double y, double z, boolean checkEntities) {
        if (nearSpecialBlocks(p.getWorld(), (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) return true;
        return checkEntities && nearBoatOrShulker(p);
    }

    /** Блочная часть nearSpecial: 36 блоков вокруг клетки (bx, by, bz). Результат можно кэшировать по клетке. */
    public static boolean nearSpecialBlocks(World w, int bx, int by, int bz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = loadedBlock(w, bx + dx, by + dy, bz + dz);
                    if (b != null && special(b)) return true;
                }
            }
        }
        return false;
    }

    /** Сущностная часть nearSpecial: дорогой поиск соседей, вызывать только когда нужно. */
    public static boolean nearBoatOrShulker(Player p) {
        for (Entity e : p.getNearbyEntities(1.2, 1.6, 1.2)) {
            if (e instanceof Boat || e instanceof Shulker) return true;
        }
        return false;
    }

    /** Расстояние от точки до ближайшей точки bounding box (0, если точка внутри). */
    public static double distanceToBox(double px, double py, double pz, BoundingBox b) {
        double dx = Math.max(Math.max(b.getMinX() - px, 0.0), px - b.getMaxX());
        double dy = Math.max(Math.max(b.getMinY() - py, 0.0), py - b.getMaxY());
        double dz = Math.max(Math.max(b.getMinZ() - pz, 0.0), pz - b.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
