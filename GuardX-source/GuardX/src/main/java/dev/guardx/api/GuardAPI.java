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

import dev.guardx.GuardX;
import dev.guardx.check.Check;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Публичный API GuardX для других плагинов. Это тонкая обёртка над внутренними менеджерами: своей логики здесь нет.
 * Если GuardX не включён, методы бросают {@link IllegalStateException}.
 * Если не сказано иное, вызывать нужно из основного потока сервера.
 *
 * <p>Свою проверку делают наследником {@link Check} (конструктор {@code super(GuardAPI.plugin(), "my-check")})
 * и регистрируют через {@link #registerCheck(Check)}. Включение и пороги такой проверки
 * читаются из config.yml GuardX: {@code checks.my-check.enabled}, а слои (раздел {@code layers}) работают для неё так же,
 * как для встроенных.
 */
public final class GuardAPI {

    /** Растёт при несовместимых изменениях API. */
    public static final int API_VERSION = 1;

    private GuardAPI() {
    }

    /** Экземпляр плагина (нужен, чтобы создавать собственные проверки). */
    public static GuardX plugin() {
        GuardX p = JavaPlugin.getPlugin(GuardX.class);
        if (!p.isEnabled()) throw new IllegalStateException("GuardX is not enabled");
        return p;
    }

    // ------------------------------------------------------------ нарушения (VL)

    /** VL игрока по одной проверке (0, если нарушений нет). */
    public static double getVL(UUID player, String check) {
        return plugin().violations().get(player).getOrDefault(check, 0.0);
    }

    /** Сумма VL игрока по всем проверкам. */
    public static double getTotalVL(UUID player) {
        double sum = 0;
        for (double v : plugin().violations().get(player).values()) if (Double.isFinite(v) && v > 0) sum += v;
        return sum;
    }

    /** Копия VL игрока: проверка -> VL (только положительные), отсортировано по названию. */
    public static Map<String, Double> getViolations(UUID player) {
        Map<String, Double> out = new TreeMap<>();
        plugin().violations().get(player).forEach((k, v) -> {
            if (v > 0) out.put(k, v);
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Засчитать нарушение игроку от имени вашей проверки (оповещения, история, наказание — как у встроенных).
     * Не делает ничего, если игрок исключён (право guardx.bypass, /guardx exempt, наблюдатель или слой).
     */
    public static void flag(Player player, String check, String info, double vl) {
        GuardX p = plugin();
        if (!p.shouldCheck(player, check)) return;
        p.violations().flag(player, p.data(player), check, info, vl);
    }

    public static void resetViolations(UUID player) {
        plugin().violations().reset(player);
    }

    // ------------------------------------------------------------ история

    /**
     * Последние нарушения игрока (старые первыми), в том числе из файлов после перезапуска сервера.
     * Читает файл, поэтому лучше вызывать не из горячего кода.
     */
    public static List<GuardHistoryEntry> getHistory(UUID player, int limit) {
        return new ArrayList<>(plugin().history().entries(player, Math.max(1, limit)));
    }

    // ------------------------------------------------------------ сеть серверов

    /**
     * Записи о банах игрока в сети серверов (новые первыми, не больше 20). Запрос к базе идёт в фоне,
     * {@code callback} вызывается в основном потоке: со списком записей или с {@code null}, если база недоступна.
     * Если сетевой режим выключен (network.enabled), приходит пустой список.
     */
    public static void getNetworkRecords(UUID player, Consumer<List<GuardNetworkRecord>> callback) {
        if (!plugin().network().enabled()) {
            callback.accept(List.of());
            return;
        }
        plugin().network().lookup(player, null, false, callback);
    }

    /**
     * Записать бан в общую базу сети от имени вашего плагина (например, бан, выданный модератором вручную).
     * Другие серверы сети предупредят модераторов, когда этот игрок зайдёт к ним. Ничего не делает при выключенном network.enabled.
     */
    public static void reportNetworkBan(UUID player, String name, String check, String info) {
        plugin().network().record(player, name, check, 0.0, info);
    }

    /** Снять все записи о банах игрока в сети (ошибочный бан, разбан). Число снятых записей приходит в callback (-1 при ошибке базы). */
    public static void revokeNetworkBans(UUID player, Consumer<Integer> callback) {
        plugin().network().revoke(player, null, callback);
    }

    // ------------------------------------------------------------ исключения и слои

    /** Игрок сейчас не проверяется вообще (право guardx.bypass или /guardx exempt). */
    public static boolean isExempt(Player player) {
        return plugin().bypass(player);
    }

    /** Не проверять игрока {@code millis} мс; значение {@code <= 0} снимает исключение. */
    public static void setExempt(Player player, long millis) {
        plugin().setExempt(player, millis);
    }

    /** Отключена ли проверка для игрока слоем из config.yml (раздел layers). */
    public static boolean isSkippedByLayer(Player player, String check) {
        return plugin().layers().skips(player, check);
    }

    // ------------------------------------------------------------ проверки

    /** Идентификаторы всех проверок, включая добавленные через API. */
    public static List<String> getCheckIds() {
        List<String> out = new ArrayList<>();
        for (Check c : plugin().checks().all()) out.add(c.id());
        return out;
    }

    /**
     * Добавить свою проверку. Идентификатор: 1–32 символа {@code a-z 0-9 _ -}.
     *
     * @return false, если идентификатор некорректен или уже занят
     */
    public static boolean registerCheck(Check check) {
        return plugin().checks().register(check);
    }

    /** Убрать добавленную проверку (например, при выключении вашего плагина). Встроенные убрать нельзя. */
    public static boolean unregisterCheck(String id) {
        return plugin().checks().unregister(id);
    }
}
