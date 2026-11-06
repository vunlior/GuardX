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

import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Короткая история хитбоксов игрока (по одному снимку за тик, ~1.5 секунды).
 * Нужна для компенсации задержки: клиент атакующего видит цель там, где она была
 * примерно "пинг" миллисекунд назад, а не там, где она находится на сервере сейчас.
 * Используется только из основного потока.
 */
public final class PositionHistory {

    private static final long MAX_AGE_MS = 1500;
    private static final long TICK_MS = 50;

    private record Snapshot(long time, BoundingBox box) {
    }

    private final ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();

    public void add(long now, BoundingBox box) {
        snapshots.addLast(new Snapshot(now, box.clone()));
        while (!snapshots.isEmpty() && now - snapshots.peekFirst().time() > MAX_AGE_MS) {
            snapshots.pollFirst();
        }
    }

    /**
     * Добавляет в out все хитбоксы за последние rewindMs миллисекунд (плюс один тик запаса).
     * Возвращаемые боксы нельзя изменять — перед expand() делай clone().
     */
    public void collect(long now, long rewindMs, List<BoundingBox> out) {
        long cutoff = now - rewindMs - TICK_MS;
        for (Snapshot s : snapshots) {
            if (s.time() >= cutoff) out.add(s.box());
        }
    }
}
