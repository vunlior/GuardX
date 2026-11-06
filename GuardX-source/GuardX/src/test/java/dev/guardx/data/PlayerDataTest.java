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

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDataTest {

    private PlayerData data() {
        return new PlayerData(UUID.randomUUID());
    }

    @Test
    void bufferDefaultsToZero() {
        PlayerData d = data();
        assertEquals(0.0, d.buffer("anything"));
    }

    @Test
    void addBufferAccumulates() {
        PlayerData d = data();
        assertEquals(1.0, d.addBuffer("vl", 1.0));
        assertEquals(2.5, d.addBuffer("vl", 1.5));
        assertEquals(2.5, d.buffer("vl"));
    }

    @Test
    void bufferNeverGoesBelowZero() {
        PlayerData d = data();
        d.setBuffer("vl", 1.0);
        assertEquals(0.0, d.addBuffer("vl", -5.0));
        d.setBuffer("neg", -3.0);
        assertEquals(0.0, d.buffer("neg"));
    }

    @Test
    void resetMovementClearsVelocityPending() {
        PlayerData d = data();
        d.velPending = true;
        d.velTime = System.currentTimeMillis();
        d.velSince = d.velTime;
        d.resetMovement();
        assertFalse(d.velPending);
    }

    @Test
    void resetMovementClearsCheckBuffers() {
        PlayerData d = data();
        d.addBuffer("speed", 5.0);
        d.addBuffer("fly", 3.0);
        d.addBuffer("momentum", 2.0);
        d.addBuffer("xray", 4.0); // не движение — должен уцелеть
        d.resetMovement();
        assertEquals(0.0, d.buffer("speed"));
        assertEquals(0.0, d.buffer("fly"));
        assertEquals(0.0, d.buffer("momentum"));
        assertEquals(4.0, d.buffer("xray"));
    }
}
