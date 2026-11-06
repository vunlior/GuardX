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

package dev.guardx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * parseExpires решает, до какого момента бан "живой" для сети.
 * 0 = бессрочный/не распознанный.
 */
class NetworkStoreParseTest {

    @Test
    void plainBanIsPermanent() {
        assertEquals(0L, NetworkStore.parseExpires("ban Steve [GuardX] hacking"));
        assertEquals(0L, NetworkStore.parseExpires("banip 1.2.3.4 7d reason")); // не temp*
        assertEquals(0L, NetworkStore.parseExpires("kick Steve"));
        assertEquals(0L, NetworkStore.parseExpires(null));
        assertEquals(0L, NetworkStore.parseExpires(""));
    }

    @Test
    void tempBanParsesDuration() {
        long before = System.currentTimeMillis();
        long exp = NetworkStore.parseExpires("tempban Steve 7d [GuardX] hack");
        long after = System.currentTimeMillis();
        assertTrue(exp > before + 6 * 86_400_000L && exp < after + 8 * 86_400_000L,
                "tempban 7d должен истекать примерно через 7 суток, получено " + exp);
    }

    @Test
    void tempBanMinutesAndWeeks() {
        long now = System.currentTimeMillis();
        long m = NetworkStore.parseExpires("tempban Steve 30m reason");
        assertTrue(m > now + 29 * 60_000L && m < now + 31 * 60_000L);

        long w = NetworkStore.parseExpires("tempbanip Steve 2w reason");
        assertTrue(w > now + 13 * 86_400_000L && w < now + 15 * 86_400_000L);
    }

    @Test
    void pluginPrefixIsTolerated() {
        long now = System.currentTimeMillis();
        long exp = NetworkStore.parseExpires("ess:tempban Steve 12h reason");
        assertTrue(exp > now + 11 * 3_600_000L && exp < now + 13 * 3_600_000L);
    }

    @Test
    void tempbanWithoutDurationIsZero() {
        assertEquals(0L, NetworkStore.parseExpires("tempban Steve"));
    }

    @Test
    void digitsInNickOrReasonAreIgnored() {
        long now = System.currentTimeMillis();
        long exp = NetworkStore.parseExpires("tempban Player1 hacking 7d");
        assertTrue(exp > now + 6 * 86_400_000L && exp < now + 8 * 86_400_000L);
        assertEquals(0L, NetworkStore.parseExpires("tempban Player1 hacking"));
    }

    @Test
    void hugeNumberDoesNotThrow() {
        assertEquals(0L, NetworkStore.parseExpires("tempban Steve 99999999999999999999d reason"));
    }
}
