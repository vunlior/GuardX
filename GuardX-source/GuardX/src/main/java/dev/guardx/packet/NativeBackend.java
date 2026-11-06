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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Встроенный пакетный слой: не требует PacketEvents или другого плагина.
 *
 * Принцип тот же, что у PacketEvents: в Netty-конвейер каждого игрока добавляется обработчик, который видит уже
 * разобранные клиентские пакеты до того, как их получит сервер. Классы и поля NMS ищутся по именам классов и типам
 * полей, а не по именам полей, поэтому слой переживает и обфусцированные имена Spigot (1.16 — 1.20.4),
 * и имена Mojang (Paper 1.20.5+).
 *
 * Покрывает ровно то, что нужно GuardX: движение/поворот, взмах, использование предмета, удар и взаимодействие
 * с сущностью, действия игрока, смену слота хотбара. Остальные пакеты проходят без изменений и учитываются
 * только счётчиком PacketFlood.
 */
final class NativeBackend implements PacketBackend {

    private static final String HANDLER = "guardx_packets";

    // имена классов пакетов (без пакета): Mojang -> Spigot (1.16 — 1.20.4)
    private static final String FLYING_MOJ = "ServerboundMovePlayerPacket";
    private static final String FLYING_SPG = "PacketPlayInFlying";

    private final GuardX plugin;
    private final PacketProcessor processor;
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final Map<Class<?>, Info> infos = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> actionMethods = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile boolean injectWarned;
    private volatile boolean actionWarned;

    NativeBackend(GuardX plugin, PacketProcessor processor) {
        this.plugin = plugin;
        this.processor = processor;
    }

    @Override
    public String name() {
        return "Native (Netty)";
    }

    // ---------------------------------------------------------------- запуск / остановка

