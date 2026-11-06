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

package dev.guardx.util;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MoveUtilTest {

    @Test
    void yawZeroLooksSouth() {
        // В Minecraft yaw=0 смотрит на +Z (юг)
        Vector v = MoveUtil.direction(0f, 0f);
        assertEquals(0.0, v.getX(), 1e-6);
        assertEquals(0.0, v.getY(), 1e-6);
        assertEquals(1.0, v.getZ(), 1e-6);
    }

    @Test
    void yawNinetyLooksWest() {
        Vector v = MoveUtil.direction(90f, 0f);
        assertEquals(-1.0, v.getX(), 1e-6);
        assertEquals(0.0, v.getZ(), 1e-6);
    }

    @Test
    void negativePitchLooksUp() {
        // pitch=-90 — взгляд строго вверх
        Vector v = MoveUtil.direction(0f, -90f);
        assertEquals(1.0, v.getY(), 1e-6);
        assertTrue(v.getX() * v.getX() + v.getZ() * v.getZ() < 1e-6);
    }

    @Test
    void positivePitchLooksDown() {
        Vector v = MoveUtil.direction(0f, 90f);
        assertEquals(-1.0, v.getY(), 1e-6);
    }

    @Test
    void directionIsUnitLength() {
        for (float yaw = -180; yaw <= 180; yaw += 37) {
            for (float pitch = -90; pitch <= 90; pitch += 30) {
                Vector v = MoveUtil.direction(yaw, pitch);
                assertEquals(1.0, v.length(), 1e-4, "yaw=" + yaw + " pitch=" + pitch);
            }
        }
    }
}
