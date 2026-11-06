# Third-party notices / Сторонние компоненты

**EN.** GuardX is © 2026 Vunlior, licensed under the Apache License 2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). This file lists the third-party software GuardX is built with or works together with. That software is **not** covered by the GuardX license and stays under its own license, which applies to it independently.

**RU.** GuardX © 2026 Vunlior, лицензия Apache License 2.0 (см. [LICENSE](LICENSE) и [NOTICE](NOTICE)). Здесь перечислено стороннее ПО, с которым GuardX собран или работает вместе. На него **не** распространяется лицензия GuardX: оно остаётся под собственной лицензией, которая действует независимо.

## 1. Included in the GuardX JAR / Входит в JAR

| Component / Компонент | Version | License | Use / Назначение |
|---|---|---|---|
| [bStats Metrics](https://github.com/Bastian/bStats-Metrics) | 3.2.1 | MIT | anonymous usage statistics, relocated to `dev.guardx.libs.bstats` / анонимная статистика, перемещён в `dev.guardx.libs.bstats` |

This is the only third-party code shipped inside the JAR.
Это единственный сторонний код внутри JAR.

## 2. Not included: supplied by the server or by another plugin / Не входит: даёт сервер или другой плагин

GuardX is compiled against these, uses them only through their public APIs and does not copy or redistribute them.
GuardX компилируется с ними, использует их только через публичные API и не копирует и не распространяет их.

| Component / Компонент | Compiled against | Required? | License | Use / Назначение |
|---|---|---|---|---|
| [Paper API](https://papermc.io) | 1.21 | yes / да | see the project / см. проект | server API / API сервера |
| [Netty](https://netty.io) | 4.1.115.Final | yes, present in every server / да, есть в любом сервере | Apache-2.0 | channel access for the built-in packet layer / доступ к каналу для встроенного пакетного слоя |
| [PacketEvents](https://github.com/retrooper/packetevents) | 2.14.0 | **no, optional / нет, необязателен** | see the project / см. проект | alternative packet source, used when installed / альтернативный источник пакетов, используется при наличии |
| [PlaceholderAPI](https://github.com/PlaceholderAPI/PlaceholderAPI) | 2.11.6 | no, optional / нет, необязателен | see the project / см. проект | `%guardx_*%` placeholders / плейсхолдеры `%guardx_*%` |

**EN.** If you install PacketEvents or PlaceholderAPI, you receive them from their authors under their own terms; GuardX does not grant you any rights to them. GuardX also has its own packet layer and works without both of them.

**RU.** Если вы устанавливаете PacketEvents или PlaceholderAPI, вы получаете их от их авторов на их условиях; GuardX не даёт вам никаких прав на них. У GuardX есть собственный пакетный слой, поэтому он работает и без них.

## 3. Build tools / Инструменты сборки

Apache Maven, maven-compiler-plugin and maven-shade-plugin (Apache-2.0) are used to build the JAR and are not part of it.
Apache Maven, maven-compiler-plugin и maven-shade-plugin (Apache-2.0) используются для сборки JAR и в него не входят.

## 4. Trademarks / Товарные знаки

Minecraft is a trademark of Mojang AB / Microsoft. Paper, PacketEvents, PlaceholderAPI, bStats and other names belong to their owners. GuardX is not affiliated with or endorsed by any of them.

Minecraft — товарный знак Mojang AB / Microsoft. Paper, PacketEvents, PlaceholderAPI, bStats и другие названия принадлежат их владельцам. GuardX не связан ни с кем из них и не одобрен ими.

## 5. bStats license text / Текст лицензии bStats

MIT License

Copyright (c) the bStats authors

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Перевод для информации (юридическую силу имеет английский текст): разрешение на использование, копирование, изменение и распространение предоставляется бесплатно при условии сохранения уведомления об авторских правах и этого текста разрешения во всех копиях или существенных частях. ПО предоставляется «как есть», без гарантий; авторы не несут ответственности за убытки, связанные с ним.

## 6. Data sent by bStats / Что отправляет bStats

See the bStats section of the [README](README.md). It can be disabled in `plugins/bStats/config.yml`.
См. раздел про bStats в [README](README.md). Отключается в `plugins/bStats/config.yml`.


## 7. JDBC drivers / JDBC-драйверы

MySQL Connector/J and sqlite-jdbc are compiled against with scope `provided` and are NOT shipped in the JAR; the server supplies them. / Драйверы MySQL Connector/J и sqlite-jdbc подключены как `provided` и НЕ входят в JAR: их даёт сервер.
