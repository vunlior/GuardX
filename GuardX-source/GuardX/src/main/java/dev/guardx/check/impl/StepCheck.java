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

import dev.guardx.Lang;
import dev.guardx.GuardX;
import dev.guardx.check.Check;
import dev.guardx.check.MoveContext;
import dev.guardx.data.PlayerData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * Step / HighJump: подъём за один тик больше, чем возможно в ванили.
 * Максимум — ступенька 0.6 блока или прыжок (0.42, плюс 0.1 за уровень Jump Boost).
 */
public final class StepCheck extends Check {

    public StepCheck(GuardX plugin) {
        super(plugin, "step");
    }

    @Override
    public void onMove(MoveContext c) {
        if (!c.positionChanged || c.exempt() || c.nearSpecial()) return;

        PlayerData d = c.data;
        double cap = num("max-step", 0.65);
        PotionEffect jb = dev.guardx.util.Compat.effect(c.player, dev.guardx.util.Compat.JUMP_BOOST);
        if (jb != null) cap += (jb.getAmplifier() + 1) * 0.1;

        if (c.dy <= cap) {
            d.addBuffer("step", -0.25);
            return;
        }

        double buf = d.addBuffer("step", 1.0);
        if (buf >= num("buffer", 2)) {
            d.setBuffer("step", 0);
            flagMove(c, t("f-step", "dy", Lang.n(c.dy, 2), "max", Lang.n(cap, 2)), 1.0);
        }
    }
}
