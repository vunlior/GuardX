# Changelog / История изменений

## 1.0.0 (лицензия / license)

**RU**
- Лицензия изменена с All Rights Reserved на **Apache License 2.0**. Добавлен файл `NOTICE`, во все `.java` файлы добавлен заголовок с копирайтом, в `pom.xml` указана лицензия, обновлены README и THIRD_PARTY_NOTICES.

**EN**
- License changed from All Rights Reserved to the **Apache License 2.0**. Added a `NOTICE` file, added a copyright header to all `.java` files, declared the license in `pom.xml`, updated the README and THIRD_PARTY_NOTICES.

## 1.0.0 (исправления v5 / v5 fixes)

**RU**
- **ScaffoldFolding** впервые стал рабочим: состояние игрока больше не удаляется сразу после создания (очистка выполняется до `computeIfAbsent`). По умолчанию `punish-vl: 0`: сначала понаблюдай за оповещениями.
- **NetworkStore:** срок tempban берётся только из токенов после ника и только при полном совпадении `\d{1,9}[smhdw]` (раньше «Player1 hacking» давало 1 час, а огромное число — `NumberFormatException`).
- **Жалобы:** команда `reports.threshold.command` выполняется один раз при достижении порога, а не на каждой следующей жалобе. Устаревшие записи кулдаунов и подозреваемых теперь чистятся.
- **config_en.yml:** добавлены `checks.velocity.extra-ms` и `pass-ratio`.
- **Telegram:** если разметка `**`/`` ` `` из введённого игроком текста даёт неверно вложенные теги, сообщение отправляется простым текстом, оповещение не теряется.
- **MySQL:** индекс `ip_hash` на старых таблицах создаётся обычным `CREATE INDEX` (без `IF NOT EXISTS`) после миграции колонок.

**EN**
- **ScaffoldFolding** now actually works: a player's state is no longer deleted right after creation (cleanup runs before `computeIfAbsent`). Default `punish-vl: 0`: watch the alerts first.
- **NetworkStore:** tempban duration is read only from tokens after the nickname and only on a full `\d{1,9}[smhdw]` match (previously "Player1 hacking" gave 1 hour and a huge number threw `NumberFormatException`).
- **Reports:** `reports.threshold.command` runs once when the threshold is reached, not on every further report. Stale cooldown and suspect entries are now pruned.
- **config_en.yml:** added `checks.velocity.extra-ms` and `pass-ratio`.
- **Telegram:** if `**`/`` ` `` markup in player-entered text produces mis-nested tags, the message is sent as plain text so the alert is not lost.
- **MySQL:** the `ip_hash` index on old tables is created with a plain `CREATE INDEX` (no `IF NOT EXISTS`) after the column migration.

## 1.0.0 (PacketEvents необязателен / PacketEvents is optional)

**RU**
- **PacketEvents больше не требуется.** В `plugin.yml` он перенесён из `depend` в `softdepend`. Без него работает встроенный пакетный слой на Netty (классы `NativeBackend`, `PacketProcessor`): он подключается к каналу игрока и разбирает пакеты движения, взмаха, использования предмета, удара/взаимодействия с сущностью, действий игрока и смены слота. Классы и поля сервера ищутся по имени и типу, без привязки к обфусцированным именам.
- Новый параметр `packets.backend`: `auto` (PacketEvents, если он есть, иначе встроенный), `packetevents`, `native`, `off`. Если выбранный слой не запустился, GuardX переключается на другой или продолжает работать без пакетных проверок и пишет причину в консоль.
- Логика пакетных проверок вынесена из `GuardPacketListener` в `PacketProcessor` и не зависит от источника пакетов. Все ссылки на PacketEvents собраны в одном адаптере `PacketEventsBackend`, который загружается только при наличии плагина.
- Баннер запуска и `/guardx diagnostics` показывают активный пакетный слой. Новые ключи в `lang/`: `banner-backend`, `diag-packets`, `warn-packets-*`, `info-packets-native`.
- Встроенный слой не проверялся на живом сервере: сначала проверь его на тестовом.

