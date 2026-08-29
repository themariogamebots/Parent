## Codebase Overview

SH-Telegram es la **aplicación** (Maven `org.themarioga:sh-telegram`) que expone el motor de Secret
Hitler en Telegram, y el único consumidor de `sh-engine`. Levanta **un solo bot** —Secret Hitler no
tiene el equivalente al bot de diccionarios de CAH—, que trabaja a la vez en el grupo (la mesa) y en
el chat privado de cada jugador. Depende de `sh-engine` para las reglas y de `commons-telegram` para
la identidad, la sesión y el despacho de updates.

Tiene **su propia base de datos**, separada de la de CAH-Telegram: las dos `Game` mapearían a la
misma tabla si compartieran una.

**Stack**: Java, Spring Boot 4.1, Hibernate/JPA, Flyway (MariaDB y H2), Spring Security (contexto
programático, sin login), `org.telegram:telegrambots-*`, JUnit 5.

**Structure**: `config` (propiedades del bot, seguridad, traducción de errores) → `models`
(equivalencias entre Telegram y el motor: `TelegramRoom`, `TelegramGame`, `TelegramPlayer`) → `dao` →
`services` → `game`, con su `app` (tabla de 9 comandos y 23 callbacks) y su `service` (orquestación
del motor y composición de los mensajes).

⚠️ Cosas que conviene saber antes de tocar nada:

- **Mandar algo al chat equivocado arruina la partida.** El rol, las tres leyes que ve el presidente,
  la que descarta, el partido que revela una investigación y las leyes espiadas del mazo van **solo**
  al privado de una persona. No hay compilador que lo detecte: la red son los tests, que comprueban
  a quién se envía cada cosa y no solo que se envíe.
- **Quién decide qué toca después es el estado de la ronda, no el bot.** El motor cierra la votación
  con el último voto, promulga, desbloquea poderes y termina la partida; el bot lee el estado en que
  la deja y ramifica. Hay tres llamadas que pueden terminar la partida por su cuenta —`voteChancellor`,
  `chancellorSelectsLaw` y `nextRound`— más `killPlayer` si el ejecutado era Hitler.
- **La tabla de comandos y callbacks es contrato.** Los botones viven dentro de mensajes que Telegram
  guarda indefinidamente: cambiar una clave rompe partidas en marcha. Un test fija los dos conjuntos.
- **El identificador de un usuario o de una sala del motor NO es un id de chat.** Son `UUID`; el chat
  se resuelve por `telegram_user` y `telegram_room`.
- **Nada se ha probado contra un bot real**: ni long polling con un token, ni webhook.
- **No ejecutes el perfil `check`**: el formateador compartido une las líneas partidas y no las
  vuelve a partir.

Para la arquitectura, el flujo de una partida y las trampas concretas, ver
[docs/CODEBASE_MAP.md](docs/CODEBASE_MAP.md). El plan del que salió este proyecto, con el porqué de
cada decisión, está en
[`../docs/specs/SH-Telegram-PLAN.md`](../docs/specs/SH-Telegram-PLAN.md) del superproyecto.
