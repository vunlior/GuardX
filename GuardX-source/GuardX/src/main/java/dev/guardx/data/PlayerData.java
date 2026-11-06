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

package dev.guardx.data;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Состояние одного игрока. Почти всё используется только из основного потока.
 * Поля с volatile читает ещё и сетевой поток (пакетные проверки).
 */
public final class PlayerData {

    public final UUID uuid;
    public final long joinTime = System.currentTimeMillis();
    /** До какого момента игрок временно исключён из проверок (/guardx exempt). */
    public volatile long exemptUntil;

    // --- движение (основной поток) ---
    public Location lastSafe;
    public int airTicks;
    public double lastDeltaY;
    public double groundY;

    // --- поворот (основной поток): нужен проверке хитбокса, т.к. пакет удара приходит раньше пакета поворота ---
    public float yaw;
    public float pitch;
    public float prevYaw;
    public float prevPitch;

    /** Где был этот игрок в последние ~1.5 с (для компенсации задержки). */
    public final PositionHistory history = new PositionHistory();

    // --- причины, по которым движение может быть "необычным" ---
    public volatile long lastTeleport;
    public volatile long lastDig;
    public long lastVelocity;
    public long lastDamage;
    public long lastIce;
    public long ignoreTeleportUntil;

    /** Просит пакетные проверки начать счёт заново (после телепорта / отката). */
    public volatile boolean packetReset;

    // --- скользящее среднее скорости, Jesus, NoSlow ---
    public final Deque<Double> speedWindow = new ArrayDeque<>();
    public int jesusTicks;
    public double jesusMinY;   // JesusCheck: размах высоты в текущей серии
    public double jesusMaxY;
    public int noSlowTicks;
    public long lastMoveAt;    // SpeedCheck: время последнего события с изменением позиции

    // --- кэш MoveContext.nearSpecial(): результат блочного скана для клетки, на которой стоит игрок ---
    public long specKey = Long.MIN_VALUE;
    public long specTime;
    public boolean specBlocks;
    public boolean specEntities;
    public boolean specEntitiesKnown;

    // --- ожидание реакции на отбрасывание (Velocity) ---
    public boolean velPending;
    public long velTime;
    public long velSince;      // начало серии ударов (burst), чтобы оценка не откладывалась бесконечно
    public double velExpectedY;
    public double velBestDy;
    public double velExpectedHor; // горизонтальная составляющая KB (0 = не проверяем)
    public double velDirX, velDirZ; // направление KB по горизонтали
    public double velHorAlong;     // сколько прошли вдоль направления KB

    // --- Nuker: моменты начала ломания блоков ---
    public final Deque<Long> digTimes = new ArrayDeque<>();
    public long nukerKey;        // ключ последнего засчитанного блока (дебаунс повторных кликов)
    public long nukerAt;

    // --- CpsCheck: удары в секунду (все и "полные" с перезарядкой) ---
    public final Deque<Long> cpsTimes = new ArrayDeque<>();
    public final Deque<Long> fullTimes = new ArrayDeque<>();
    public long lastCpsSwing;

    // --- Xray: руды, подменённые на клиенте игрока (мир -> ключи блоков) ---
    public final Map<UUID, Set<Long>> xraySpoof = new HashMap<>();
    public long xraySpoofAt;     // когда последний раз запускали скан-подмену
    public long xraySpoofScanAt; // когда последний раз проверяли возврат настоящих блоков

    // --- Momentum: состояние предыдущего тика ---
    public boolean momValid;
    public boolean momG1;        // стоял на земле в конце прошлого тика
    public boolean momG2;        // стоял на земле в конце позапрошлого тика
    public double momPrevHor;    // горизонтальный сдвиг за прошлый тик
    public long momPrevTime;

    // --- Strafe: состояние предыдущего тика ---
    public boolean strValid;
    public boolean strAir1;      // был в воздухе в конце прошлого тика
    public boolean strAir2;      // был в воздухе в конце позапрошлого тика
    public double strPrevDx;
    public double strPrevDz;
    public long strPrevTime;

    // --- прочее движение ---
    public int sprintBackTicks;  // OmniSprint
    public int climbTicks;       // Spider: тиков на лазаемом блоке подряд
    public int climbCount;
    public double climbSum;
    public long invOpenSince;    // InvMove: когда открылся контейнер (0 = не открыт)
    public int invMoveTicks;

    // --- NoRotate: поворот, который сервер задал при телепорте, и ожидание ответа клиента ---
    public boolean nrPending;
    public long nrTime;
    public float nrYaw;
    public float nrPitch;
    public float nrOldYaw;
    public float nrOldPitch;
    public double nrChange;

    // --- действия ---
    public long handRaisedSince; // MultiAction: с какого момента игрок использует предмет (0 = не использует)
    public volatile long useStart; // FastBow: момент последнего нажатия ПКМ
    public long useSince;        // FastUse: когда игрок начал есть/пить (0 = не использует)
    public long lastDigKey;      // ключ блока, который игрок начал ломать (для FastBreak)

    // --- строительство / инвентарь ---
    public final Deque<Long> placeTimes = new ArrayDeque<>();
    public final Deque<Long> inventoryClickTimes = new ArrayDeque<>();
    public long lastBreakAt;
    public String lastBreakType = "";
    public int consecutiveFastBreaks;

    // --- оповещения ---
    public final Map<String, Long> lastAlert = new HashMap<>();

    private final Map<String, Double> buffers = new HashMap<>();

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public double buffer(String key) {
        return buffers.getOrDefault(key, 0.0);
    }

    /** Добавляет к буферу значение (может быть отрицательным). Буфер не уходит ниже нуля. */
    public double addBuffer(String key, double amount) {
        double v = Math.max(0.0, buffer(key) + amount);
        buffers.put(key, v);
        return v;
    }

    public void setBuffer(String key, double value) {
        buffers.put(key, Math.max(0.0, value));
    }

    /** Сброс состояния движения после телепорта / отката. */
    public void resetMovement() {
        airTicks = 0;
        lastDeltaY = 0;
        packetReset = true;
        buffers.remove("speed");
        buffers.remove("fly");
        buffers.remove("nofall");
        buffers.remove("momentum");
        buffers.remove("strafe");
        buffers.remove("sprint");
        buffers.remove("spider");
        momValid = false;
        strValid = false;
        sprintBackTicks = 0;
        climbTicks = 0;
        climbCount = 0;
        climbSum = 0;
        invMoveTicks = 0;
        buffers.remove("speed-avg");
        buffers.remove("step");
        buffers.remove("noslow");
        speedWindow.clear();
        jesusTicks = 0;
        noSlowTicks = 0;
        lastMoveAt = 0;
        velPending = false;
    }
}
