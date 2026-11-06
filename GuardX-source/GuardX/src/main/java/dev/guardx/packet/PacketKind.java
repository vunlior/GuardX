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

/** Тип клиентского пакета в том виде, в котором он нужен проверкам GuardX (не зависит от реализации пакетного слоя). */
public enum PacketKind {
    /** Любой пакет движения/поворота (Flying, Position, Look, PositionLook, OnGround). */
    FLYING,
    /** Взмах руки. */
    ANIMATION,
    /** Установка блока или использование предмета. */
    USE_ITEM,
    /** Удар по сущности (старый пакет InteractEntity с действием ATTACK или новый пакет Attack). */
    ATTACK,
    /** Взаимодействие с сущностью без удара (правый клик). */
    INTERACT,
    /** Приседание, бег и т.п. */
    ENTITY_ACTION,
    /** Смена слота хотбара. */
    HELD_ITEM,
    /** Любой другой пакет: нужен только для лимита пакетов в секунду. */
    OTHER
}
