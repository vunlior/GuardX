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

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * «Слои» — правила, по которым часть проверок пропускается для игрока в нужном месте или с нужным правом.
 * Описываются в config.yml (раздел layers). Слой действует, когда выполнены ВСЕ его условия:
 * мир (если задан), зона (если задана) и право (если задано).
 * Любая проверка, которую слой перечисляет в checks (или "*" — все), для игрока пропускается целиком:
 * не считается, не отменяет действия, не даёт VL и не наказывает.
 * Проверки (и встроенные, и добавленные через API) спрашивают слои в одном месте — CheckManager / GuardX#shouldCheck.
 */
public final class LayerManager {

    /** Один слой. {@code worlds} пустой = любой мир; {@code min}/{@code max} null = без зоны. */
    public record Layer(String name, Set<String> worlds, double[] min, double[] max,
                        Set<String> checks, String permission) {

        public boolean allChecks() {
            return checks.contains("*");
        }

        boolean covers(Player p, String check) {
            if (!allChecks() && !checks.contains(check)) return false;
            if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT))) return false;
            if (permission != null && !p.hasPermission(permission)) return false;
            if (min != null) {
                Location l = p.getLocation();
                return l.getX() >= min[0] && l.getX() <= max[0]
                        && l.getY() >= min[1] && l.getY() <= max[1]
                        && l.getZ() >= min[2] && l.getZ() <= max[2];
            }
            return true;
        }
    }

    private final GuardX plugin;
    private volatile List<Layer> layers = List.of();

    public LayerManager(GuardX plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Читает раздел layers из конфига (вызывается при запуске и /guardx reload). */
    public void reload() {
        List<Layer> out = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> raw : plugin.getConfig().getMapList("layers")) {
            index++;
            try {
                out.add(parse(raw, index));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning(plugin.lang().plain("warn-layer", "index", index, "error", String.valueOf(ex.getMessage())));
            }
        }
        layers = List.copyOf(out);
    }

    private static Layer parse(Map<?, ?> m, int index) {
        String name = m.get("name") == null ? "layer-" + index : String.valueOf(m.get("name"));

        Set<String> worlds = new HashSet<>();
        for (String w : strings(m.get("worlds"))) worlds.add(w.toLowerCase(Locale.ROOT));

        Set<String> checks = new HashSet<>();
        for (String c : strings(m.get("checks"))) checks.add(c.toLowerCase(Locale.ROOT));
        if (checks.isEmpty()) throw new IllegalArgumentException("checks is empty");

        double[] a = coords(m.get("min"));
        double[] b = coords(m.get("max"));
        if ((a == null) != (b == null)) throw new IllegalArgumentException("min and max must be set together");
        double[] min = null;
        double[] max = null;
        if (a != null) {
            min = new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
            max = new double[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
        }
        Object perm = m.get("permission");
        String permission = perm == null || String.valueOf(perm).isBlank() ? null : String.valueOf(perm).trim();
        return new Layer(name, Set.copyOf(worlds), min, max, Set.copyOf(checks), permission);
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) if (e != null) out.add(String.valueOf(e).trim());
        } else if (o != null) {
            out.add(String.valueOf(o).trim());
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    private static double[] coords(Object o) {
        if (o == null) return null;
        if (!(o instanceof List<?> l) || l.size() != 3) throw new IllegalArgumentException("coordinates must be [x, y, z]");
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!(l.get(i) instanceof Number n)) throw new IllegalArgumentException("coordinates must be numbers");
            r[i] = n.doubleValue();
        }
        return r;
    }

    /** Пропускать ли проверку {@code check} для игрока сейчас. Без слоёв — мгновенно false. */
    public boolean skips(Player p, String check) {
        List<Layer> ls = layers;
        if (ls.isEmpty()) return false;
        for (Layer l : ls) if (l.covers(p, check)) return true;
        return false;
    }

    public List<Layer> all() {
        return layers;
    }

    /** Слои, которые сейчас действуют на игрока (хотя бы для одной проверки). */
    public List<Layer> activeFor(Player p) {
        List<Layer> out = new ArrayList<>();
        for (Layer l : layers) {
            if (l.allChecks() ? l.covers(p, "*") : l.checks().stream().anyMatch(c -> l.covers(p, c))) out.add(l);
        }
        return out;
    }
}
