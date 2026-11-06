<div align="center">

# ⚔ GuardX

**Серверный античит для Minecraft на уровне пакетов и событий**
**Server-side anti-cheat for Minecraft — packet level + Bukkit events**

![Version](https://img.shields.io/badge/version-1.0.0-brightgreen)
![Java](https://img.shields.io/badge/Java-16%2B-orange)
![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5%20→%201.21.x-blue)
![Platform](https://img.shields.io/badge/Paper%20%7C%20Purpur%20%7C%20Pufferfish-supported-success)
![Author](https://img.shields.io/badge/author-Vunlior-purple)

🇷🇺 [Русский](#-русский) · 🇬🇧 [English](#-english)

</div>

---

# 🇷🇺 Русский

## 📖 Содержание
- [Возможности](#-возможности)
- [Совместимость](#-совместимость)
- [Установка](#-установка)
- [Сборка](#-сборка)
- [Команды и права](#-команды-и-права)
- [Жалобы игроков](#-жалобы-игроков)
- [Как это работает](#-как-это-работает)
- [Настройка](#-настройка)
- [Язык](#-язык)
- [Discord](#-оповещения-в-discord)
- [Telegram](#-оповещения-в-telegram)
- [История нарушений](#-история-нарушений)
- [Слои](#-слои-отключение-проверок-по-месту-и-праву)
- [PlaceholderAPI](#-placeholderapi)
- [API для разработчиков](#-api-для-разработчиков)
- [Статистика bStats](#-статистика-bstats)
- [Ограничения](#-известные-ограничения)
- [Лицензия](#-лицензия)

## ✨ Возможности

GuardX проверяет игроков на двух уровнях.

### 📡 Пакетный уровень (встроенный слой, PacketEvents необязателен)
Точное время и порядок пакетов, не зависит от TPS сервера. Пакеты читает встроенный слой на Netty; если на сервере стоит PacketEvents, GuardX использует его (`packets.backend: auto`).

| Проверка | Что ловит |
|---|---|
| **Timer** | ускорение времени (баланс по пакетам движения) |
| **AutoClicker** | нереальный CPS и слишком ровные клики |
| **KillAura (мульти-цель)** | два разных удара за один клиентский тик (лишний удар отменяется) |
| **NoSwing** | удар без взмаха руки |
| **BadPackets** | невозможные координаты, углы и слот хотбара, NaN/∞ |
| **PacketFlood** | слишком много пакетов в секунду (флуд-краш), лишние пакеты отбрасываются |
| **ActionSpam** | спам пакетов присеста/бега |
| **Aim** | спинбот и аимбот (одинаковые повороты, повороты не кратны шагу мыши) |

### 🌍 Уровень событий Bukkit
Нужен доступ к миру: блоки, позиции сущностей.

| Группа | Проверки |
|---|---|
| **Движение** | Speed, Fly, NoFall, Step/HighJump, Jesus, Spider, Strafe, Momentum, OmniSprint, Phase/NoClip, **Elytra** (скорость на элитрах), **FoodSprint** (бег при голоде/слепоте) |
| **Бой** | Reach (с компенсацией пинга), KillAura (хитбокс и стены), Criticals, Velocity (AntiKB) |
| **Действия** | NoSlow, MultiAction, FastBow, FastUse, InvMove, Inventory |
| **Блоки** | Scaffold, Nuker, BlockReach, FastBreak (выключена по умолчанию) |

### 🛡 Дополнительно
- Жалобы игроков `/report` с авто-жалобами и порогом.
- Защита от ложных срабатываний при лагах (`lag-protect.min-tps`).
- Откат игрока на последнюю безопасную позицию.
- История нарушений, диагностика, отчёты в файл.
- Красивый лог включения и выключения в консоли.
- Два языка: русский и английский (`language` в конфиге), свои переводы в `lang/`.
- Оповещения в Discord и Telegram, плейсхолдеры PlaceholderAPI, события и API для других плагинов (в том числе свои проверки).
- Постоянная история нарушений и «слои»: отключение проверок по миру, зоне и праву.
- Режим отладки `/guardx debug` для разбора ложных срабатываний.

## 🧩 Совместимость

| | |
|---|---|
| **Сервер** | Paper, Purpur, Pufferfish и другие форки Paper |
| **Minecraft** | 1.16.5 — 1.21.x (версии 26.x и новее не поддерживаются) |
| **Java** | 16+ (для 1.17+ это и так требование игры) |
| **Зависимость** | не требуется. **PacketEvents 2.x** — необязательный: если он есть, GuardX берёт пакеты у него, иначе работает встроенный слой |
| **Не поддерживается** | Folia, чистый Spigot/Bukkit (без `getTPS` отключается только защита от лагов) |

## 📥 Установка

1. Положи `GuardX-AC_v1.0.0_1.16+_Universal.jar` в папку `plugins/`.
2. Перезапусти сервер. Больше ничего ставить не нужно: в консоли будет написано, какой пакетный слой работает.
3. Настрой `plugins/GuardX/config.yml`.
4. *(необязательно)* Поставь **PacketEvents**, если хочешь использовать его вместо встроенного слоя.

Режим выбирается параметром `packets.backend`: `auto` (по умолчанию: PacketEvents, если он есть, иначе встроенный), `packetevents`, `native` (всегда встроенный), `off` (пакетные проверки выключены, остальные работают). Активный слой виден в баннере запуска и в `/guardx diagnostics`.

> 💡 Для начала поставь `punish.enabled: false`, посмотри оповещения и только потом включай наказания.

## 🔨 Сборка

Нужны Java 21 и Maven:

```bash
mvn clean package
```

Результат: `target/GuardX-AC_v1.0.0_1.16+_Universal.jar`.

Версия PacketEvents нужна только для компиляции адаптера и задаётся свойством `packetevents.version` в `pom.xml` (сейчас `2.14.0`); в `plugin.yml` он указан как `softdepend`. Paper API — `1.21-R0.1-SNAPSHOT`.
Для другой версии сервера поменяй `paper-api` в `pom.xml` и `api-version` в `plugin.yml`.

## 💬 Команды и права

| Команда | Описание | Право |
|---|---|---|
| `/guardx help` | список команд | — |
| `/guardx reload` | перезагрузить конфиг | `guardx.admin` |
| `/guardx alerts` | вкл/выкл оповещения для себя | `guardx.alerts` |
| `/guardx debug` | вкл/выкл режим отладки (видно каждое срабатывание) | `guardx.admin` |
| `/guardx info <игрок>` | VL игрока | `guardx.admin` |
| `/guardx inspect <игрок>` | подробный анализ игрока | `guardx.admin` |
| `/guardx reset <игрок>` | сбросить VL | `guardx.admin` |
| `/guardx top` | топ игроков по VL | `guardx.admin` |
| `/guardx history <игрок>` | последние нарушения | `guardx.admin` |
| `/guardx reports [стр]` | список жалоб | `guardx.admin` |
| `/guardx close <id\|игрок>` | закрыть жалобу | `guardx.admin` |
| `/guardx goto <игрок>` | телепорт к игроку | `guardx.admin` |
| `/guardx exempt <игрок> [минуты\|off]` | временно не проверять игрока (1–1440 мин) | `guardx.admin` |
| `/guardx stats` | какие проверки срабатывают чаще всего | `guardx.admin` |
| `/guardx dump <игрок>` | отчёт в файл | `guardx.admin` |
| `/guardx diagnostics` | время работы проверок | `guardx.admin` |
| `/guardx checks` | список проверок | `guardx.admin` |
| `/report <игрок> <причина>` | пожаловаться на читера | `guardx.report` (у всех) |

| Право | По умолчанию | Описание |
|---|---|---|
| `guardx.admin` | OP | управление плагином |
| `guardx.alerts` | OP | получать оповещения |
| `guardx.report` | все | подавать жалобы |
| `guardx.bypass` | никто | обход всех проверок (даже OP, чтобы админы могли тестировать) |

## 📨 Жалобы игроков

- Модераторы сразу получают сообщение: при наведении видно VL подозреваемого, по клику — телепорт.
- К жалобе прикладывается снимок VL, мир и координаты.
- Защита от спама: пауза, запрет повторных жалоб, нельзя жаловаться на себя и админов.
- После жалобы VL подозреваемого растут быстрее (`suspect-vl-multiplier`).
- Если на игрока пожаловались 3 разных игрока за 15 минут — отдельное оповещение.
- Античит сам создаёт авто-жалобу, когда суммарный VL дошёл до порога.
- Жалобы хранятся в `plugins/GuardX/reports.yml`.

## ⚙ Как это работает

- Каждая проверка копит **буфер**; флаг ставится, только когда он переполнен — это защищает от ложных срабатываний при лагах.
- Каждый флаг даёт **VL**. При `punish.vl` (по умолчанию 25) выполняется `punish.command` (по умолчанию kick). VL со временем уменьшается.
- Есть суммарный порог `punish.total-vl` и строгое наказание рецидивистов `punish.repeat`.
- Нарушения движения откатывают игрока на последнюю безопасную точку (`setback: true`).
- Невозможные удары и установка блоков вслепую отменяются.
- Нарушения пишутся в `plugins/GuardX/violations.log`.
- Пакетные проверки работают в сетевом потоке и не трогают мир: всё, что нужно сделать с игроком, уходит в основной поток.

## 🔧 Настройка

Всё лежит в `plugins/GuardX/config.yml`.
Если честные игроки получают флаги, увеличь `buffer` или лимиты у нужной проверки. У каждой проверки есть свой `punish-vl`.

## 🌐 Язык

В `config.yml` задаётся `language: ru` или `language: en`. При первом запуске плагин создаёт `plugins/GuardX/lang/ru.yml` и `en.yml`: там все сообщения игрокам, модераторам и консоли. Файлы можно править. Чтобы добавить свой язык, скопируй `en.yml` в `de.yml`, переведи и поставь `language: de`. Недостающие ключи берутся из встроенного английского. Команда `/guardx reload` перечитывает и конфиг, и язык.


## 📣 Оповещения в Discord

1. В Discord: настройки канала → Интеграции → Вебхуки → Создать → Копировать URL.
2. В `config.yml`:

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/..."
  username: "GuardX"
  notify:
    punish: true
    reports: true
    threshold: true
```

Сообщения уходят в отдельном потоке и не нагружают сервер. Упоминания (`@everyone` и т.п.) в сообщениях отключены.

## ✈ Оповещения в Telegram

Те же события и те же тексты, что и в Discord (общий код отправки, тексты из `lang/`).

1. Создай бота у [@BotFather](https://t.me/BotFather) (`/newbot`) и скопируй токен.
2. Добавь бота в чат, группу или канал (в канале дай право публиковать сообщения). Узнай `chat-id` (для групп и каналов он начинается с `-100`).
3. В `config.yml`:

```yaml
telegram:
  enabled: true
  bot-token: "123456789:AAE..."
  chat-id: "-1001234567890"
  thread-id: 0          # номер темы в группе с темами (0 = без темы)
  silent: false
  proxy: ""             # "host:port", если Telegram недоступен с сервера
  notify:
    punish: true
    reports: true
    threshold: true
```

Проверка: `/guardx notifytest` отправляет тестовое сообщение во все включённые каналы. Токен никогда не попадает в логи.

## 🗂 История нарушений

Все нарушения пишутся в `plugins/GuardX/history/<uuid>.tsv` и доступны после перезапуска сервера и у игроков, которых нет онлайн:
`/guardx history <ник> [страница]`. Настройки в разделе `history` (`persist`, `max-entries`, `retention-days`). Файлы подрезаются до `max-entries` записей, а файлы игроков без новых нарушений удаляются через `retention-days` дней.

## 🧱 Слои (отключение проверок по месту и праву)

Слой отключает выбранные проверки целиком для игроков, которые подходят под все его условия: мир, зона (куб `min`–`max`) и право. Отключённая проверка не считается, не отменяет действия, не даёт VL и не наказывает. Слои работают одинаково для встроенных проверок и для проверок из других плагинов.

```yaml
layers:
  - name: pvp-arena
    worlds: [arena]
    checks: [reach, killaura, velocity]
  - name: vip-lobby
    worlds: [lobby]
    permission: guardx.layer.vip
    checks: ["*"]
```

Какие слои заданы и какие действуют на игрока сейчас: `/guardx layers [игрок]`. Для пакетных проверок слой отключает нарушения (VL и наказание); отмена самих пакетов (например, лимит `PacketFlood`) остаётся.

## 🔌 PlaceholderAPI

Если на сервере стоит PlaceholderAPI, плагин сам регистрирует плейсхолдеры:

| Плейсхолдер | Значение |
|---|---|
| `%guardx_vl%` | суммарный VL игрока |
| `%guardx_vl_<проверка>%` | VL по одной проверке, например `%guardx_vl_speed%` |
| `%guardx_suspect%` | `true`, если игрок под усиленным контролем после жалобы |
| `%guardx_reports_open%` | число открытых жалоб |
| `%guardx_version%` | версия GuardX |

## 🧑‍💻 API для разработчиков

Два события Bukkit в пакете `dev.guardx.api`, оба вызываются в основном потоке и отменяемые:

```java
@EventHandler
public void onFlag(GuardFlagEvent e) {
    // e.getPlayer(), e.getCheck(), e.getInfo(), e.getVl(), e.getTotalAfter()
    if (e.getPlayer().hasPermission("myplugin.vip")) e.setCancelled(true); // не засчитывать
}

@EventHandler
public void onPunish(GuardPunishEvent e) {
    // e.getCheck(), e.getVl(), e.getPunishCount(), e.getCommand()
    // e.setCancelled(true) — команда наказания не выполнится
}
```

### Статический API: `GuardAPI`

Тонкая обёртка над внутренними менеджерами (своей логики нет, всё идёт через те же методы, что и у самого плагина):

```java
UUID id = player.getUniqueId();
GuardAPI.getVL(id, "speed");          // VL по проверке
GuardAPI.getTotalVL(id);              // сумма VL
GuardAPI.getViolations(id);           // проверка -> VL
GuardAPI.getHistory(id, 50);          // из файлов, переживает перезапуск
GuardAPI.flag(player, "my-check", "причина", 1.0);
GuardAPI.setExempt(player, 60_000);   // не проверять 60 секунд (<= 0 — снять)
GuardAPI.isSkippedByLayer(player, "fly");
GuardAPI.resetViolations(id);
```

**Своя проверка:** наследуйся от `Check` и зарегистрируй её. Включение и пороги читаются из `checks.<id>.*` в `config.yml` GuardX, слои и оповещения работают для неё автоматически.

```java
public final class MyCheck extends Check {
    public MyCheck() { super(GuardAPI.plugin(), "my-check"); }

    @Override
    public void onMove(MoveContext c) {
        if (c.horizontal > 5) flag(c.player, c.data, "слишком быстро", 1.0);
    }
}
// onEnable:  GuardAPI.registerCheck(new MyCheck());
// onDisable: GuardAPI.unregisterCheck("my-check");
```

Идентификатор: `a-z 0-9 _ -`, до 32 символов. Встроенные проверки через API снять нельзя.

## 📊 Статистика bStats

GuardX отправляет анонимную статистику на [bStats](https://bstats.org/plugin/bukkit/GuardX/35139): версию плагина, версии сервера и Java, число игроков, страну сервера и два параметра (язык и включены ли наказания). Ников, IP игроков, логов и содержимого конфига там нет. Отключить сбор можно в `plugins/bStats/config.yml` (`enabled: false`), это отключает bStats для всех плагинов на сервере.

## ⚠ Известные ограничения

- **Проект не тестировался на живом сервере.** Сначала проверь на тестовом: с пингом, лагами, льдом, слизью, лестницами.
- **Timer:** сетевой стопор с последующей пачкой пакетов неотличим от Timer, поэтому допускается накопление до `min-balance` мс (по умолчанию 1000). Очень слабый timer (≈1.05x) ловится не сразу.
- **Встроенный пакетный слой:** классы сервера ищутся по имени и типу полей, поэтому он должен работать и на Spigot-маппингах (1.16 — 1.20.4), и на Mojang-маппингах (1.20.5+), но **на живом сервере не проверялся**. Если слой не запустится, GuardX продолжит работу без пакетных проверок и напишет об этом в консоль; запасной вариант — установить PacketEvents. С PacketEvents на Minecraft 26.1+ для нового пакета атаки учитывается только факт удара (NoSwing работает), мульти-цель не ловится без обновления под `WrapperPlayClientAttack`.
- **Компенсация задержки** есть только для целей-игроков. Если у честных игроков с большим пингом бывают флаги Reach, подними `lag-compensation.extra-ms`.
- **Aim (GCD) и FastUse** лучше сначала запустить с `punish.enabled: false`. **FastBreak** выключена по умолчанию и требует калибровки.
- **ScaffoldFolding** до v5 не работал из-за ошибки очистки состояния; теперь включён с `punish-vl: 0` (только оповещения), проверь на живых игроках перед включением наказания.
- Не реализован: X-Ray. NoSlow может ложно срабатывать на предметах с кастомным замедлением.

---

# 🇬🇧 English

## 📖 Contents
- [Features](#-features)
- [Compatibility](#-compatibility)
- [Installation](#-installation)
- [Building](#-building)
- [Commands & permissions](#-commands--permissions)
- [Player reports](#-player-reports)
- [How it works](#-how-it-works)
- [Configuration](#-configuration)
- [Language](#-language)
- [Discord](#-discord-alerts)
- [Telegram](#-telegram-alerts)
- [Violation history](#-persistent-violation-history)
- [Layers](#-layers-disable-checks-by-place-and-permission)
- [PlaceholderAPI](#-placeholderapi-1)
- [Developer API](#-developer-api)
- [bStats statistics](#-bstats-statistics)
- [Known limitations](#-known-limitations)
- [License](#-license)

## ✨ Features

GuardX checks players on two levels.

### 📡 Packet level (built-in layer, PacketEvents optional)
Exact packet timing and order, independent of server TPS. Packets are read by a built-in Netty layer; if PacketEvents is installed GuardX uses it instead (`packets.backend: auto`).

| Check | Detects |
|---|---|
| **Timer** | game speed-up (time balance over movement packets) |
| **AutoClicker** | impossible CPS and overly consistent click intervals |
| **KillAura (multi-target)** | two different attacks in one client tick (the extra hit is cancelled) |
| **NoSwing** | attacks without an arm swing |
| **BadPackets** | impossible positions, angles and hotbar slot, NaN/∞ values |
| **PacketFlood** | too many packets per second (crash flooding); excess packets are dropped |
| **ActionSpam** | sneak/sprint packet spam |
| **Aim** | spinbot and aimbot (constant rotations, rotations not matching mouse step) |

### 🌍 Bukkit event level
Needs world access: blocks and entity positions.

| Group | Checks |
|---|---|
| **Movement** | Speed, Fly, NoFall, Step/HighJump, Jesus, Spider, Strafe, Momentum, OmniSprint, Phase/NoClip, **Elytra** (elytra speed), **FoodSprint** (sprinting while hungry/blind) |
| **Combat** | Reach (ping-compensated), KillAura (hitbox & walls), Criticals, Velocity (AntiKB) |
| **Actions** | NoSlow, MultiAction, FastBow, FastUse, InvMove, Inventory |
| **Blocks** | Scaffold, Nuker, BlockReach, FastBreak (disabled by default) |

### 🛡 Extras
- Player `/report` system with auto-reports and a threshold alert.
- False-positive protection during server lag (`lag-protect.min-tps`).
- Setback to the last safe position.
- Violation history, diagnostics, file reports.
- Clean enable/disable banner in the console.
- Two languages: Russian and English (`language` in the config), custom translations in `lang/`.
- Discord and Telegram alerts, PlaceholderAPI placeholders, events and an API for other plugins (including custom checks).
- Persistent violation history and layers: disable checks by world, area and permission.
- `/guardx debug` mode for investigating false positives.

## 🧩 Compatibility

| | |
|---|---|
| **Server** | Paper, Purpur, Pufferfish and other Paper forks |
| **Minecraft** | 1.16.5 — 1.21.x (26.x and newer are not supported) |
| **Java** | 16+ (already required by the game for 1.17+) |
| **Dependency** | none required. **PacketEvents 2.x** is optional: if present GuardX takes packets from it, otherwise the built-in layer runs |
| **Not supported** | Folia, plain Spigot/Bukkit (without `getTPS` only the lag protection is disabled) |

## 📥 Installation

1. Drop `GuardX-AC_v1.0.0_1.16+_Universal.jar` into `plugins/`.
2. Restart the server. Nothing else is needed: the console shows which packet layer is running.
3. Tune `plugins/GuardX/config.yml`.
4. *(optional)* Install **PacketEvents** if you prefer it over the built-in layer.

The mode is set by `packets.backend`: `auto` (default: PacketEvents if present, otherwise built-in), `packetevents`, `native` (always built-in), `off` (packet checks disabled, everything else keeps working). The active layer is shown in the startup banner and in `/guardx diagnostics`.

> 💡 Start with `punish.enabled: false`, watch the alerts, and only then enable punishments.

## 🔨 Building

Requires Java 21 and Maven:

```bash
mvn clean package
```

Output: `target/GuardX-AC_v1.0.0_1.16+_Universal.jar`.

The PacketEvents version is only needed to compile the adapter and is set by the `packetevents.version` property in `pom.xml` (currently `2.14.0`); `plugin.yml` lists it as `softdepend`. Paper API is `1.21-R0.1-SNAPSHOT`.
For another server version, change `paper-api` in `pom.xml` and `api-version` in `plugin.yml`.

## 💬 Commands & permissions

| Command | Description | Permission |
|---|---|---|
| `/guardx help` | command list | — |
| `/guardx reload` | reload config | `guardx.admin` |
| `/guardx alerts` | toggle alerts for yourself | `guardx.alerts` |
| `/guardx debug` | toggle debug mode (shows every single flag) | `guardx.admin` |
| `/guardx info <player>` | player's VL | `guardx.admin` |
| `/guardx inspect <player>` | detailed player analysis | `guardx.admin` |
| `/guardx reset <player>` | reset VL | `guardx.admin` |
| `/guardx top` | top players by VL | `guardx.admin` |
| `/guardx history <player>` | recent violations | `guardx.admin` |
| `/guardx reports [page]` | list reports | `guardx.admin` |
| `/guardx close <id\|player>` | close a report | `guardx.admin` |
| `/guardx goto <player>` | teleport to player | `guardx.admin` |
| `/guardx exempt <player> [minutes\|off]` | temporarily skip a player (1–1440 min) | `guardx.admin` |
| `/guardx stats` | most frequently triggered checks | `guardx.admin` |
| `/guardx dump <player>` | write a report file | `guardx.admin` |
| `/guardx diagnostics` | check timing stats | `guardx.admin` |
| `/guardx checks` | list registered checks | `guardx.admin` |
| `/report <player> <reason>` | report a cheater | `guardx.report` (everyone) |

| Permission | Default | Description |
|---|---|---|
| `guardx.admin` | OP | manage the plugin |
| `guardx.alerts` | OP | receive alerts |
| `guardx.report` | everyone | file reports |
| `guardx.bypass` | nobody | bypass all checks (even OPs, so admins can test) |

## 📨 Player reports

- Moderators get an instant message: hover to see the suspect's VL, click to teleport.
- Each report includes a VL snapshot, world and coordinates.
- Anti-spam: cooldown, no duplicate reports, no self-reports, no reports on admins.
- After a report the suspect's VL grows faster (`suspect-vl-multiplier`).
- If 3 different players report the same person within 15 minutes, a separate alert fires.
- The anti-cheat files an auto-report when a player's total VL reaches the threshold.
- Reports are stored in `plugins/GuardX/reports.yml`.

## ⚙ How it works

- Every check accumulates a **buffer**; a flag is raised only when it overflows, which protects against false positives under lag.
- Every flag adds **VL**. At `punish.vl` (default 25) `punish.command` runs (kick by default). VL decays over time.
- There is a combined threshold `punish.total-vl` and a harsher punishment for repeat offenders `punish.repeat`.
- Movement violations set the player back to the last safe position (`setback: true`).
- Impossible attacks and blind block placement are cancelled.
- Violations are logged to `plugins/GuardX/violations.log`.
- Packet checks run on the network thread and never touch the world; anything affecting the player is handed to the main thread.

## 🔧 Configuration

Everything lives in `plugins/GuardX/config.yml`.
If legit players get flagged, raise `buffer` or the limits of the relevant check. Each check has its own `punish-vl`.

## 🌐 Language

Set `language: ru` or `language: en` in `config.yml`. On first start the plugin creates `plugins/GuardX/lang/ru.yml` and `en.yml` containing every message shown to players, moderators and the console. You can edit them. To add your own language, copy `en.yml` to `de.yml`, translate it and set `language: de`. Missing keys fall back to the bundled English text. `/guardx reload` reloads both the config and the language.


## 📣 Discord alerts

1. In Discord: channel settings → Integrations → Webhooks → New Webhook → Copy URL.
2. In `config.yml`:

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/..."
  username: "GuardX"
  notify:
    punish: true
    reports: true
    threshold: true
```

Messages are sent from a separate thread and do not load the server. Mentions (`@everyone` etc.) are disabled in the messages.

## ✈ Telegram alerts

The same events and texts as Discord (shared sending code, texts come from `lang/`).

1. Create a bot with [@BotFather](https://t.me/BotFather) (`/newbot`) and copy the token.
2. Add the bot to a chat, group or channel (give it permission to post in a channel). Get the `chat-id` (it starts with `-100` for groups and channels).
3. In `config.yml`:

```yaml
telegram:
  enabled: true
  bot-token: "123456789:AAE..."
  chat-id: "-1001234567890"
  thread-id: 0          # topic number in a group with topics (0 = no topic)
  silent: false
  proxy: ""             # "host:port" if Telegram is unreachable from the server
  notify:
    punish: true
    reports: true
    threshold: true
```

Check it with `/guardx notifytest`, which sends a test message to every enabled channel. The token is never written to logs.

## 🗂 Persistent violation history

Every violation is written to `plugins/GuardX/history/<uuid>.tsv` and stays available after a server restart, including for offline players:
`/guardx history <name> [page]`. Settings are in the `history` section (`persist`, `max-entries`, `retention-days`). Files are trimmed to `max-entries` records and files of players with no new violations are deleted after `retention-days` days.

## 🧱 Layers (disable checks by place and permission)

A layer disables selected checks entirely for players who match all of its conditions: world, area (a `min`–`max` cuboid) and permission. A disabled check is not computed, cancels nothing, gives no VL and punishes nobody. Layers work the same for built-in checks and for checks added by other plugins.

```yaml
layers:
  - name: pvp-arena
    worlds: [arena]
    checks: [reach, killaura, velocity]
  - name: vip-lobby
    worlds: [lobby]
    permission: guardx.layer.vip
    checks: ["*"]
```

See which layers exist and which apply to a player right now with `/guardx layers [player]`. For packet checks a layer suppresses the violations (VL and punishment); cancelling the packets themselves (e.g. the `PacketFlood` limit) still happens.

## 🔌 PlaceholderAPI

If PlaceholderAPI is installed, GuardX registers its placeholders automatically:

| Placeholder | Value |
|---|---|
| `%guardx_vl%` | player's total VL |
| `%guardx_vl_<check>%` | VL of a single check, e.g. `%guardx_vl_speed%` |
| `%guardx_suspect%` | `true` if the player is under increased scrutiny after a report |
| `%guardx_reports_open%` | number of open reports |
| `%guardx_version%` | GuardX version |

## 🧑‍💻 Developer API

Two cancellable Bukkit events in the `dev.guardx.api` package, both fired on the main thread:

```java
@EventHandler
public void onFlag(GuardFlagEvent e) {
    // e.getPlayer(), e.getCheck(), e.getInfo(), e.getVl(), e.getTotalAfter()
    if (e.getPlayer().hasPermission("myplugin.vip")) e.setCancelled(true); // ignore this flag
}

@EventHandler
public void onPunish(GuardPunishEvent e) {
    // e.getCheck(), e.getVl(), e.getPunishCount(), e.getCommand()
    // e.setCancelled(true) -> the punishment command will not run
}
```

### Static API: `GuardAPI`

A thin facade over the internal managers (no logic of its own; it goes through the same methods the plugin uses):

```java
UUID id = player.getUniqueId();
GuardAPI.getVL(id, "speed");          // VL of one check
GuardAPI.getTotalVL(id);              // total VL
GuardAPI.getViolations(id);           // check -> VL
GuardAPI.getHistory(id, 50);          // read from files, survives restarts
GuardAPI.flag(player, "my-check", "reason", 1.0);
GuardAPI.setExempt(player, 60_000);   // skip checks for 60 s (<= 0 clears it)
GuardAPI.isSkippedByLayer(player, "fly");
GuardAPI.resetViolations(id);
```

**Custom check:** extend `Check` and register it. Its enabled flag and thresholds are read from `checks.<id>.*` in GuardX's `config.yml`; layers and alerts work for it automatically.

```java
public final class MyCheck extends Check {
    public MyCheck() { super(GuardAPI.plugin(), "my-check"); }

    @Override
    public void onMove(MoveContext c) {
        if (c.horizontal > 5) flag(c.player, c.data, "too fast", 1.0);
    }
}
// onEnable:  GuardAPI.registerCheck(new MyCheck());
// onDisable: GuardAPI.unregisterCheck("my-check");
```

Id: `a-z 0-9 _ -`, up to 32 characters. Built-in checks cannot be removed through the API.

## 📊 bStats statistics

GuardX sends anonymous statistics to [bStats](https://bstats.org/plugin/bukkit/GuardX/35139): plugin version, server and Java versions, player count, server country and two settings (language and whether punishments are enabled). No player names, player IPs, logs or config contents are sent. You can opt out in `plugins/bStats/config.yml` (`enabled: false`), which disables bStats for every plugin on the server.

## ⚠ Known limitations

- **ScaffoldFolding** did not work before v5 because of a state-cleanup bug; it now ships with `punish-vl: 0` (alerts only). Verify it on live players before enabling punishments.
- **Not tested on a live server.** Try it on a test server first: with ping, lag, ice, slime, ladders.
- **Timer:** a network stall followed by a packet burst is indistinguishable from Timer, so up to `min-balance` ms (default 1000) of idle time is tolerated. A very weak timer (≈1.05x) is not caught quickly.
- **Built-in packet layer:** it finds the server classes by name and field type, so it should survive Spigot-mapped (1.16 – 1.20.4) and Mojang-mapped (1.20.5+) servers, but it has **not been tested on a live server**. If it fails to start, GuardX keeps running without packet checks and says so in the console; installing PacketEvents is the fallback. With PacketEvents on Minecraft 26.1+, for the new attack packet only the fact of a hit is counted (NoSwing works); multi-target is not detected until the code is updated for `WrapperPlayClientAttack`.
- **Lag compensation** applies to player targets only. If legit high-ping players get Reach flags, raise `lag-compensation.extra-ms`.
- Run **Aim (GCD)** and **FastUse** with `punish.enabled: false` first. **FastBreak** is off by default and needs calibration.
- Not implemented: X-Ray. NoSlow may false-flag on items with custom slowdown.

---

## 📄 Лицензия

© 2026 Vunlior. GuardX распространяется по лицензии [Apache License 2.0](LICENSE): можно свободно использовать (в том числе коммерчески), изменять и распространять, сохраняя текст лицензии и файл [NOTICE](NOTICE). Если вы распространяете изменённую версию, пометьте изменённые файлы. ПО предоставляется «как есть», без гарантий. Юридическую силу имеет английский текст в файле [LICENSE](LICENSE). Сторонние компоненты: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 📄 License

© 2026 Vunlior. GuardX is licensed under the [Apache License 2.0](LICENSE): you may use it (including commercially), modify it and redistribute it, as long as you keep the license text and the [NOTICE](NOTICE) file. If you distribute a modified version, mark the files you changed. Provided "as is", without warranty. Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

<div align="center">

Made by **Vunlior** · GuardX 1.0.0

</div>
