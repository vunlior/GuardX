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

package dev.guardx.notify;

import dev.guardx.GuardX;
import dev.guardx.ReportManager;

import java.util.List;

/** Все внешние каналы оповещений. Остальной код вызывает только этот класс, а не конкретные каналы. */
public final class Notifications {

    private final List<Notifier> channels;

    public Notifications(GuardX plugin) {
        this.channels = List.of(new DiscordNotifier(plugin), new TelegramNotifier(plugin));
    }

    public List<Notifier> channels() {
        return channels;
    }

    public void punish(String player, String check, double vl, int count, String command) {
        for (Notifier n : channels) n.punish(player, check, vl, count, command);
    }

    public void report(ReportManager.Report r) {
        for (Notifier n : channels) n.report(r);
    }

    public void threshold(String player, int count) {
        for (Notifier n : channels) n.threshold(player, count);
    }

    public void network(String player, String server, String check, double vl, int count, String ago, String info) {
        for (Notifier n : channels) n.network(player, server, check, vl, count, ago, info);
    }

    /** @return имена каналов, в которые ушло тестовое сообщение */
    public List<String> test() {
        return channels.stream().filter(Notifier::test).map(Notifier::name).toList();
    }

    public void shutdown() {
        for (Notifier n : channels) n.shutdown();
    }
}