**EN**
- **PacketEvents is no longer required.** In `plugin.yml` it moved from `depend` to `softdepend`. Without it the built-in Netty packet layer runs (`NativeBackend`, `PacketProcessor`): it attaches to each player's channel and decodes movement, swing, item use, entity attack/interact, player action and hotbar slot packets. Server classes and fields are found by name and type, not by obfuscated names.
- New `packets.backend` option: `auto` (PacketEvents if present, otherwise built-in), `packetevents`, `native`, `off`. If the chosen layer fails to start, GuardX falls back to the other one or keeps running without packet checks and logs the reason.
- Packet check logic moved from `GuardPacketListener` to `PacketProcessor` and no longer depends on the packet source. All PacketEvents references live in one adapter, `PacketEventsBackend`, which is loaded only when the plugin is present.
- The startup banner and `/guardx diagnostics` show the active packet layer. New `lang/` keys: `banner-backend`, `diag-packets`, `warn-packets-*`, `info-packets-native`.
- The built-in layer has not been tested on a live server: try it on a test server first.

## 1.0.0 (Telegram, история, слои, API / Telegram, history, layers, API)

**RU**
- **Telegram:** оповещения о наказаниях, жалобах и пороге жалоб (раздел `telegram` в конфиге, прокси, темы групп). Общий код отправки с Discord (`Notifier`), тексты из `lang/` (ключи `discord-*` переименованы в `notify-*`). `/guardx notifytest` проверяет настройку.
- **Постоянная история нарушений:** `plugins/GuardX/history/`, `/guardx history <ник> [страница]` работает после перезапуска и для игроков не в сети; раздел `history` в конфиге.
- **Слои (`layers`):** отключение выбранных проверок по миру, зоне и праву; единая точка проверки для встроенных и добавленных проверок; `/guardx layers [игрок]`.
- **API:** `GuardAPI` (VL, история, `flag`, исключения, слои) и возможность добавлять свои проверки (`registerCheck` / `unregisterCheck`).

**EN**
- **Telegram:** alerts for punishments, reports and the report threshold (`telegram` config section, proxy, group topics). Sending code is shared with Discord (`Notifier`), texts come from `lang/` (`discord-*` keys were renamed to `notify-*`). `/guardx notifytest` verifies the setup.
- **Persistent violation history:** `plugins/GuardX/history/`; `/guardx history <name> [page]` works after restarts and for offline players; `history` config section.
- **Layers (`layers`):** disable selected checks by world, area and permission; one shared gate for built-in and added checks; `/guardx layers [player]`.
- **API:** `GuardAPI` (VL, history, `flag`, exemptions, layers) and the ability to add custom checks (`registerCheck` / `unregisterCheck`).

## 1.0.0 (локализация / localization)

**RU**
- Полная поддержка русского и английского: тексты нарушений (оповещения, история, Discord, лог), `/guardx inspect|info|top|stats|reports`, файл `/guardx dump`, ошибки в консоли — всё берётся из `lang/ru.yml` и `lang/en.yml`.
- Новый плейсхолдер `%reason%` в командах наказания: текст причины на выбранном языке (ключ `punish-reason`). Конфиг по умолчанию использует `kick %player% [GuardX] %reason%`.
- Английский `config_en.yml` с комментариями: при первом запуске выбирается по языку сервера (русский для ru, иначе английский).
- `plugin.yml`: описания команд и прав на двух языках.
- Старые файлы `lang/*.yml` продолжают работать: отсутствующие ключи берутся из встроенных.

**EN**
- Full Russian and English support: violation descriptions (alerts, history, Discord, log), `/guardx inspect|info|top|stats|reports`, the `/guardx dump` file and console errors all come from `lang/ru.yml` and `lang/en.yml`.
- New `%reason%` placeholder for punishment commands: reason text in the selected language (key `punish-reason`). The default config uses `kick %player% [GuardX] %reason%`.
- English `config_en.yml` with comments: chosen on first start by the server language (Russian for ru, English otherwise).
- `plugin.yml`: command and permission descriptions in both languages.
- Existing `lang/*.yml` files keep working: missing keys fall back to the bundled ones.

## 1.0.0 (доработки / update)

