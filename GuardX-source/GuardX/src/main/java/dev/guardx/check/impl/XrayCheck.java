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
import dev.guardx.util.Compat;
import dev.guardx.util.MoveUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-Xray (статистический, только оповещение) + подмена руд на клиенте пойманного читера.
 *
 * Идея: рентген-читер роет туннель так, что "вскрывает" полностью закрытые руды намного чаще,
 * чем обычный игрок. Поэтому считаем два числа за скользящее окно:
 *   rock  - сколько "породы" игрок сломал;
 *   blind - сколько раз ломаемый блок вскрыл ценную руду, которая до этого была
 *           со всех сторон закрыта твёрдыми блоками (то есть увидеть её было нельзя).
 * Если blind / rock выше порога при достаточной выборке, это подозрение.
 *
 * Одна руда ничего не доказывает (бывает везение), поэтому смотрим только на долю
 * и работаем через буфер. Лучше использовать вместе с anti-xray движка Paper.
 *
 * При флаге (spoof-on-flag) все полностью закрытые руды в spoof-radius подменяются
 * для ЭТОГО игрока на обычный камень через sendBlockChange — серверный мир не меняется,
 * читер видит камень вместо руды. Когда руда открывается по-настоящему (сосед сломан,
 * потекла лава и т.п.), клиенту возвращается настоящий блок.
 */
public final class XrayCheck extends Check {

    private Set<Material> ores = Set.of();
    private Set<Material> rock = Set.of();
    private int loadedVersion = -1;

    public XrayCheck(GuardX plugin) {
        super(plugin, "xray");
    }

    @Override
    public void onBreak(Player p, PlayerData d, BlockBreakEvent event, long now) {
        if (p.getGameMode() != GameMode.SURVIVAL || plugin.bypass(p)) return;
        syncMaterials();

        Block b = event.getBlock();
        Material type = b.getType();

        if (rock.contains(type)) {
            double r = d.addBuffer("xray-rock", 1.0);
            // Скользящее окно: когда набралось достаточно, делим счётчики пополам
            double window = num("window", 400);
            if (r > window) {
                d.setBuffer("xray-rock", r / 2);
                d.setBuffer("xray-blind", d.addBuffer("xray-blind", 0) / 2);
            }
        }

        // Какие соседние блоки этот слом вскрывает? Ищем ценные руды, закрытые со всех других сторон.
        boolean found = false;
        for (BlockFace face : FACES) {
            Block n = b.getRelative(face);
            if (!ores.contains(n.getType())) continue;
            if (isHidden(n, b)) {
                found = true;
                d.addBuffer("xray-blind", 1.0);
            }
        }
        if (!found) {
            restoreAround(p, d, b);
            return;
        }

        double rockCount = d.addBuffer("xray-rock", 0);
        double blind = d.addBuffer("xray-blind", 0);
        if (rockCount < num("min-rock", 120)) {
            restoreAround(p, d, b);
            return;
        }

        double ratio = blind / rockCount;
        double maxRatio = num("max-ratio", 0.04);
        if (ratio > maxRatio) {
            double buf = d.addBuffer("xray", 1.0);
            if (buf >= num("buffer", 3)) {
                d.setBuffer("xray", 0);
                // Сброс счётчиков, чтобы не флагать по одним и тем же данным
                d.setBuffer("xray-rock", 0);
                d.setBuffer("xray-blind", 0);
                flag(p, d, t("f-xray",
                        "ratio", Lang.n(ratio * 100, 1),
                        "max", Lang.n(maxRatio * 100, 1),
                        "rock", (int) rockCount), 1.0);
                if (bool("spoof-on-flag", true)) spoofOres(p, d, now);
            }
        } else {
            d.addBuffer("xray", -0.25);
        }
        restoreAround(p, d, b);
    }

    // ------------------------------------------------------------ подмена руд на клиенте

    /** Руда "скрыта", если все её стороны, кроме только что сломанного блока, непрозрачны. */
    private boolean isHidden(Block ore, Block broken) {
        for (BlockFace face : FACES) {
            Block n = ore.getRelative(face);
            if (n.getX() == broken.getX() && n.getY() == broken.getY() && n.getZ() == broken.getZ()) continue;
            if (!n.getType().isOccluding()) return false; // воздух, вода, лава, пещера: видно
        }
        return true;
    }

    /** Руда полностью закрыта со всех 6 сторон — игрок её физически не видит. */
    private boolean isEnclosed(Block ore) {
        for (BlockFace face : FACES) {
            if (!ore.getRelative(face).getType().isOccluding()) return false;
        }
        return true;
    }

