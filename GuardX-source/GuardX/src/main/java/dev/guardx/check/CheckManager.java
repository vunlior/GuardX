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
import dev.guardx.check.impl.ElytraCheck;
import dev.guardx.check.impl.FlyCheck;
import dev.guardx.check.impl.FoodSprintCheck;
import dev.guardx.check.impl.JesusCheck;
import dev.guardx.check.impl.CpsCheck;
import dev.guardx.check.impl.KillAuraCheck;
import dev.guardx.check.impl.NoFallCheck;
import dev.guardx.check.impl.NoSlowCheck;
import dev.guardx.check.impl.NukerCheck;
import dev.guardx.check.impl.ReachCheck;
import dev.guardx.check.impl.ScaffoldCheck;
import dev.guardx.check.impl.ScaffoldFoldingCheck;
import dev.guardx.check.impl.SpeedCheck;
import dev.guardx.check.impl.StepCheck;
import dev.guardx.check.impl.VelocityCheck;
import dev.guardx.data.PlayerData;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.BlockBreakEvent;
import dev.guardx.check.impl.BlockReachCheck;
import dev.guardx.check.impl.FastBowCheck;
import dev.guardx.check.impl.InvMoveCheck;
import dev.guardx.check.impl.InventoryCheck;
import dev.guardx.check.impl.MomentumCheck;
import dev.guardx.check.impl.MultiActionCheck;
import dev.guardx.check.impl.OmniSprintCheck;
import dev.guardx.check.impl.SpiderCheck;
import dev.guardx.check.impl.StrafeCheck;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import dev.guardx.check.impl.FastBreakCheck;
import dev.guardx.check.impl.CriticalsCheck;
import dev.guardx.check.impl.FastUseCheck;
import dev.guardx.check.impl.PhaseCheck;
import dev.guardx.check.impl.XrayCheck;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import dev.guardx.check.impl.NoRotateCheck;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Проверки на событиях Bukkit (им нужен мир: блоки, позиции сущностей).
 * Timer, AutoClicker, NoSwing и BadPackets живут в пакетном слое: см. dev.guardx.packet.
 */
public final class CheckManager {

    private final GuardX plugin;
    private final List<Check> checks = new CopyOnWriteArrayList<>();
    /** Идентификаторы встроенных проверок: их нельзя снять через API. */
    private final Set<String> builtin = new HashSet<>();
    private final Set<String> broken = new HashSet<>();
    private long calls;
    private long totalNanos;
    private long maxNanos;

    public CheckManager(GuardX plugin) {
        this.plugin = plugin;
        checks.add(new SpeedCheck(plugin));
        checks.add(new FlyCheck(plugin));
        checks.add(new NoFallCheck(plugin));
        checks.add(new ReachCheck(plugin));
        checks.add(new KillAuraCheck(plugin));
        checks.add(new CpsCheck(plugin));
        checks.add(new ScaffoldCheck(plugin));
        checks.add(new ScaffoldFoldingCheck(plugin));
        checks.add(new StepCheck(plugin));
        checks.add(new JesusCheck(plugin));
        checks.add(new NoSlowCheck(plugin));
        checks.add(new VelocityCheck(plugin));
        checks.add(new NukerCheck(plugin));
        checks.add(new MomentumCheck(plugin));
        checks.add(new StrafeCheck(plugin));
        checks.add(new OmniSprintCheck(plugin));
        checks.add(new SpiderCheck(plugin));
        checks.add(new InvMoveCheck(plugin));
        checks.add(new MultiActionCheck(plugin));
        checks.add(new FastBowCheck(plugin));
        checks.add(new BlockReachCheck(plugin));
        checks.add(new InventoryCheck(plugin));
        checks.add(new FastBreakCheck(plugin));
        checks.add(new PhaseCheck(plugin));
        checks.add(new CriticalsCheck(plugin));
        checks.add(new FastUseCheck(plugin));
        checks.add(new ElytraCheck(plugin));
        checks.add(new FoodSprintCheck(plugin));
        checks.add(new XrayCheck(plugin));
        checks.add(new NoRotateCheck(plugin));
        for (Check c : checks) builtin.add(c.id());
    }

    /**
     * Единое условие запуска проверки для игрока: включена в конфиге и не отключена слоем (config: layers).
     * Все обработчики ниже спрашивают именно его.
     */
    private boolean active(Check c, Player p) {
        return c.enabled() && !plugin.layers().skips(p, c.id());
    }

    /** Добавляет проверку (например, из другого плагина через GuardAPI). @return false, если такой id уже занят или он некорректен */
    public boolean register(Check c) {
        if (c == null || !c.id().matches("[a-z0-9_-]{1,32}") || find(c.id()) != null) return false;
        return checks.add(c);
    }

