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

package dev.guardx.check;

import dev.guardx.GuardX;
import dev.guardx.data.PlayerData;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

/** Базовый класс проверки на событиях Bukkit. Подклассы переопределяют только нужные события. */
public abstract class Check {

    protected final GuardX plugin;
    private final String id;

    protected Check(GuardX plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    public String id() {
        return id;
    }

    // Кэш значений конфига: раньше каждый вызов собирал строку и ходил в getConfig().
    // Кэш сбрасывается при /guardx reload (plugin.configVersion()).
    private final java.util.Map<String, Double> nums = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> ints = new java.util.HashMap<>();
    private final java.util.Map<String, Boolean> bools = new java.util.HashMap<>();
    private Boolean enabledCache;
    private int cacheVersion = -1;

    private void syncCache() {
        int v = plugin.configVersion();
        if (v != cacheVersion) {
            nums.clear();
            ints.clear();
            bools.clear();
            enabledCache = null;
            cacheVersion = v;
        }
    }

    public boolean enabled() {
        syncCache();
        if (enabledCache == null) {
            enabledCache = plugin.getConfig().getBoolean("checks." + id + ".enabled", true);
        }
        return enabledCache;
    }

    protected double num(String key, double def) {
        syncCache();
        Double v = nums.get(key);
        if (v == null) {
            v = plugin.getConfig().getDouble("checks." + id + "." + key, def);
            nums.put(key, v);
        }
        return v;
    }

    protected int integer(String key, int def) {
        syncCache();
        Integer v = ints.get(key);
        if (v == null) {
            v = plugin.getConfig().getInt("checks." + id + "." + key, def);
            ints.put(key, v);
        }
        return v;
    }

    protected boolean bool(String key, boolean def) {
        syncCache();
        Boolean v = bools.get(key);
        if (v == null) {
            v = plugin.getConfig().getBoolean("checks." + id + "." + key, def);
            bools.put(key, v);
        }
        return v;
    }

    /** Локализованный текст без цветов (для описания нарушения). */
    protected String t(String key, Object... kv) {
        return plugin.lang().plain(key, kv);
    }

    protected void flag(Player p, PlayerData d, String info, double vl) {
        plugin.violations().flag(p, d, id, info, vl);
    }

    /** Нарушение движения: фиксируем и просим откатить игрока. */
    protected void flagMove(MoveContext c, String info, double vl) {
        flag(c.player, c.data, info, vl);
        if (plugin.getConfig().getBoolean("setback", true)) {
            c.setback = true;
        }
    }

    public void onMove(MoveContext c) {
    }

    /** Сервер собирается придать игроку скорость (отбрасывание, плагины). */
    public void onVelocity(Player p, PlayerData d, Vector vel, long now) {
    }

    /** Игрок начал ломать блок. @return true, если действие нужно отменить */
    public boolean onDig(Player p, PlayerData d, Block block, long now) {
        return false;
    }

    /** Раз в тик для каждого игрока — для проверок, которым нужен таймер, а не событие. */
    public void onTick(Player p, PlayerData d, long now) {
    }

    /** Клик по интерфейсу инвентаря. */
    public void onInventoryClick(Player p, PlayerData d, InventoryClickEvent event, long now) {
    }

    /** Игрок нажал правую/левую кнопку мыши (предмет в руке, блок, воздух). */
    public void onInteract(Player p, PlayerData d, PlayerInteractEvent event, long now) {
    }

    /** Игрок выстрелил из лука. @return true, если выстрел нужно отменить */
    public boolean onShoot(Player p, PlayerData d, EntityShootBowEvent event, long now) {
        return false;
    }

    /** Завершённое разрушение блока. */
    public void onBreak(Player p, PlayerData d, BlockBreakEvent event, long now) {
    }

    /** @return true, если удар нужно отменить */
    public boolean onAttack(Player p, PlayerData d, Entity target, long now) {
        return false;
    }

    /** @return true, если установку блока нужно отменить */
    public boolean onPlace(Player p, PlayerData d, Block placed, Block against, long now) {
        return false;
    }

    /** Сервер телепортировал игрока (не наш собственный откат). Нужен проверкам, которые сверяют ответ клиента. */
    public void onTeleport(Player p, PlayerData d, PlayerTeleportEvent event, long now) {
    }

    /** Игрок съел/выпил предмет. @return true, если нужно отменить */
    public boolean onConsume(Player p, PlayerData d, PlayerItemConsumeEvent event, long now) {
        return false;
    }
}