    /** Подменяет закрытые руды в радиусе на "камень" для клиента игрока. */
    private void spoofOres(Player p, PlayerData d, long now) {
        long respoofMs = (long) (num("spoof-respoof-seconds", 120) * 1000);
        if (now - d.xraySpoofAt < respoofMs) return;
        d.xraySpoofAt = now;

        int radius = integer("spoof-radius", 14);
        Material fake = fakeMaterial(p);
        World w = p.getWorld();
        Set<Long> set = d.xraySpoof.computeIfAbsent(w.getUID(), k -> new HashSet<>());

        Location loc = p.getLocation();
        int cx = loc.getBlockX(), cy = loc.getBlockY(), cz = loc.getBlockZ();
        int minY = Math.max(Compat.minHeight(w), cy - radius);
        int maxY = Math.min(Compat.maxHeight(w) - 1, cy + radius);
        int added = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!ores.contains(b.getType()) || !isEnclosed(b)) continue;
                    long key = MoveUtil.blockKey(b);
                    if (set.contains(key)) continue;
                    p.sendBlockChange(b.getLocation(), fake.createBlockData());
                    set.add(key);
                    added++;
                }
            }
        }
        plugin.getLogger().info("xray-spoof " + p.getName() + ": " + added + " ores hidden client-side");
    }

    /** После слома блока: если соседние подменённые руды теперь открыты — возвращаем настоящие. */
    private void restoreAround(Player p, PlayerData d, Block broken) {
        if (d.xraySpoof.isEmpty()) return;
        Set<Long> set = d.xraySpoof.get(broken.getWorld().getUID());
        if (set == null) return;
        World w = broken.getWorld();
        for (BlockFace face : FACES) {
            Block n = broken.getRelative(face);
            long key = MoveUtil.blockKey(n);
            if (set.contains(key) && isExposed(n)) {
                p.sendBlockChange(n.getLocation(), n.getType().createBlockData());
                set.remove(key);
            }
        }
        if (set.isEmpty()) d.xraySpoof.remove(w.getUID());
    }

    private boolean isExposed(Block ore) {
        for (BlockFace face : FACES) {
            if (!ore.getRelative(face).getType().isOccluding()) return true;
        }
        return false;
    }

    /** Раз в секунду: подметаем подменённые блоки — вернувшиеся "открытыми" и слишком дальние. */
    @Override
    public void onTick(Player p, PlayerData d, long now) {
        if (d.xraySpoof.isEmpty() || now - d.xraySpoofScanAt < 1000) return;
        d.xraySpoofScanAt = now;

        double far2 = num("spoof-radius", 14) * 2.0;
        far2 *= far2;
        Location pl = p.getLocation();

        for (Map.Entry<UUID, Set<Long>> e : new ArrayList<>(d.xraySpoof.entrySet())) {
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) { e.getValue().clear(); continue; }
            Iterator<Long> it = e.getValue().iterator();
            while (it.hasNext()) {
                Block b = unpack(w, it.next());
                if (b == null) { it.remove(); continue; }
                double dx = b.getX() + 0.5 - pl.getX();
                double dy = b.getY() + 0.5 - pl.getY();
                double dz = b.getZ() + 0.5 - pl.getZ();
                if (dx * dx + dy * dy + dz * dz > far2 || isExposed(b)) {
                    p.sendBlockChange(b.getLocation(), b.getType().createBlockData());
                    it.remove();
                }
            }
            if (e.getValue().isEmpty()) d.xraySpoof.remove(e.getKey());
        }
    }

    /** Обратная упаковка MoveUtil.blockKey: x(26) << 38 | z(26) << 12 | y(12). */
    private static Block unpack(World w, long key) {
        long x = key >> 38;
        x = (x << 38) >> 38;
        long z = (key >> 12) & 0x3FFFFFFL;
        z = (z << 38) >> 38;
        long y = key & 0xFFFL;
        y = (y << 52) >> 52;
        int xi = (int) x, yi = (int) y, zi = (int) z;
        if (yi < Compat.minHeight(w) || yi >= Compat.maxHeight(w) || !w.isChunkLoaded(xi >> 4, zi >> 4)) return null;
        return w.getBlockAt(xi, yi, zi);
    }

    private Material fakeMaterial(Player p) {
        String name = plugin.getConfig().getString("checks.xray.spoof-block", "");
        if (name != null && !name.isBlank()) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock()) return m;
        }
        return switch (p.getWorld().getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.STONE;
        };
    }

    private void syncMaterials() {
        int v = plugin.configVersion();
        if (v == loadedVersion) return;
        loadedVersion = v;
        ores = load("ores", List.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "EMERALD_ORE",
                "DEEPSLATE_EMERALD_ORE", "ANCIENT_DEBRIS", "GOLD_ORE", "DEEPSLATE_GOLD_ORE"));
        rock = load("rock", List.of("STONE", "DEEPSLATE", "NETHERRACK", "ANDESITE", "DIORITE",
                "GRANITE", "TUFF", "BLACKSTONE", "BASALT"));
    }

    /** Material.matchMaterial вернёт null для блоков, которых нет в этой версии (например DEEPSLATE на 1.16). */
    private Set<Material> load(String key, List<String> def) {
        List<String> names = plugin.getConfig().isList("checks.xray." + key)
                ? plugin.getConfig().getStringList("checks.xray." + key) : def;
        Set<Material> out = new HashSet<>();
        for (String name : names) {
            Material m = Material.matchMaterial(name);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
}
