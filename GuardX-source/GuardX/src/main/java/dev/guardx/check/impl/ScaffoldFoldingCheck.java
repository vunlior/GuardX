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

package dev.guardx.check.impl;

import dev.guardx.GuardX;
import dev.guardx.check.Check;
import dev.guardx.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * ScaffoldFolding: looks for repeated, unusually sharp camera turns immediately
 * around block placements. This is an evidence-based check, not a single-turn ban.
 *
 * Register it separately from ScaffoldCheck with:
 * checks.add(new ScaffoldFoldingCheck(plugin));
 */
public final class ScaffoldFoldingCheck extends Check {

    private static final long STATE_TTL_MS = 10_000L;
    private static final int MAX_STATES = 2_048;

    private final Map<UUID, State> states = new HashMap<>();

    public ScaffoldFoldingCheck(GuardX plugin) {
        super(plugin, "scaffold_folding");
    }

    @Override
    public boolean onPlace(Player player, PlayerData data, Block placed, Block against, long now) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()
                || player.isGliding() || player.isDead() || plugin.bypass(player)
                || now < data.exemptUntil || now - data.joinTime < 3_000L
                || now - data.lastTeleport < 1_500L) {
            states.remove(player.getUniqueId());
            return false;
        }

        UUID uuid = player.getUniqueId();
        // cleanup ДО computeIfAbsent: иначе только что созданное состояние (lastPlaceAt = 0) сразу удаляется как просроченное
        cleanup(now);
        State state = states.computeIfAbsent(uuid, ignored -> new State());

        float yaw = normalizeYaw(data.yaw);
        float pitch = data.pitch;
        long sincePrevious = state.lastPlaceAt == 0L ? Long.MAX_VALUE : now - state.lastPlaceAt;

        // PlayerData yaw/pitch are refreshed by GuardListener on PlayerMoveEvent.
        // Ignore stale samples: otherwise a place event could reuse an old rotation.
        float yawDelta = state.hasRotation ? Math.abs(wrapDegrees(yaw - state.lastYaw)) : 0.0f;
        float pitchDelta = state.hasRotation ? Math.abs(pitch - state.lastPitch) : 0.0f;

        state.lastPlaceAt = now;
        state.lastYaw = yaw;
        state.lastPitch = pitch;
        state.hasRotation = true;

        // Only count turns close to a placement, not ordinary camera movement.
        boolean sharpTurn = sincePrevious >= 0L && sincePrevious <= integer("sample-window-ms", 250)
                && yawDelta >= num("min-yaw-change", 42.0)
                && pitchDelta <= num("max-pitch-change", 28.0);

        if (sharpTurn) {
            state.turnTimes.addLast(now);
        }

        long window = integer("analysis-window-ms", 1_800);
        while (!state.turnTimes.isEmpty() && now - state.turnTimes.peekFirst() > window) {
            state.turnTimes.pollFirst();
        }

        int requiredTurns = integer("required-turns", 4);
        int turnCount = state.turnTimes.size();

        // Require multiple repeated sharp turns, and require the player to be
        // actively moving. This avoids flagging a single legitimate camera flick.
        double horizontalSpeed = Math.hypot(player.getVelocity().getX(), player.getVelocity().getZ());
        boolean moving = horizontalSpeed >= num("min-horizontal-speed", 0.08);

        if (turnCount >= requiredTurns && moving) {
            double buffer = data.addBuffer("scaffold_folding", 1.0);
            state.turnTimes.clear();

            double threshold = num("buffer", 3.0);
            if (buffer >= threshold) {
                data.setBuffer("scaffold_folding", 0.0);
                flag(player, data,
                        t("f-scaffold-folding", "turns", turnCount,
                                "yaw", String.format(java.util.Locale.ROOT, "%.1f", yawDelta)),
                        num("vl", 1.0));
            }
        } else {
            data.addBuffer("scaffold_folding", -num("buffer-decay", 0.20));
        }

        return false; // Detection only; do not cancel placement from this check.
    }

    private void cleanup(long now) {
        if (states.size() <= MAX_STATES) {
            Iterator<Map.Entry<UUID, State>> iterator = states.entrySet().iterator();
            while (iterator.hasNext()) {
                State state = iterator.next().getValue();
                if (now - state.lastPlaceAt > STATE_TTL_MS) iterator.remove();
            }
            return;
        }

        // Hard cap protects against unbounded state growth on busy servers.
        Iterator<Map.Entry<UUID, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (now - state.lastPlaceAt > STATE_TTL_MS || states.size() > MAX_STATES) {
                iterator.remove();
            }
        }
    }

    private static float normalizeYaw(float yaw) {
        return wrapDegrees(yaw);
    }

    private static float wrapDegrees(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    private static final class State {
        private final Deque<Long> turnTimes = new ArrayDeque<>();
        private long lastPlaceAt;
        private float lastYaw;
        private float lastPitch;
        private boolean hasRotation;
    }
}
