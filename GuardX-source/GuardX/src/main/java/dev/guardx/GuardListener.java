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

package dev.guardx;

import dev.guardx.check.MoveContext;
import dev.guardx.data.PlayerData;
import dev.guardx.util.MoveUtil;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/** Собирает данные из событий сервера и передаёт их проверкам. */
public final class GuardListener implements Listener {

    private final GuardX plugin;

    public GuardListener(GuardX plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ движение

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;

        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();

        // Запоминаем предыдущий и текущий поворот (нужны проверке хитбокса KillAura)
        d.prevYaw = d.yaw;
        d.prevPitch = d.pitch;
        d.yaw = to.getYaw();
        d.pitch = to.getPitch();

        boolean pos = from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ();
        MoveContext c = new MoveContext(p, d, from, to, now, pos);

        if (pos) {
            c.serverGround = MoveUtil.onGround(to.getWorld(), to.getX(), to.getY(), to.getZ(), plugin.groundHeight());
            c.clientGround = p.isOnGround();
            if (MoveUtil.isIceUnder(to.getWorld(), to.getX(), to.getY(), to.getZ())) {
                d.lastIce = now;
            }
        }

        plugin.checks().handleMove(c);

        // Откат: возвращаем на последнюю безопасную позицию (или на предыдущую, если её нет)
        if (c.setback) {
            Location back = (d.lastSafe != null && to.getWorld().equals(d.lastSafe.getWorld()))
                    ? d.lastSafe.clone() : from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            d.ignoreTeleportUntil = now + 200;
            d.resetMovement();
            e.setTo(back);
            return;
        }

        if (!pos) return;

        // Обновляем состояние ПОСЛЕ проверок, чтобы они видели предыдущий тик
        if (c.serverGround || c.nearSpecial()) {
            d.airTicks = 0;
            d.groundY = to.getY();
        } else {
            d.airTicks++;
        }

        // Безопасной считаем позицию только пока игрок не "подозрителен"
        if (c.serverGround && !c.exempt() && !c.nearSpecial()
                && d.buffer("speed") < 1 && d.buffer("fly") < 1 && d.buffer("nofall") < 1) {
            d.lastSafe = to.clone();
        }
        d.lastDeltaY = c.dy;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        PlayerData d = plugin.data(e.getPlayer());
        long now = System.currentTimeMillis();
        if (now <= d.ignoreTeleportUntil) return; // это наш собственный откат

        d.lastTeleport = now;
        d.lastSafe = e.getTo().clone();
        plugin.checks().handleTeleport(e.getPlayer(), d, e, now);
        d.resetMovement();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.lastTeleport = System.currentTimeMillis();
        d.lastSafe = e.getRespawnLocation().clone();
        d.resetMovement();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.lastTeleport = System.currentTimeMillis();
        d.lastSafe = e.getPlayer().getLocation().clone();
        d.resetMovement();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        long now = System.currentTimeMillis();
        d.lastVelocity = now;
        if (!e.isCancelled()) {
            plugin.checks().handleVelocity(e.getPlayer(), d, e.getVelocity(), now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            plugin.data(p).lastDamage = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------ бой

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        Entity target = e.getEntity();
        PlayerData d = plugin.data(p);
        if (plugin.checks().handleAttack(p, d, target, System.currentTimeMillis())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ блоки

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDig(BlockDamageEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        long now = System.currentTimeMillis();
        d.lastDig = now;
        d.lastDigKey = MoveUtil.blockKey(e.getBlock());
        if (plugin.checks().handleDig(p, d, e.getBlock(), now)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        long now = System.currentTimeMillis();
        d.lastDig = now;
        if (!e.isCancelled()) plugin.checks().handleBreak(e.getPlayer(), d, e, now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        plugin.checks().handleInteract(p, plugin.data(p), e, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (plugin.checks().handleShoot(p, plugin.data(p), e, System.currentTimeMillis())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (plugin.checks().handleConsume(p, plugin.data(p), e, System.currentTimeMillis())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        plugin.checks().handleInventoryClick(p, plugin.data(p), e, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (plugin.checks().handlePlace(p, d, e.getBlockPlaced(), e.getBlockAgainst(), System.currentTimeMillis())) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ вход / выход

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        d.lastSafe = e.getPlayer().getLocation().clone();
        d.yaw = d.prevYaw = e.getPlayer().getLocation().getYaw();
        d.pitch = d.prevPitch = e.getPlayer().getLocation().getPitch();
        if (plugin.packetLayer() != null) plugin.packetLayer().onJoin(e.getPlayer());
        plugin.network().onJoin(e.getPlayer());
        plugin.network().digestOnJoin(e.getPlayer());
    }

    /** Баны и разбаны администраторов для сети серверов. Нужно право guardx.network.ban, иначе любой игрок мог бы подделать запись. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStaffCommand(PlayerCommandPreprocessEvent e) {
        if (!e.getPlayer().hasPermission("guardx.network.ban")) return;
        plugin.network().onAdminCommand(e.getPlayer().getName(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        plugin.network().onAdminCommand("CONSOLE", e.getCommand());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (plugin.packetLayer() != null) plugin.packetLayer().onQuit(e.getPlayer());
        plugin.removeData(e.getPlayer().getUniqueId());
        plugin.violations().clear(e.getPlayer().getUniqueId());
        plugin.reports().forget(e.getPlayer().getUniqueId());
    }
}