    @Override
    public void start() {
        if (!Bukkit.getServer().getClass().getName().startsWith("org.bukkit.craftbukkit")) {
            throw new IllegalStateException("not a CraftBukkit-based server");
        }
        try {
            Class.forName("io.netty.channel.Channel");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Netty not found", ex);
        }
        running = true;

        // уже находящиеся на сервере игроки (например, после /reload): первая ошибка — повод отказаться от слоя целиком
        boolean first = true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                inject(p);
            } catch (Throwable ex) {
                if (first) {
                    running = false;
                    stop();
                    throw new IllegalStateException("cannot reach the player's connection: " + ex, ex);
                }
                warnInject(p, ex);
            }
            first = false;
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Channel ch : channels.values()) uninject(ch);
        channels.clear();
        infos.clear();
        actionMethods.clear();
    }

    @Override
    public void onJoin(Player player) {
        if (!running) return;
        try {
            inject(player);
        } catch (Throwable ex) {
            warnInject(player, ex);
        }
    }

    @Override
    public void onQuit(Player player) {
        Channel ch = channels.remove(player.getUniqueId());
        if (ch != null) uninject(ch);
    }

    private void warnInject(Player p, Throwable ex) {
        if (injectWarned) return; // одного предупреждения достаточно, иначе консоль заполнится
        injectWarned = true;
        plugin.getLogger().warning(plugin.lang().plain("warn-packets-inject", "player", p.getName(), "error", String.valueOf(ex)));
    }

    // ---------------------------------------------------------------- внедрение в канал

    private void inject(Player p) throws Exception {
        UUID id = p.getUniqueId();
        Channel ch = channelOf(p);
        channels.put(id, ch);
        ch.eventLoop().execute(() -> {
            try {
                ChannelPipeline pl = ch.pipeline();
                if (pl.get(HANDLER) != null) pl.remove(HANDLER);
                // «packet_handler» — сам сетевой менеджер сервера: перед ним пакеты уже расшифрованы, распакованы и разобраны
                if (pl.get("packet_handler") == null) throw new IllegalStateException("packet_handler not found in pipeline");
                pl.addBefore("packet_handler", HANDLER, new Handler(id));
            } catch (Throwable ex) {
                channels.remove(id, ch);
                warnInject(p, ex);
            }
        });
    }

    private static void uninject(Channel ch) {
        try {
            ch.eventLoop().execute(() -> {
                try {
                    if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                } catch (Throwable ignored) {
                    // канал уже закрыт
                }
            });
        } catch (Throwable ignored) {
            // event loop уже остановлен
        }
    }

    /** Player -> CraftPlayer.getHandle() -> PlayerConnection -> NetworkManager -> Channel; поля ищем по типу, не по имени. */
    private static Channel channelOf(Player p) throws Exception {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object listener = fieldByType(handle, "ServerGamePacketListenerImpl", "PlayerConnection");
        Object network = fieldByType(listener, "Connection", "NetworkManager");
        for (Class<?> c = network.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Channel.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(network);
                if (v instanceof Channel ch) return ch;
            }
        }
        throw new NoSuchFieldException("Channel");
    }

    private static Object fieldByType(Object owner, String... simpleNames) throws Exception {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                if (!matches(f.getType(), simpleNames)) continue;
                f.setAccessible(true);
                Object v = f.get(owner);
                if (v != null) return v;
            }
        }
        throw new NoSuchFieldException(String.join("/", simpleNames) + " in " + owner.getClass().getName());
    }

    private static boolean matches(Class<?> type, String[] names) {
        for (Class<?> t = type; t != null && t != Object.class; t = t.getSuperclass()) {
            for (String n : names) if (t.getSimpleName().equals(n)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- обработчик Netty

    private final class Handler extends ChannelInboundHandlerAdapter {
        private final UUID id;

        Handler(UUID id) {
            this.id = id;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            boolean cancel = false;
            try {
                if (running) cancel = process(id, msg);
            } catch (Throwable ex) {
                // ошибка анализа не должна рвать соединение игрока: пакет просто идёт дальше
            }
            if (cancel) {
                ReferenceCountUtil.release(msg);
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }

    private boolean process(UUID id, Object packet) throws Exception {
        Info info = infos.computeIfAbsent(packet.getClass(), Info::of);
        if (info == Info.NOT_A_PACKET) return false;

        PacketFrame frame;
        switch (info.kind) {
            case FLYING -> {
                frame = new PacketFrame(PacketKind.FLYING);
                if (processor.needsFlyingData() && info.doubles.length >= 3 && info.floats.length >= 2) {
                    double x = 0, y = 0, z = 0;
                    float yaw = 0, pitch = 0;
                    if (info.hasPos) {
                        x = info.doubles[0].getDouble(packet);
                        y = info.doubles[1].getDouble(packet);
                        z = info.doubles[2].getDouble(packet);
                    }
                    if (info.hasRot) {
                        yaw = info.floats[0].getFloat(packet);
                        pitch = info.floats[1].getFloat(packet);
                    }
                    frame.flying(info.hasPos, x, y, z, info.hasRot, yaw, pitch);
                }
            }
            case ATTACK -> frame = new PacketFrame(PacketKind.ATTACK).entity(readInt(info.intField, packet));
            case INTERACT -> {
                // один и тот же пакет и для удара, и для правого клика: различаем по действию
                frame = isAttack(info, packet)
                        ? new PacketFrame(PacketKind.ATTACK).entity(readInt(info.intField, packet))
                        : new PacketFrame(PacketKind.INTERACT);
            }
            case HELD_ITEM -> frame = new PacketFrame(PacketKind.HELD_ITEM).slot(readInt(info.intField, packet));
            default -> frame = PacketFrame.of(info.kind);
        }
        return processor.handle(id, frame);
    }

    private static int readInt(Field f, Object o) throws IllegalAccessException {
        return f == null ? -1 : f.getInt(o);
    }

    /** Действие взаимодействия: ординал ATTACK во всех версиях Minecraft равен 1 (INTERACT, ATTACK, INTERACT_AT). */
    private boolean isAttack(Info info, Object packet) throws Exception {
        if (info.actionField == null) return false;
        Object action = info.actionField.get(packet);
        if (action == null) return false;
        if (action instanceof Enum<?> e) return e.ordinal() == 1;

        Method m = actionMethods.computeIfAbsent(action.getClass(), NativeBackend::findEnumMethod);
        if (m == NO_METHOD) {
            if (!actionWarned) {
                actionWarned = true;
                plugin.getLogger().warning(plugin.lang().plain("warn-packets-action"));
            }
            return false; // безопаснее считать правым кликом: удар не будет ошибочно отменён
        }
        Object v = m.invoke(action);
        return v instanceof Enum<?> e && e.ordinal() == 1;
    }

    private static final Method NO_METHOD = findNoMethod();

    private static Method findNoMethod() {
        try {
            return Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Method findEnumMethod(Class<?> c) {
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().isEnum() && !Modifier.isStatic(m.getModifiers())) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    // вызовем как есть
                }
                return m;
            }
        }
        return NO_METHOD;
    }

    // ---------------------------------------------------------------- описание класса пакета (один раз на класс)

    private static final class Info {
        static final Info NOT_A_PACKET = new Info(PacketKind.OTHER);

        final PacketKind kind;
        boolean hasPos;
        boolean hasRot;
        Field[] doubles = new Field[0];
        Field[] floats = new Field[0];
        Field intField;
        Field actionField;

        Info(PacketKind kind) {
            this.kind = kind;
        }

        static Info of(Class<?> c) {
            String full = c.getName();
            if (!full.startsWith("net.minecraft.")) return NOT_A_PACKET;

            String base = full.substring(full.lastIndexOf('.') + 1);
            int d = base.indexOf('$');
            String outer = d < 0 ? base : base.substring(0, d);
            String inner = d < 0 ? "" : base.substring(d + 1);

            PacketKind kind = switch (outer) {
                case FLYING_MOJ, FLYING_SPG -> PacketKind.FLYING;
                case "ServerboundSwingPacket", "PacketPlayInArmAnimation" -> PacketKind.ANIMATION;
                case "ServerboundUseItemOnPacket", "ServerboundUseItemPacket",
                     "PacketPlayInUseItem", "PacketPlayInBlockPlace" -> PacketKind.USE_ITEM;
                case "ServerboundInteractPacket", "PacketPlayInUseEntity" -> PacketKind.INTERACT;
                case "ServerboundAttackPacket" -> PacketKind.ATTACK;
                case "ServerboundPlayerCommandPacket", "PacketPlayInEntityAction" -> PacketKind.ENTITY_ACTION;
                case "ServerboundSetCarriedItemPacket", "PacketPlayInHeldItemSlot" -> PacketKind.HELD_ITEM;
                default -> PacketKind.OTHER;
            };
            Info info = new Info(kind);

            // поля объявлены в базовом классе пакета; подкласс (Pos, Rot...) их только наследует
            List<Field> dbl = new ArrayList<>();
            List<Field> flt = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    try {
                        if (t == double.class) {
                            f.setAccessible(true);
                            dbl.add(f);
                        } else if (t == float.class) {
                            f.setAccessible(true);
                            flt.add(f);
                        } else if (t == int.class && info.intField == null
                                && (kind == PacketKind.INTERACT || kind == PacketKind.ATTACK || kind == PacketKind.HELD_ITEM)) {
                            f.setAccessible(true);
                            info.intField = f; // id сущности или слот хотбара — первое int-поле
                        } else if (kind == PacketKind.INTERACT && info.actionField == null
                                && !t.isPrimitive() && (t.isEnum() || t.isInterface() || t.getEnclosingClass() != null)
                                && t != java.util.UUID.class) {
                            f.setAccessible(true);
                            info.actionField = f; // действие: enum (1.16) или объект с методом, возвращающим enum (1.17+)
                        }
                    } catch (Throwable ignored) {
                        // поле недоступно — проверка, которой оно нужно, просто не получит данных
                    }
                }
            }
            info.doubles = dbl.toArray(new Field[0]);
            info.floats = flt.toArray(new Field[0]);

            if (kind == PacketKind.FLYING) {
                // что именно изменилось, определяем по подклассу: так надёжнее, чем по именам булевых полей
                String in = inner;
                if (in.equals("PosRot") || in.equals("PacketPlayInPositionLook")) {
                    info.hasPos = true;
                    info.hasRot = true;
                } else if (in.equals("Pos") || in.equals("PacketPlayInPosition")) {
                    info.hasPos = true;
                } else if (in.equals("Rot") || in.equals("PacketPlayInLook")) {
                    info.hasRot = true;
                }
            }
            return info;
        }
    }
}
