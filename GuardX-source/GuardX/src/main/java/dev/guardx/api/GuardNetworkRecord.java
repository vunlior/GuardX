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

package dev.guardx.api;

/**
 * Запись о бане игрока в сети серверов.
 *
 * @param time   время бана (миллисекунды Unix)
 * @param name   ник игрока на момент бана
 * @param server идентификатор сервера, на котором выдан бан (network.server-id)
 * @param check  проверка, из-за которой наказали, или "manual" для бана, выданного администратором командой
 * @param vl     VL в момент наказания
 * @param info   подробности (команда наказания или текст, переданный через API)
 */
public record GuardNetworkRecord(long time, String name, String server, String check, double vl, String info) {
}
