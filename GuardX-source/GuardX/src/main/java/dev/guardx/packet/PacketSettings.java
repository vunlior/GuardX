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

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Неизменяемый снимок настроек для пакетных проверок.
 * Они работают в сетевом потоке, поэтому конфиг читаем один раз при старте/перезагрузке.
 */
public final class PacketSettings {

    public final boolean timer;
    public final double timerMax;
    public final double timerMin;

    public final boolean clicks;
    public final double maxCps;
    public final int samples;
    public final double minCpsConsistency;
    public final double minDeviationMs;

    public final boolean killaura;
    public final double killauraBuffer;
    public final boolean cancelHits;

    public final boolean noSwing;
    public final double noSwingBuffer;

    public final boolean badPackets;

    public final double killauraWindowMs;

    public final boolean flood;
    public final int floodMax;
    public final boolean floodCancel;

    public final boolean actionSpam;
    public final int actionMax;

    public final boolean aimSkipBedrock;

    public final boolean aim;
    public final double aimCombatMs;
    public final int aimConstCount;
    public final double aimMinDelta;
    public final boolean aimGcd;
    public final int aimGcdSamples;
    public final double aimGcdMin;
    public final double aimGcdBuffer;

    public PacketSettings(FileConfiguration c) {
        timer = c.getBoolean("checks.timer.enabled", true);
        timerMax = clamp(finite(c.getDouble("checks.timer.max-balance", 400), 400), 50, 5000);
        timerMin = clamp(finite(c.getDouble("checks.timer.min-balance", -1000), -1000), -5000, 0);

        clicks = c.getBoolean("checks.autoclicker.enabled", true);
        maxCps = clamp(finite(c.getDouble("checks.autoclicker.max-cps", 20), 20), 1, 40);
        samples = Math.max(10, Math.min(100, c.getInt("checks.autoclicker.samples", 30)));
        minCpsConsistency = clamp(finite(c.getDouble("checks.autoclicker.min-cps-consistency", 8), 8), 1, 40);
        minDeviationMs = clamp(finite(c.getDouble("checks.autoclicker.min-deviation-ms", 6.0), 6.0), 0, 100);

        killaura = c.getBoolean("checks.killaura.enabled", true);
        killauraBuffer = clamp(finite(c.getDouble("checks.killaura.buffer", 4), 4), 1, 100);
        cancelHits = c.getBoolean("checks.killaura.cancel-invalid-hits", true);

        noSwing = c.getBoolean("checks.noswing.enabled", true);
        noSwingBuffer = clamp(finite(c.getDouble("checks.noswing.buffer", 5), 5), 1, 100);

        badPackets = c.getBoolean("checks.badpackets.enabled", true);

        killauraWindowMs = clamp(finite(c.getDouble("checks.killaura.same-tick-window-ms", 35), 35), 0, 50);

        flood = c.getBoolean("checks.packetflood.enabled", true);
        floodMax = Math.max(100, Math.min(5000, c.getInt("checks.packetflood.max-per-second", 450)));
        floodCancel = c.getBoolean("checks.packetflood.cancel-excess", true);

        actionSpam = c.getBoolean("checks.actionspam.enabled", true);
        actionMax = Math.max(10, Math.min(500, c.getInt("checks.actionspam.max-per-second", 50)));

        aimSkipBedrock = c.getBoolean("checks.aim.skip-bedrock", true);

        aim = c.getBoolean("checks.aim.enabled", true);
        aimCombatMs = clamp(finite(c.getDouble("checks.aim.combat-ms", 2000), 2000), 500, 10000);
        aimConstCount = Math.max(3, Math.min(40, c.getInt("checks.aim.constant-count", 8)));
        aimMinDelta = clamp(finite(c.getDouble("checks.aim.min-delta", 2.0), 2.0), 0.5, 90);
        aimGcd = c.getBoolean("checks.aim.gcd.enabled", true);
        aimGcdSamples = Math.max(10, Math.min(60, c.getInt("checks.aim.gcd.samples", 20)));
        aimGcdMin = clamp(finite(c.getDouble("checks.aim.gcd.min-step", 0.004), 0.004), 0.0006, 0.009);
        aimGcdBuffer = clamp(finite(c.getDouble("checks.aim.gcd.buffer", 6), 6), 1, 100);
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
