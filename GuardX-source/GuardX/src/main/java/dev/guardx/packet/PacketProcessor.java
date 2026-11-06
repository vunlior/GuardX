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
import dev.guardx.Lang;
import dev.guardx.data.PlayerData;

import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проверки на уровне пакетов. Не зависит от того, откуда пришли пакеты (PacketEvents или встроенный слой):
 * бэкенд превращает пакет в {@link PacketFrame} и вызывает {@link #handle}.
 *
 * Работает в сетевом потоке, поэтому здесь НЕТ обращений к миру Bukkit:
 * всё, что нужно сделать с игроком (оповещение, откат), уходит в основной поток через plugin.flagAsync.
 *
 * Преимущество перед событиями Bukkit: время прихода пакета не зависит от TPS сервера,
 * а порядок пакетов внутри клиентского тика известен точно.
 */
public final class PacketProcessor {

    private final GuardX plugin;
    private final Map<UUID, PacketData> states = new ConcurrentHashMap<>();

    public PacketProcessor(GuardX plugin) {
        this.plugin = plugin;
    }

    public void remove(UUID id) {
        states.remove(id);
    }

    /** Очищает временные состояния всех подключений при выключении плагина. */
    public void clear() {
        states.clear();
    }

    /** Нужны ли значения координат/углов из пакетов движения (BadPackets и Aim). Бэкенд читает их только в этом случае. */
    public boolean needsFlyingData() {
        PacketSettings cfg = plugin.settings();
        return cfg != null && (cfg.badPackets || cfg.aim);
    }

    /**
     * Разбирает один клиентский пакет.
     * @return true, если пакет нужно отменить (до сервера он не дойдёт)
     */
    public boolean handle(UUID id, PacketFrame f) {
        if (id == null || !plugin.isEnabled()) return false;
        PacketSettings cfg = plugin.settings();
        if (cfg == null) return false;

        boolean early = cfg.flood || cfg.actionSpam || cfg.badPackets;
        PacketKind kind = f.kind;
        if (kind == PacketKind.OTHER && !(cfg.flood)) return false;

        PacketData s = states.computeIfAbsent(id, k -> new PacketData());
        PlayerData pd = plugin.peekData(id);
        double nowMs = System.nanoTime() / 1_000_000.0;

        // Обычно пакеты одного соединения идут последовательно в одном Netty event loop.
        // Синхронизация дополнительно защищает состояние, если реализация транспорта меняет это поведение.
        synchronized (s) {
            // Проверки, которым нужны ВСЕ пакеты: флуд, спам действий, неверный слот хотбара
            if (early && !earlyChecks(id, f, s, cfg, nowMs)) return true; // пакет отменён

            switch (kind) {
                case FLYING:
                    return onFlying(f, id, pd, s, cfg, nowMs);
                case ANIMATION:
                    onSwing(id, pd, s, cfg, nowMs);
                    return false;
                case USE_ITEM:
                    s.lastUseMs = nowMs;
                    return false;
                case INTERACT:
                    s.lastUseMs = nowMs; // правый клик по сущности тоже сопровождается взмахом
                    return false;
                case ATTACK:
                    return onAttack(f, id, s, cfg, nowMs);
                default:
                    return false;
            }
        }
    }

    // ---------------------------------------------------------------- флуд / спам

    /**
     * Лимит пакетов в секунду (защита от флуд-краша и «пакетных» читов), спам пакетов действий
     * и невозможный слот хотбара.
     * @return false, если пакет отменён и дальше его разбирать не нужно
     */
    private boolean earlyChecks(UUID id, PacketFrame f, PacketData s, PacketSettings cfg, double nowMs) {
        if (cfg.flood) {
            if (nowMs - s.floodStart >= 1000) {
                s.floodStart = nowMs;
                s.floodCount = 0;
                s.floodFlagged = false;
            }
            s.floodCount++;
            if (s.floodCount > cfg.floodMax) {
                if (!s.floodFlagged) {
                    s.floodFlagged = true;
                    plugin.flagAsync(id, "packetflood", plugin.lang().plain("f-packetflood", "max", cfg.floodMax), 3.0, false);
                }
                if (cfg.floodCancel) return false;
            }
        }

        if (cfg.actionSpam && f.kind == PacketKind.ENTITY_ACTION) {
            if (nowMs - s.actionStart >= 1000) {
                s.actionStart = nowMs;
                s.actionCount = 0;
                s.actionFlagged = false;
            }
            s.actionCount++;
            if (s.actionCount > cfg.actionMax && !s.actionFlagged) {
                s.actionFlagged = true;
                plugin.flagAsync(id, "actionspam", plugin.lang().plain("f-actionspam", "max", cfg.actionMax), 1.0, false);
            }
        }

        if (cfg.badPackets && f.kind == PacketKind.HELD_ITEM) {
            int slot = f.slot;
            if (slot < 0 || slot > 8) {
                plugin.flagAsync(id, "badpackets", plugin.lang().plain("f-badslot", "slot", slot), 3.0, false);
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Aim

    /**
     * Аим-ассист / спинбот. Работает только в бою (последние combat-ms после удара).
     * 1) Constant: много подряд одинаковых по модулю поворотов — рука так не двигается (спинбот, «дёрганье» ауры).
     * 2) GCD: поворот мышью всегда кратен шагу чувствительности (>= 0.0096° на отсчёт мыши),
     *    а математический аимбот даёт углы, у которых общего делителя нет.
     */
    private void aim(UUID id, PlayerData pd, PacketData s, PacketSettings cfg,
                     PacketFrame w, double nowMs) {
        if (cfg.aimSkipBedrock && id.getMostSignificantBits() == 0L) return; // Bedrock (Floodgate): тач/геймпад, GCD неприменим
        if (!w.hasRot) return;
        float yaw = w.yaw;
        float pitch = w.pitch;
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;

        if (!s.haveRot) {
            s.haveRot = true;
            s.lastYaw = yaw;
            s.lastPitch = pitch;
            return;
        }
        double dYaw = wrap180(yaw - s.lastYaw);
        double dPitch = pitch - s.lastPitch;
        s.lastYaw = yaw;
        s.lastPitch = pitch;

        // После телепорта поворот может меняться скачком — не считаем
        long wall = System.currentTimeMillis();
        boolean fresh = pd == null || wall - pd.lastTeleport < 1000 || wall - pd.joinTime < 3000;
        boolean combat = nowMs - s.lastAttackMs < cfg.aimCombatMs;
        if (fresh || !combat) {
            s.constCount = 0;
            s.lastAbsYaw = -1;
            s.pitchDeltas.clear();
            return;
        }

        double ay = Math.abs(dYaw);
        double ap = Math.abs(dPitch);

        // 1) Constant
        if (ay >= cfg.aimMinDelta && Math.abs(ay - s.lastAbsYaw) < 1.0E-4) {
            s.constCount++;
            if (s.constCount >= cfg.aimConstCount) {
                s.constCount = 0;
                plugin.flagAsync(id, "aim", plugin.lang().plain("f-aim-const", "delta", Lang.n(ay, 2)), 1.5, false);
            }
        } else {
            s.constCount = 0;
        }
        s.lastAbsYaw = ay;

        // 2) GCD по pitch (дельты меньше шага мыши и огромные рывки пропускаем)
        if (cfg.aimGcd && ap >= 0.001 && ap <= 25.0) {
            s.pitchDeltas.addLast(ap);
            if (s.pitchDeltas.size() >= cfg.aimGcdSamples) {
                double g = 0;
                for (double v : s.pitchDeltas) g = (g == 0) ? v : gcd(Math.max(g, v), Math.min(g, v));
                s.pitchDeltas.clear();
                if (g < cfg.aimGcdMin) {
                    s.gcdBuffer += 1.0;
                    if (s.gcdBuffer >= cfg.aimGcdBuffer) {
                        s.gcdBuffer = 0;
                        plugin.flagAsync(id, "aim", plugin.lang().plain("f-aim-gcd", "gcd", Lang.n(g, 5)), 1.0, false);
                    }
                } else {
                    s.gcdBuffer = Math.max(0, s.gcdBuffer - 1.0);
                }
            }
        }
    }

    /** НОД для дробных чисел с допуском на ошибку float (0.0005° намного меньше минимального шага мыши 0.0096°). */
    private static double gcd(double a, double b) {
        while (b > 0.0005) {
            double t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static double wrap180(double angle) {
        angle %= 360.0;
        if (angle > 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }

    // ---------------------------------------------------------------- движение

    /** @return true, если пакет нужно отменить */
    private boolean onFlying(PacketFrame w, UUID id, PlayerData pd, PacketData s,
                             PacketSettings cfg, double nowMs) {
        // 1) Невозможные значения: NaN/бесконечность и координаты за пределами мира (краш-эксплойты,
        //    кривые клиенты), pitch вне -90..90 (в ванили так не бывает). Такой пакет до сервера не доходит.
        if (cfg.badPackets && w.flyingRead) {
            if (w.hasPos) {
                double x = w.x;
                double y = w.y;
                double z = w.z;
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                        || Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7 || Math.abs(y) > 1.0E7) {
                    plugin.flagAsync(id, "badpackets", plugin.lang().plain("f-badpos"), 5.0, false);
                    return true;
                }
            }
            if (w.hasRot) {
                float yaw = w.yaw;
                float pitch = w.pitch;
                if (!Float.isFinite(yaw) || !(Math.abs(pitch) <= 90.0f)) {
                    plugin.flagAsync(id, "badpackets", plugin.lang().plain("f-badrot", "yaw", yaw, "pitch", pitch), 2.0, false);
                    return true;
                }
            }
        }

        // 2) Aim: анализ поворотов камеры в бою
        if (cfg.aim && w.flyingRead) aim(id, pd, s, cfg, w, nowMs);

        // 3) Timer
        if (cfg.timer) timer(id, pd, s, cfg, nowMs);

        // 4) Конец клиентского тика: подводим итог по ударам
        if (s.attacked) {
            if (cfg.noSwing) {
                if (!s.swung) {
                    s.noSwingBuffer += 1.0;
                    if (s.noSwingBuffer >= cfg.noSwingBuffer) {
                        s.noSwingBuffer = 0;
                        plugin.flagAsync(id, "noswing", plugin.lang().plain("f-noswing"), 1.0, false);
                    }
                } else {
                    s.noSwingBuffer = Math.max(0, s.noSwingBuffer - 0.5);
                }
            }
            if (s.targets.size() <= 1) {
                s.multiBuffer = Math.max(0, s.multiBuffer - 0.25);
            }
        }
        s.attacked = false;
        s.swung = false;
        s.targets.clear();
        return false;
    }

    /**
     * Баланс времени: каждый пакет движения "стоит" 50 мс, реально прошедшее время вычитаем.
     * Сетевой лаг даёт отрицательный баланс, а затем пачку пакетов — в сумме ноль.
     * Если пакеты идут чаще, чем 20 в секунду, баланс растёт — это и есть Timer.
     */
    private void timer(UUID id, PlayerData pd, PacketData s, PacketSettings cfg, double nowMs) {
        if (pd == null) return;

        long wall = System.currentTimeMillis();
        boolean restart = pd.packetReset || s.lastFlyingMs == 0
                || wall - pd.lastTeleport < 1000 || wall - pd.joinTime < 3000;
        if (restart) {
            pd.packetReset = false;
            s.lastFlyingMs = nowMs;
            s.balance = 0;
            return;
        }

        double delta = nowMs - s.lastFlyingMs;
        s.lastFlyingMs = nowMs;

        if (delta > 1500) { // долго молчал — начинаем с нуля
            s.balance = 0;
            return;
        }

        s.balance = Math.max(s.balance + (50 - delta), cfg.timerMin);

        if (s.balance > cfg.timerMax) {
            double surplus = s.balance;
            s.balance = 0;
            plugin.flagAsync(id, "timer", plugin.lang().plain("f-timer", "ms", Lang.n(surplus, 0)), 1.0, true);
        }
    }

    // ---------------------------------------------------------------- клики

    private void onSwing(UUID id, PlayerData pd, PacketData s, PacketSettings cfg, double nowMs) {
        s.swung = true;
        if (!cfg.clicks) return;

        // Взмах от установки блока или использования предмета — не клик
        if (nowMs - s.lastUseMs <= 30) return;

        // Ломание блоков даёт взмахи руки — тоже не считаем
        if (pd != null && System.currentTimeMillis() - pd.lastDig < 400) {
            s.lastClickMs = nowMs;
            return;
        }

        if (s.lastClickMs != 0) {
            double interval = nowMs - s.lastClickMs;
            if (interval < 1000) {
                s.intervals.addLast(interval);
                while (s.intervals.size() > cfg.samples) s.intervals.pollFirst();
            } else {
                s.intervals.clear();
            }
        }
        s.lastClickMs = nowMs;

        Deque<Double> q = s.clickTimes;
        q.addLast(nowMs);
        while (!q.isEmpty() && nowMs - q.peekFirst() > 1000) q.pollFirst();
        int cps = q.size();

        // 1) Нереальный CPS
        if (cps > cfg.maxCps) {
            plugin.flagAsync(id, "autoclicker", plugin.lang().plain("f-autoclicker-cps", "cps", cps, "max", (int) cfg.maxCps), 1.0, false);
            q.clear();
            return;
        }

        // 2) Слишком ровные интервалы между кликами
        if (cps >= cfg.minCpsConsistency && s.intervals.size() >= cfg.samples) {
            double sum = 0;
            for (double v : s.intervals) sum += v;
            double mean = sum / s.intervals.size();
            double var = 0;
            for (double v : s.intervals) var += (v - mean) * (v - mean);
            double std = Math.sqrt(var / s.intervals.size());

            if (std < cfg.minDeviationMs) {
                plugin.flagAsync(id, "autoclicker",
                        plugin.lang().plain("f-autoclicker-steady", "std", Lang.n(std, 1), "cps", cps), 1.0, false);
                s.intervals.clear();
            }
        }
    }

    // ---------------------------------------------------------------- удары

    /** @return true, если пакет нужно отменить */
    private boolean onAttack(PacketFrame f, UUID id, PacketData s, PacketSettings cfg, double nowMs) {
        s.attacked = true;
        s.lastAttackMs = nowMs;
        // id цели не удалось прочитать (новый пакет атаки в PacketEvents): «был удар» для NoSwing учтён, остальное пропускаем
        if (!cfg.killaura || f.entityId < 0) return false;

        // За один клиентский тик перекрестие не может смотреть на две разные сущности:
        // два разных id между пакетами движения — признак мульти-ауры.
        // Клиенты 1.21.2+ не шлют пакет движения, пока игрок стоит: «тик» тогда определяем по времени
        if (!s.targets.isEmpty() && nowMs - s.lastTargetMs > cfg.killauraWindowMs) s.targets.clear();
        s.lastTargetMs = nowMs;
        s.targets.add(f.entityId);
        if (s.targets.size() == 2) {
            s.multiBuffer += 1.0;
            if (s.multiBuffer >= cfg.killauraBuffer) {
                s.multiBuffer = 0;
                plugin.flagAsync(id, "killaura", plugin.lang().plain("f-multitarget"), 1.5, false);
            }
        }
        // этот удар до сервера не дойдёт
        return s.targets.size() >= 2 && cfg.cancelHits;
    }
}
