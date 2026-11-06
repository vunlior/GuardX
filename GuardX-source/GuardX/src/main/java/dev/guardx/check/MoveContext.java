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

import dev.guardx.data.PlayerData;
import dev.guardx.util.MoveUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/** Всё, что проверкам нужно знать об одном движении игрока. Считается один раз за событие. */
public final class MoveContext {

    public final Player player;
    public final PlayerData data;
    public final Location from;
    public final Location to;
    public final long now;
    public final boolean positionChanged;

    public final double dx;
    public final double dy;
    public final double dz;
    public final double horizontal;

    /** Стоит ли игрок на земле по мнению сервера (по блокам). */
    public boolean serverGround;
    /** Что говорит клиент (может быть подделано). */
    public boolean clientGround;
    /** Проверка просит откатить игрока. */
    public boolean setback;

    private Boolean special;
    private Boolean exempt;
    private Boolean exemptBase;

    public MoveContext(Player player, PlayerData data, Location from, Location to, long now, boolean positionChanged) {
        this.player = player;
        this.data = data;
        this.from = from;
        this.to = to;
        this.now = now;
        this.positionChanged = positionChanged;
        this.dx = to.getX() - from.getX();
        this.dy = to.getY() - from.getY();
        this.dz = to.getZ() - from.getZ();
        this.horizontal = Math.sqrt(dx * dx + dz * dz);
    }

    public boolean nearSpecial() {
        if (special == null) special = computeSpecial();
        return special;
    }

    /**
     * Блочный скан (36 блоков) кэшируется на 250 мс, пока игрок остаётся в той же клетке, а поиск
     * сущностей (лодки, шалкеры) делается только в воздухе и тоже один раз на это окно.
     * Кэш сбрасывается после телепорта (смена мира / позиции).
     */
    private boolean computeSpecial() {
        double x = to.getX(), y = to.getY(), z = to.getZ();
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        long key = ((long) bx & 0x3FFFFFFL) << 38 | ((long) bz & 0x3FFFFFFL) << 12 | ((long) by & 0xFFFL);

        PlayerData d = data;
        boolean fresh = d.specKey == key && now - d.specTime < 250 && d.specTime > d.lastTeleport;
        if (!fresh) {
            d.specKey = key;
            d.specTime = now;
            d.specBlocks = MoveUtil.nearSpecialBlocks(player.getWorld(), bx, by, bz);
            d.specEntitiesKnown = false;
        }
        if (d.specBlocks) return true;
        if (serverGround) return false;
        if (!d.specEntitiesKnown) {
            d.specEntities = MoveUtil.nearBoatOrShulker(player);
            d.specEntitiesKnown = true;
        }
        return d.specEntities;
    }

    /** Игроков в этом состоянии проверять нельзя: у них законно "странное" движение. */
    public boolean exempt() {
        if (exempt == null) exempt = computeExempt(true);
        return exempt;
    }

    /** То же, но без исключений "недавно получил урон / отбрасывание" — нужно проверке Velocity. */
    public boolean exemptBase() {
        if (exemptBase == null) exemptBase = computeExempt(false);
        return exemptBase;
    }

    private boolean computeExempt(boolean combat) {
        Player p = player;
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isGliding()) return true;
        if (p.isInsideVehicle() || p.isDead() || p.isSleeping() || p.isRiptiding()) return true;
        if (now < data.exemptUntil || p.hasPermission("guardx.bypass")) return true;
        if (now - data.joinTime < 3000) return true;
        if (now - data.lastTeleport < 1500) return true;
        if (combat) {
            if (now - data.lastVelocity < 2000) return true;
            if (now - data.lastDamage < 800) return true;
        }
        return p.hasPotionEffect(PotionEffectType.LEVITATION)
                || p.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }
}
