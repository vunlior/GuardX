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

package dev.guardx.packet;

import org.bukkit.entity.Player;

/**
 * Источник клиентских пакетов для проверок GuardX. Реализации:
 * <ul>
 *   <li>{@code PacketEventsBackend} — через плагин PacketEvents (если он установлен);</li>
 *   <li>{@code NativeBackend} — встроенный слой на Netty, не требует сторонних плагинов.</li>
 * </ul>
 */
public interface PacketBackend {

    /** Короткое название для баннера и диагностики, например «PacketEvents 2.14.0» или «Native (Netty)». */
    String name();

    /** Подключается к пакетам и подхватывает уже находящихся на сервере игроков. Бросает исключение, если не получилось. */
    void start();

    /** Отключается и убирает за собой всё, что добавил. Не бросает исключений. */
    void stop();

    /** Игрок зашёл. Встроенному слою здесь нужно внедриться в его канал. */
    default void onJoin(Player player) {
    }

    /** Игрок вышел. */
    default void onQuit(Player player) {
    }
}
