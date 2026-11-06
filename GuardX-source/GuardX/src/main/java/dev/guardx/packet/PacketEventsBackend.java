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

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.UUID;

/**
 * Пакетный слой через плагин PacketEvents (необязательный). Этот класс — единственное место,
 * которое ссылается на классы PacketEvents; он загружается, только если PacketEvents установлен и выбран.
 */
final class PacketEventsBackend implements PacketBackend, PacketListener {

    /** Тип пакета «атака» появился в новых версиях Minecraft; ищем его по имени, чтобы не зависеть от версии PacketEvents. */
    private static final PacketTypeCommon ATTACK_TYPE = lookupAttackType();

    private final PacketProcessor processor;
    private PacketListenerCommon handle;

    PacketEventsBackend(PacketProcessor processor) {
        this.processor = processor;
    }

    private static PacketTypeCommon lookupAttackType() {
        try {
            return PacketType.Play.Client.valueOf("ATTACK");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public String name() {
        String v;
        try {
            v = String.valueOf(PacketEvents.getAPI().getVersion());
        } catch (Throwable ex) {
            v = "?";
        }
        return "PacketEvents " + v;
    }

    @Override
    public void start() {
        // PacketListener -> PacketListenerCommon: registerListener принимает только обёртку
        handle = asAbstract(PacketListenerPriority.NORMAL);
        PacketEvents.getAPI().getEventManager().registerListener(handle);
    }

    @Override
    public void stop() {
        if (handle == null) return;
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(handle);
        } catch (Throwable ignored) {
            // PacketEvents уже выключается — снимать нечего
        }
        handle = null;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        var type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Client)) return;

        User user = event.getUser();
        if (user == null) return;
        UUID id = user.getUUID();
        if (id == null) return; // игрок ещё не вошёл в игру

        PacketFrame frame = frame(event, type);
        if (processor.handle(id, frame)) event.setCancelled(true);
    }

    private PacketFrame frame(PacketReceiveEvent event, PacketTypeCommon type) {
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            PacketFrame f = new PacketFrame(PacketKind.FLYING);
            if (processor.needsFlyingData()) {
                WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
                boolean pos = w.hasPositionChanged();
                boolean rot = w.hasRotationChanged();
                double x = 0, y = 0, z = 0;
                float yaw = 0, pitch = 0;
                if (pos || rot) {
                    var loc = w.getLocation();
                    x = loc.getX();
                    y = loc.getY();
                    z = loc.getZ();
                    yaw = loc.getYaw();
                    pitch = loc.getPitch();
                }
                f.flying(pos, x, y, z, rot, yaw, pitch);
            }
            return f;
        }
        if (type == PacketType.Play.Client.ANIMATION) return new PacketFrame(PacketKind.ANIMATION);
        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT || type == PacketType.Play.Client.USE_ITEM) {
            return new PacketFrame(PacketKind.USE_ITEM);
        }
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
            if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return new PacketFrame(PacketKind.ATTACK).entity(w.getEntityId());
            }
            return new PacketFrame(PacketKind.INTERACT);
        }
        if (ATTACK_TYPE != null && type == ATTACK_TYPE) {
            return new PacketFrame(PacketKind.ATTACK); // id цели не читаем: «был удар» для NoSwing учитывается
        }
        if (type == PacketType.Play.Client.ENTITY_ACTION) return new PacketFrame(PacketKind.ENTITY_ACTION);
        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            return new PacketFrame(PacketKind.HELD_ITEM).slot(new WrapperPlayClientHeldItemChange(event).getSlot());
        }
        return PacketFrame.other();
    }
}