    /** Снимает добавленную проверку. Встроенные снять нельзя. */
    public boolean unregister(String id) {
        if (id == null || builtin.contains(id.toLowerCase(java.util.Locale.ROOT))) return false;
        broken.remove(id);
        return checks.removeIf(c -> c.id().equalsIgnoreCase(id));
    }

    public Check find(String id) {
        for (Check c : checks) if (c.id().equalsIgnoreCase(id)) return c;
        return null;
    }

    public List<Check> all() {
        return List.copyOf(checks);
    }

    private void fail(Check c, Throwable ex) {
        // Одна сломанная проверка не должна ронять остальные. Пишем в лог один раз.
        if (broken.add(c.id())) {
            plugin.getLogger().log(Level.SEVERE, plugin.lang().plain("error-check", "check", c.id()), ex);
        }
    }

    public void handleMove(MoveContext ctx) {
        if (plugin.lowTps()) return; // сервер лагает — не наказываем игроков за чужие лаги
        long started = System.nanoTime();
        for (Check c : checks) {
            if (!active(c, ctx.player)) continue;
            try {
                c.onMove(ctx);
            } catch (Throwable ex) {
                fail(c, ex);
            }
        }
        recordTime(started);
    }

    public void handleVelocity(Player p, PlayerData d, Vector vel, long now) {
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try {
                c.onVelocity(p, d, vel, now);
            } catch (Throwable ex) {
                fail(c, ex);
            }
        }
    }

    public boolean handleDig(Player p, PlayerData d, Block block, long now) {
        boolean cancel = false;
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try {
                cancel |= c.onDig(p, d, block, now);
            } catch (Throwable ex) {
                fail(c, ex);
            }
        }
        return cancel;
    }

    public void handleTick(Player p, PlayerData d, long now) {
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try {
                c.onTick(p, d, now);
            } catch (Throwable ex) {
                fail(c, ex);
            }
        }
    }

    public void handleInventoryClick(Player p, PlayerData d, InventoryClickEvent event, long now) {
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try { c.onInventoryClick(p, d, event, now); }
            catch (Throwable ex) { fail(c, ex); }
        }
    }

    public void handleInteract(Player p, PlayerData d, PlayerInteractEvent event, long now) {
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try { c.onInteract(p, d, event, now); }
            catch (Throwable ex) { fail(c, ex); }
        }
    }

    public void handleTeleport(Player p, PlayerData d, PlayerTeleportEvent event, long now) {
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try { c.onTeleport(p, d, event, now); }
            catch (Throwable ex) { fail(c, ex); }
        }
    }

    public boolean handleShoot(Player p, PlayerData d, EntityShootBowEvent event, long now) {
        boolean cancel = false;
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try { cancel |= c.onShoot(p, d, event, now); }
            catch (Throwable ex) { fail(c, ex); }
        }
        return cancel;
    }

    public void handleBreak(Player p, PlayerData d, BlockBreakEvent event, long now) {
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try { c.onBreak(p, d, event, now); }
            catch (Throwable ex) { fail(c, ex); }
        }
    }

    private void recordTime(long started) {
        long elapsed = System.nanoTime() - started;
        calls++;
        totalNanos += elapsed;
        if (elapsed > maxNanos) maxNanos = elapsed;
    }

    public long calls() { return calls; }
    public double averageMicros() { return calls == 0 ? 0.0 : (totalNanos / (double) calls) / 1000.0; }
    public double maxMicros() { return maxNanos / 1000.0; }
    public int brokenCount() { return broken.size(); }
    public Set<String> brokenChecks() { return Set.copyOf(broken); }

    public boolean handleAttack(Player p, PlayerData d, Entity target, long now) {
        if (plugin.lowTps()) return false;
        boolean cancel = false;
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try {
                cancel |= c.onAttack(p, d, target, now);
            } catch (Throwable ex) {
                fail(c, ex);
            }
        }
        return cancel;
    }

    public boolean handlePlace(Player p, PlayerData d, Block placed, Block against, long now) {
        boolean cancel = false;
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try {
                cancel |= c.onPlace(p, d, placed, against, now);
            } catch (Throwable ex) {
                fail(c, ex);
            }
        }
        return cancel;
    }

    public boolean handleConsume(Player p, PlayerData d, PlayerItemConsumeEvent event, long now) {
        boolean cancel = false;
        for (Check c : checks) {
            if (!active(c, p)) continue;
            try { cancel |= c.onConsume(p, d, event, now); }
            catch (Throwable ex) { fail(c, ex); }
        }
        return cancel;
    }
}
