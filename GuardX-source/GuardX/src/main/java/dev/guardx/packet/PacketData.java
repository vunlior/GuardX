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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Состояние пакетных проверок одного игрока.
 * Пакеты одного соединения обычно приходят последовательно; вызывающий код дополнительно синхронизирует доступ к этому состоянию.
 */
final class PacketData {

    // Timer
    double lastFlyingMs;
    double balance;

    // AutoClicker
    double lastClickMs;
    double lastUseMs = -1000;
    final Deque<Double> clickTimes = new ArrayDeque<>();
    final Deque<Double> intervals = new ArrayDeque<>();

    // Бой: что произошло между двумя пакетами движения (то есть за один клиентский тик)
    boolean attacked;
    boolean swung;
    final Set<Integer> targets = new HashSet<>();
    double noSwingBuffer;
    double multiBuffer;
    double lastTargetMs;

    // Aim: поворот камеры
    boolean haveRot;
    float lastYaw;
    float lastPitch;
    double lastAbsYaw = -1;
    int constCount;
    double lastAttackMs = -1_000_000;
    final Deque<Double> pitchDeltas = new ArrayDeque<>();
    double gcdBuffer;

    // PacketFlood: сколько пакетов пришло в текущую секунду
    double floodStart;
    int floodCount;
    boolean floodFlagged;

    // ActionSpam: пакеты действий (присесть/бежать) в текущую секунду
    double actionStart;
    int actionCount;
    boolean actionFlagged;
}
