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

import java.util.List;

/**
 * Вызывается в основном потоке, когда на сервер зашёл игрок, уже забанненный за читы на другом сервере сети.
 * Если отменить событие, GuardX не оповестит модераторов (чат, Discord, Telegram) и не выполнит network.join-command.
 */
public class GuardNetworkAlertEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final List<GuardNetworkRecord> records;
    private boolean cancelled;

    public GuardNetworkAlertEvent(Player player, List<GuardNetworkRecord> records) {
        this.player = player;
        this.records = List.copyOf(records);
    }

    public Player getPlayer() { return player; }

    /** Записи о банах с других серверов, новые первыми. */
    public List<GuardNetworkRecord> getRecords() { return records; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
