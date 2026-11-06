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

package dev.guardx.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Вызывается в основном потоке перед выполнением команды наказания.
 * Если отменить событие, команда не выполнится (VL при этом уже сброшены).
 */
public class GuardPunishEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String check;
    private final double vl;
    private final int punishCount;
    private final String command;
    private boolean cancelled;

    public GuardPunishEvent(Player player, String check, double vl, int punishCount, String command) {
        this.player = player;
        this.check = check;
        this.vl = vl;
        this.punishCount = punishCount;
        this.command = command;
    }

    public Player getPlayer() { return player; }

    /** Проверка, из-за которой наказали, либо "multi-check" для суммарного порога. */
    public String getCheck() { return check; }

    public double getVl() { return vl; }

    /** Какое по счёту это наказание игрока с момента запуска сервера. */
    public int getPunishCount() { return punishCount; }

    /** Команда, которая будет выполнена от имени консоли. */
    public String getCommand() { return command; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
