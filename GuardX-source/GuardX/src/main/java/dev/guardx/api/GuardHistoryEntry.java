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
 * Одна запись истории нарушений.
 *
 * @param time  время нарушения (миллисекунды Unix)
 * @param check идентификатор проверки
 * @param vl    VL по этой проверке после начисления
 * @param info  подробности (текст на языке сервера в момент нарушения)
 */
public record GuardHistoryEntry(long time, String check, double vl, String info) {
}
