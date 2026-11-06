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

import dev.guardx.GuardX;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Выбор и запуск пакетного слоя. PacketEvents необязателен: если его нет, работает встроенный слой.
 * Режим задаёт {@code packets.backend} в config.yml: auto (по умолчанию), packetevents, native, off.
 */
public final class PacketLayer {

    private final GuardX plugin;
    private final PacketProcessor processor;
    private PacketBackend backend;

    public PacketLayer(GuardX plugin) {
        this.plugin = plugin;
        this.processor = new PacketProcessor(plugin);
    }

    /** Запускает подходящий слой. Если ни один не запустился, GuardX продолжает работать без пакетных проверок. */
    public void start() {
        String mode = plugin.getConfig().getString("packets.backend", "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off")) {
            plugin.getLogger().warning(plugin.lang().plain("warn-packets-off"));
            return;
        }

        boolean wantPe = mode.equals("auto") || mode.equals("packetevents");
        boolean wantNative = mode.equals("auto") || mode.equals("native") || mode.equals("packetevents");

        if (wantPe) {
            if (packetEventsReady()) {
                if (tryStart(createPacketEvents())) return;
            } else if (mode.equals("packetevents")) {
                plugin.getLogger().warning(plugin.lang().plain("warn-packets-pe-missing"));
            }
        }
        if (wantNative) {
            if (mode.equals("auto") || mode.equals("packetevents")) {
                plugin.getLogger().info(plugin.lang().plain("info-packets-native"));
            }
            if (tryStart(new NativeBackend(plugin, processor))) return;
        }
        plugin.getLogger().warning(plugin.lang().plain("warn-packets-none"));
    }

    private boolean tryStart(PacketBackend candidate) {
        try {
            candidate.start();
            backend = candidate;
            return true;
        } catch (Throwable ex) {
            try {
                candidate.stop();
            } catch (Throwable ignored) {
                // слой не запустился — чистить нечего
            }
            plugin.getLogger().warning(plugin.lang().plain("warn-packets-failed", "backend", candidate.name(), "error", String.valueOf(ex)));
            return false;
        }
    }

    /** PacketEvents установлен, включён и инициализирован. Ни одна ссылка на его классы не выходит за этот метод. */
    private boolean packetEventsReady() {
        try {
            var pe = plugin.getServer().getPluginManager().getPlugin("packetevents");
            if (pe == null || !pe.isEnabled()) return false;
            Class.forName("com.github.retrooper.packetevents.PacketEvents", false, plugin.getClass().getClassLoader());
            return PeProbe.ready();
        } catch (Throwable ex) {
            return false;
        }
    }

    private PacketBackend createPacketEvents() {
        return new PacketEventsBackend(processor);
    }

    public void stop() {
        if (backend != null) {
            backend.stop();
            backend = null;
        }
        processor.clear();
    }

    public void onJoin(Player p) {
        if (backend != null) backend.onJoin(p);
    }

    public void onQuit(Player p) {
        if (backend != null) backend.onQuit(p);
        processor.remove(p.getUniqueId());
    }

    public void forget(java.util.UUID id) {
        processor.remove(id);
    }

    public boolean active() {
        return backend != null;
    }

    /** Название активного слоя для баннера и диагностики. */
    public String name() {
        return backend == null ? "-" : backend.name();
    }

    public PacketProcessor processor() {
        return processor;
    }
}
