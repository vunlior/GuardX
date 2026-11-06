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

/**
 * Нейтральное описание одного клиентского пакета. Его заполняют бэкенды (PacketEvents или встроенный),
 * а проверки в {@link PacketProcessor} не знают, откуда пакет пришёл.
 */
public final class PacketFrame {

    /** Общий неизменяемый кадр для пакетов, которые нужны только счётчику флуда. */
    static final PacketFrame OTHER = new PacketFrame(PacketKind.OTHER);

    final PacketKind kind;

    // FLYING
    boolean hasPos;
    boolean hasRot;
    double x, y, z;
    float yaw, pitch;
    /** Заполнены ли поля FLYING (бэкенд читает их только если они нужны настроенным проверкам). */
    boolean flyingRead;

    /** ATTACK/INTERACT: id сущности, либо -1, если прочитать его не удалось. */
    int entityId = -1;

    /** HELD_ITEM: выбранный слот. */
    int slot;

    public PacketFrame(PacketKind kind) {
        this.kind = kind;
    }

    public static PacketFrame other() {
        return OTHER;
    }

    public static PacketFrame of(PacketKind kind) {
        return kind == PacketKind.OTHER ? OTHER : new PacketFrame(kind);
    }

    public PacketFrame flying(boolean hasPos, double x, double y, double z, boolean hasRot, float yaw, float pitch) {
        this.hasPos = hasPos;
        this.x = x;
        this.y = y;
        this.z = z;
        this.hasRot = hasRot;
        this.yaw = yaw;
        this.pitch = pitch;
        this.flyingRead = true;
        return this;
    }

    public PacketFrame entity(int id) {
        this.entityId = id;
        return this;
    }

    public PacketFrame slot(int slot) {
        this.slot = slot;
        return this;
    }
}
