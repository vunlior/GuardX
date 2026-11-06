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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Слой совместимости 1.16 — 1.21+. Всё, что в разных версиях Minecraft/Paper называется по-разному
 * или появилось позже 1.16, обращается сюда, а не напрямую к API.
 */
public final class Compat {

    private Compat() {
    }

    // ------------------------------------------------------------ эффекты зелий

    /** JUMP_BOOST (1.20.5+) или JUMP (старые версии). */
    public static final PotionEffectType JUMP_BOOST = potion("JUMP_BOOST", "JUMP");

    private static PotionEffectType potion(String... names) {
        for (String n : names) {
            try {
                Object o = PotionEffectType.class.getField(n).get(null);
                if (o instanceof PotionEffectType t) return t;
            } catch (Throwable ignored) {
                // такого поля в этой версии нет
            }
        }
        return null;
    }

    public static PotionEffect effect(Player p, PotionEffectType type) {
        return type == null ? null : p.getPotionEffect(type);
    }

    // ------------------------------------------------------------ пинг

    private static final Method PING = findPing();
    private static volatile boolean pingBroken;

    private static Method findPing() {
        try {
            return Player.class.getMethod("getPing");
        } catch (Throwable ex) {
            return null;
        }
    }

    /** Пинг игрока в мс. На старых версиях без Player#getPing читается из серверного объекта, иначе 0. */
    public static int ping(Player p) {
        if (PING != null) {
            try {
                return (Integer) PING.invoke(p);
            } catch (Throwable ignored) {
                return 0;
            }
        }
        if (pingBroken) return 0;
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Field f = handle.getClass().getField("ping");
            return f.getInt(handle);
        } catch (Throwable ex) {
            pingBroken = true;
            return 0;
        }
    }

    // ------------------------------------------------------------ высота мира

    private static final boolean HAS_MIN_HEIGHT = hasMethod(World.class, "getMinHeight");

    public static int minHeight(World w) {
        return HAS_MIN_HEIGHT ? w.getMinHeight() : 0;
    }

    public static int maxHeight(World w) {
        return w.getMaxHeight();
    }

    // ------------------------------------------------------------ блоки

    private static final boolean HAS_COLLISION_SHAPE = hasMethod(Block.class, "getCollisionShape");

    /** Коллизионные боксы блока в локальных координатах (0..1 внутри блока). */
    public static Collection<BoundingBox> collisionBoxes(Block b) {
        if (HAS_COLLISION_SHAPE) {
            return b.getCollisionShape().getBoundingBoxes();
        }
        // Версии без VoxelShape: берём общий бокс блока (он в мировых координатах) и переводим в локальные
        BoundingBox box = b.getBoundingBox();
        if (box == null || box.getVolume() <= 0) return List.of();
        List<BoundingBox> out = new ArrayList<>(1);
        out.add(box.clone().shift(-b.getX(), -b.getY(), -b.getZ()));
        return out;
    }

    /** Аналог Tag.CLIMBABLE по именам: работает на любой версии (лозы разных видов появлялись в 1.16–1.17). */
    public static boolean isClimbable(Material m) {
        String n = m.name();
        return n.equals("LADDER") || n.equals("SCAFFOLDING") || n.equals("VINE")
                || n.endsWith("_VINES") || n.endsWith("_VINES_PLANT") || n.equals("CAVE_VINES");
    }

    public static boolean isBed(Material m) {
        return m.name().endsWith("_BED");
    }

    // ------------------------------------------------------------ скорость передвижения

    private static final java.lang.reflect.Field MOVE_SPEED_ATTR = findMoveSpeedAttr();

    /** Атрибут movement_speed: GENERIC_MOVEMENT_SPEED на старых версиях, MOVEMENT_SPEED на новых. */
    private static java.lang.reflect.Field findMoveSpeedAttr() {
        for (String n : new String[]{"GENERIC_MOVEMENT_SPEED", "MOVEMENT_SPEED"}) {
            try {
                java.lang.reflect.Field f = org.bukkit.attribute.Attribute.class.getField(n);
                if (org.bukkit.attribute.Attribute.class.isInstance(f.get(null))) return f;
            } catch (Throwable ignored) {
                // такого поля в этой версии нет
            }
        }
        return null;
    }

    /**
     * Множитель от атрибута movement_speed (его меняют плагины, моды и предметы;
     * getWalkSpeed() на части версий его не отражает). База атрибута — 0.1.
     * Если атрибут недоступен — 1.0.
     */
    public static double moveSpeedMult(Player p) {
        if (MOVE_SPEED_ATTR == null) return 1.0;
        try {
            Object inst = p.getAttribute((org.bukkit.attribute.Attribute) MOVE_SPEED_ATTR.get(null));
            if (inst == null) return 1.0;
            double v = ((org.bukkit.attribute.AttributeInstance) inst).getValue();
            return Math.max(0.5, Math.min(4.0, v / 0.1));
        } catch (Throwable t) {
            return 1.0;
        }
    }

    // ------------------------------------------------------------ скорость атаки

    private static final org.bukkit.attribute.Attribute ATTACK_SPEED_ATTR = findAttackSpeedAttr();

    /** Атрибут attack_speed: ATTACK_SPEED на новых версиях, GENERIC_ATTACK_SPEED на старых. */
    private static org.bukkit.attribute.Attribute findAttackSpeedAttr() {
        for (String n : new String[]{"ATTACK_SPEED", "GENERIC_ATTACK_SPEED"}) {
            try {
                java.lang.reflect.Field f = org.bukkit.attribute.Attribute.class.getField(n);
                if (org.bukkit.attribute.Attribute.class.isInstance(f.get(null))) {
                    return (org.bukkit.attribute.Attribute) f.get(null);
                }
            } catch (Throwable ignored) {
                // такого поля в этой версии нет
            }
        }
        return null;
    }

    /**
     * Скорость атаки с учётом оружия в руке (атрибут включает модификаторы предмета:
     * меч ~1.6, топор ~0.8-3.1, кулак ~4.0). Это и есть лимит "полных" ударов в секунду.
     * Зачарования на скорость атаки в ванили не влияют. Если атрибут недоступен — 4.0.
     */
    public static double attackSpeed(Player p) {
        if (ATTACK_SPEED_ATTR == null) return 4.0;
        try {
            Object inst = p.getAttribute(ATTACK_SPEED_ATTR);
            if (inst == null) return 4.0;
            return Math.max(0.1, ((org.bukkit.attribute.AttributeInstance) inst).getValue());
        } catch (Throwable t) {
            return 4.0;
        }
    }

    /** Блоки с особой физикой (имена, чтобы не ломаться на версиях, где блока ещё нет). */
    private static final Set<String> SPECIAL = Set.of(
            "COBWEB", "SLIME_BLOCK", "HONEY_BLOCK", "SOUL_SAND", "SOUL_SOIL", "BUBBLE_COLUMN",
            "POWDER_SNOW", "SWEET_BERRY_BUSH", "SCAFFOLDING", "BIG_DRIPLEAF");

    public static boolean isSpecialMaterial(Material m) {
        return SPECIAL.contains(m.name());
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }
}