**RU**
- KillAura (мульти-цель): на клиентах 1.21.2+ пакет движения не шлётся, пока игрок стоит, из-за чего два обычных удара по разным мобам могли считаться «одним тиком». Теперь «тик» определяется ещё и по времени (`checks.killaura.same-tick-window-ms`).
- Aim: Bedrock-игроки (Geyser/Floodgate) пропускаются (`checks.aim.skip-bedrock`).
- Настройки проверок кэшируются и сбрасываются при `/guardx reload`: меньше нагрузки на каждый тик и каждое движение.
- Velocity: безопасное получение TPS (`plugin.tps()`), больше нет `NoSuchMethodError` вне Paper.
- CheckManager ловит `Throwable`, а не только `Exception`: ошибка одной проверки не прерывает цикл по игрокам.
- Счётчик наказаний ограничен по размеру; `violations.log` ротируется (`log-max-size-mb`).
- Откат (setback) оставлен без изменений.
- Поддерживаемые версии: Minecraft 1.16 — 1.21.x. На других версиях при запуске пишется предупреждение.
- **Новые проверки:** Elytra (скорость на элитрах), FoodSprint (бег при голоде/слепоте), PacketFlood (лимит пакетов в секунду), ActionSpam (спам присеста/бега), невозможный слот хотбара в BadPackets.
- **Новые возможности:** клик по оповещению телепортирует к игроку (`alert-click-teleport`), `/guardx exempt <игрок> [минуты|off]`, `/guardx stats`.

**EN**
- KillAura (multi-target): 1.21.2+ clients send no movement packet while standing still, so two normal hits on different mobs could be treated as one tick. A tick is now also detected by time (`checks.killaura.same-tick-window-ms`).
- Aim: Bedrock players (Geyser/Floodgate) are skipped (`checks.aim.skip-bedrock`).
- Check settings are cached and invalidated on `/guardx reload`: less overhead on every tick and move.
- Velocity: safe TPS lookup (`plugin.tps()`), no more `NoSuchMethodError` outside Paper.
- CheckManager catches `Throwable` instead of only `Exception`: one broken check no longer aborts the player loop.
- Punishment counter is size-capped; `violations.log` is rotated (`log-max-size-mb`).
- Setback behaviour left unchanged.
- Supported versions: Minecraft 1.16 - 1.21.x. A warning is logged on startup for any other version.
- **New checks:** Elytra (elytra speed), FoodSprint (sprinting while hungry/blind), PacketFlood (packets-per-second limit), ActionSpam (sneak/sprint spam), impossible hotbar slot in BadPackets.
- **New features:** click an alert to teleport to the player (`alert-click-teleport`), `/guardx exempt <player> [minutes|off]`, `/guardx stats`.

## 1.0.0

**RU**
- Первый релиз под именем GuardX 1.0.0, разработчик — Vunlior.
- Исправлена ошибка компиляции `GuardPacketListener cannot be converted to PacketListenerCommon`: listener регистрируется через `asAbstract(...)` и снимается при выключении.
- Новый лог включения и выключения плагина в консоли.
- Языки русский и английский (`language`, папка `lang/`).
- Оповещения в Discord (наказания, жалобы, порог жалоб).
- Плейсхолдеры PlaceholderAPI.
- События `GuardFlagEvent` и `GuardPunishEvent` для других плагинов.
- Команда `/guardx debug`.
- Статистика bStats (id 35139), библиотека упакована в jar через maven-shade-plugin.
- Новый README на русском и английском, лицензия All Rights Reserved.
- Имя файла сборки: `GuardX-AC_v1.0.0_1.16+_Universal.jar`.

**EN**
- First release as GuardX 1.0.0, developed by Vunlior.
- Fixed the compile error `GuardPacketListener cannot be converted to PacketListenerCommon`: the listener is now registered via `asAbstract(...)` and unregistered on disable.
- New console banner on plugin enable/disable.
- Russian and English languages (`language` option, `lang/` folder).
- Discord alerts (punishments, reports, report threshold).
- PlaceholderAPI placeholders.
- `GuardFlagEvent` and `GuardPunishEvent` for other plugins.
- `/guardx debug` command.
- bStats statistics (id 35139), library shaded into the jar with maven-shade-plugin.
- New bilingual README, All Rights Reserved license.
- Build file name: `GuardX-AC_v1.0.0_1.16+_Universal.jar`.
