# Codebase Map

Mapa de `SH-Telegram`: qué hay, por qué está así y dónde están las trampas.

## System Overview

Es la capa de entrada de Telegram sobre el motor de Secret Hitler, y la única aplicación desplegable
que lo consume. No tiene reglas de juego: las decisiones las toma `SHService`, y aquí se traduce
entre lo que Telegram entiende (chats, mensajes, botones) y lo que entiende el motor (salas,
partidas, jugadores, rondas).

```
Telegram ──update──► Commons-Telegram                    SH-Telegram                  SH-Engine
                     UpdateDispatcher
                       └─ AuthUpdateInterceptor ──► sesión (SecurityContext + TelegramContext)
                            │
                            ▼
                     CommandHandler / CallbackQueryHandler
                            │
                            ▼
                     SHApplicationServiceImpl ──► SHTelegramService ─────────────► SHService
                            │                            │
                            ▼                            ▼
                     BotMessageService            telegram_room / telegram_game
                     (envíos a Telegram)          telegram_player  (equivalencias)
```

**Lo que separa este bot del de CAH**: aquí casi todo es secreto. El rol, las leyes que ve el
presidente, la ley que descarta, el partido que revela una investigación y las tres leyes que espía
del mazo van **solo** al chat privado de una persona. Mandar una de esas cosas al grupo no rompe una
pantalla: arruina la partida entera, y no hay compilador que lo detecte. Por eso el `RecordingBotMessageService`
de los tests no está para comprobar que se envía, sino **a quién**.

## Directory Structure

```
src/main/java/org/themarioga/telegram/sh/
├── SHTelegramApplication.java      # arranque
├── config/
│   ├── BotProperties.java          # nombre, alias, versión, ayuda (sh.telegram.*)
│   ├── SHTelegramBotsConfig.java   # el bean del bot, por modo (long polling / webhook)
│   ├── ErrorMessageResolver.java   # ErrorEnum → texto traducido
│   └── SecurityConfig.java         # solo abre el webhook y el reto de Let's Encrypt
├── models/                         # equivalencias con Telegram
│   ├── TelegramRoom.java           # grupo ↔ sala                      (permanente)
│   ├── TelegramGame.java           # mensajes de una partida            (efímera)
│   └── TelegramPlayer.java         # mensajes privados de un jugador    (efímera)
├── dao/{intf,impl}/
├── services/{intf,impl}/           # TelegramGameService, SHTelegramRoomResolver
├── exceptions/TelegramErrorEnum.java  # errores de esta capa, códigos desde 100
└── game/
    ├── app/SHApplicationServiceImpl.java     # 9 comandos + 23 callbacks
    └── service/{intf,impl}/SHTelegramService # todo el comportamiento del bot

src/main/resources/db/migration/{h2,mariadb}/V1/
├── V1.0.0_1__Baseline.sql          # generado desde las entidades, no escrito a mano (17 tablas)
└── V1.0.0_2__Languages_and_tags.sql # 145 tags × 2 idiomas

src/test/java/.../tools/SchemaGenerator.java  # regenera el baseline
```

## Module Guide

### models — las tres equivalencias

Lo importante es **qué es permanente y qué es efímero**:

| Tabla | Vive | Por qué |
|---|---|---|
| `telegram_room` | siempre | el grupo sobrevive a las partidas y se reutiliza |
| `telegram_game` | lo que dura la partida | los identificadores de mensaje son de *esa* partida |
| `telegram_player` | lo que dura la partida | el mensaje del rol y el de la acción pendiente |

`telegram_game` guarda **cuatro** identificadores de mensaje, y que sean cuatro y no uno es una
decisión, no un descuido:

| Campo | Qué es |
|---|---|
| `first_message_id` | el menú de la partida en el grupo |
| `creator_message_id` | el privado desde el que el creador la configura |
| `board_message_id` | el tablero: leyes promulgadas, elecciones fallidas, vivos, veto |
| `current_round_message_id` | la fase en curso (nominación, votación, legislación) |

El tablero y la fase son mensajes distintos porque el primero es acumulativo —interesa tenerlo a la
vista toda la partida— y el segundo es volátil. En un único mensaje, cada votación empujaría el
tablero fuera de la pantalla. Y cada ronda **estrena** mensaje de fase, que luego se edita dentro de
esa ronda, para que no se entierre bajo la conversación del grupo.

`telegram_player` guarda dos: el del rol, que se envía una vez al empezar y no se toca más, y el de
la acción pendiente, que se reescribe cada vez que a ese jugador le toca algo y **se cierra al
usarse** (se sobrescribe con la confirmación) para que no queden botones vivos de una fase pasada.

### config/ErrorMessageResolver

Todas las excepciones del motor llevan su `ErrorEnum`, así que traducirlas es una convención y no una
escalera de `catch`: `X` → tag `ERROR_X`. A diferencia del de CAH, aquí no hace falta ninguna
excepción a la regla: el catálogo i18n de SH se escribió **después** que los enums, y los nombres se
eligieron para cuadrar.

Si el error no tiene texto se devuelve el genérico. `I18NService` devuelve el propio nombre del tag
cuando no lo encuentra, y enseñarle `ERROR_USER_ID_EMPTY` a un jugador es peor que decirle que algo
ha fallado.

### game/app — la tabla de comandos y callbacks

Un envoltorio por tipo de entrada (`privateCommand`, `groupCommand`, `callback`) evita repetir en
cada entrada la comprobación del tipo de chat, el `try/catch` y el `answerCallbackQuery` — que es en
lo que se le fue medio fichero al equivalente de CAH.

### game/service — el bot

Un solo fichero grande, ordenado por fases: usuario → lobby → ronda → poderes y veto →
administración → menús y tablero → sesión y permisos → errores → textos.

Tres cosas que conviene saber antes de tocarlo:

- **Trabaja sobre dos chats a la vez**, el grupo y el privado de cada jugador, y ninguno de esos
  identificadores está en las entidades del motor.
- **Quién decide qué toca después es el estado de la ronda, no el bot.** El motor cierra la votación
  con el último voto, promulga, desbloquea poderes y termina la partida; el bot lee el estado en que
  queda y ramifica.
- **`guarded()` elige cómo avisar**: si el update vino de un botón contesta a la pulsación (sin
  ensuciar el grupo); si vino de un comando, con un mensaje al privado.

## Data Flow

### Una partida entera

```
/create en grupo
  └─ resolver sala (crea Room + telegram_room la primera vez)
     └─ envíos asíncronos → ids de mensaje → SHService.createGame(room)
        └─ telegram_game + menús en grupo y en el privado del creador

game_join  ×N  → SHService.addPlayer(room)     → mensaje privado del jugador
game_start     → SHService.startGame(room)     → reparte roles y arranca la ronda 1
                 └─ rol a cada privado (los fascistas se conocen; Hitler solo con 5 o 6
                    jugadores) + tablero al grupo

── ronda ──────────────────────────────────────────────────────────────────────
sh_chancellor  → candidatos al PRIVADO del presidente; nomina
sh_vote        → votación en el GRUPO (el sentido del voto es público en este juego)
                 mientras está abierta solo se dice cuántos han votado: un recuento
                 parcial delataría el voto del último
                 └─ el último voto cierra la votación y deja la ronda en:
                    CHANCELLOR_REJECTED       → recuento + contador + [Siguiente ronda]
                    HITLER_ELECTED_CHANCELLOR → fin de partida
                    PRESIDENT_DISCARDING_LAW  → sesión legislativa
sh_discard     → 3 leyes al PRIVADO del presidente
sh_enact       → 2 leyes al PRIVADO del canciller (+ [Vetar] si está desbloqueado)
                 └─ tras promulgar, la ronda queda en:
                    ENDING                  → fin de partida
                    DOING_ADDITIONAL_ACTION → poder ejecutivo al presidente
                    ARGUING                 → [Siguiente ronda]
sh_next_round  → SHService.nextRound(game)
```

### Los tres sitios donde una sola llamada puede terminar la partida

1. `voteChancellor` — el último voto puede elegir a Hitler canciller.
2. `chancellorSelectsLaw` — la ley promulgada puede cruzar el umbral.
3. `nextRound` — con 3 elecciones fallidas promulga una ley sola, **y esa ley puede terminar la
   partida**. Es el camino más fácil de olvidar, y además el motor lo hace en silencio: el bot lo
   detecta comparando el recuento de leyes antes y después.

Y un cuarto que no es una transición de ronda: `killPlayer`, si el ejecutado era Hitler.

### Fin de partida, en este orden exacto

1. Leer el resultado y **recoger todos los ids de mensaje** de `telegram_game` y `telegram_player`.
2. Anunciar en el grupo: quién gana, por qué, y el rol de cada jugador.
3. Limpiar los privados.
4. `telegramGameService.deleteGameData(game)` y `gameService.endGame(game)`.

Al revés no hay a quién escribir: una vez borrada la fila padre, las relaciones JPA ya no se pueden
recorrer.

### Identidad

`telegramUserId → telegram_user → User`. El `User.username` es el alias en minúsculas, o
`tg:<telegramId>` si no tiene; el `User.name` es el nombre visible. **Ninguno de los dos es un id de
chat**: para escribir a alguien hay que volver por `telegram_user`.

## Conventions

- **`intf`/`impl`** en dao y services, como el resto del reactor.
- **Los identificadores del motor son `UUID`.** Los `long` que aparecen en las firmas son ids de chat
  o de usuario de Telegram. Confundirlos es la fuente número uno de bugs.
- **Todo texto que vea el usuario sale de un tag i18n**, nunca de un literal en el código.
- **Toda decisión secreta va por el privado del que decide.** No es una preferencia de diseño: es la
  regla del juego.
- **Quién puede actuar lo comprueba el motor**, no el bot. Lo que sí hace el bot es no ofrecerle el
  botón a quien no le toca.
- **El baseline no se escribe a mano**: se regenera con `SchemaGenerator` para los dos dialectos, y
  `ddl-auto=validate` + `SchemaBaselineTest` fallan si el esquema y el modelo dejan de cuadrar.

## Gotchas

1. **Las claves de comando y de `callback_data` son contrato.** Telegram guarda los botones dentro de
   los mensajes indefinidamente, así que renombrar una clave rompe las partidas en curso.
   `SHApplicationServiceTest` fija los dos conjuntos exactos.
2. **El caché de i18n no se invalida.** `I18NServiceImpl` carga todos los pares `Lang`×`Tag` en el
   constructor: tocar la tabla `tag` en caliente no se ve hasta reiniciar. Y el `\n` literal del SQL
   se convierte en salto de línea real al cargar.
3. **`createOrUpdate` (merge) no sirve para dar de alta** entidades con identificador derivado, como
   `TelegramGame` y `TelegramPlayer`. Para eso está `create` (persist).
4. **Espiar el mazo necesita un botón aunque no elija nada.** El motor comprueba quién actúa contra el
   usuario de la sesión, y la sesión de quien acaba de promulgar es la del **canciller**: sin
   `sh_action__POLICY_PEEK` en el privado del presidente, el bot no tendría con qué dispararlo.
5. **`ENABLE_VETO` no es una elección.** Con cinco leyes fascistas el motor devuelve
   `[EXECUTION, ENABLE_VETO]`, pero el veto ya lo ha activado él solo: se cae de la lista antes de
   decidir si hace falta menú, y solo se anuncia. Ofrecerlo como botón dejaría la ronda colgada en
   `DOING_ADDITIONAL_ACTION` para siempre, porque no hay método de motor que llamar.
6. **Un veto aceptado es un gobierno fallido**, no un final de ronda distinto: el motor deja la ronda
   en `CHANCELLOR_REJECTED`, el mismo estado que un voto perdido.
7. **El contador de elecciones fallidas se enseña +1** al rechazar el gobierno, porque el motor no lo
   avanza hasta el `nextRound` siguiente y al jugador hay que enseñarle ya cómo queda.
8. **Los nombres se leen antes de llamar al motor.** Investigar, ejecutar y la elección especial
   vuelven a mezclar la partida en la sesión de Hibernate; leer `getUser().getName()` después es
   pedir problemas. Recorrer `game.getPlayers()` mientras se vota lanza directamente
   `ConcurrentModificationException`.
9. **La difusión mide el mensaje antes de salir.** Cada envío que Telegram rechaza marca ese chat como
   inactivo, así que un `/sendmessagetoeveryone` vacío daría de baja a toda la base de datos.
10. **Comprobar el rol de administrador va dentro del `guarded`**, no delante: si no, la excepción se
    escapa a la tabla de comandos, que solo la apunta en el log, y quien lo intenta no ve nada.
11. **Las llamadas a Telegram están dentro de la transacción.** En long polling no puede agotar el
    pool porque se atiende un update cada vez; en webhook es un riesgo real, todavía sin medir.
12. **No ejecutes el perfil `check`**: el formateador compartido une las líneas partidas y no las
    vuelve a partir, y como el perfil modifica ficheros se lleva por delante el formato del reactor.
13. **Nada se ha probado contra Telegram**: ni un token real, ni un grupo, ni el modo webhook.

## Testing

82 tests, todos contra H2 real con el baseline y el catálogo i18n cargados, y con la mensajería
sustituida por una que apunta lo que se envía en vez de enviarlo.

| Test | Qué cubre |
|---|---|
| `GameFlowTest` | el lobby: crear, unirse, configurar, echar, salir, arrancar, borrar |
| `RoundFlowTest` | la ronda completa y la victoria liberal, con el mazo forzado a leyes liberales |
| `PowerFlowTest` | los poderes con 7 jugadores, el veto y tres de las cuatro victorias |
| `PolicyPeekFlowTest` | espiar el mazo, que solo sale en mesas de 5 o 6 |
| `AdminFlowTest` | los cinco comandos de administración, por los dos lados de la puerta |
| `HardeningTest` | mesa de 10: límites de Telegram y jugadores sin `@alias` |
| `SchemaBaselineTest` | el esquema cuadra con las entidades y el catálogo está completo |
| `SHApplicationServiceTest` | los conjuntos exactos de claves de comando y callback |

El soporte vive en `support/`: `BotFlowTest` monta la sesión a mano (lo que hace el interceptor en
producción) y `PlayedGameTest` juega partidas — fuerza el mazo por tipo, encadena leyes y resuelve
los poderes leyéndolos del propio teclado que recibió el presidente, que de paso comprueba que el bot
ofrece el que toca en el privado que toca.

## Navigation Guide

| Si buscas… | Mira en |
|---|---|
| Qué comandos y botones existen | `game/app/SHApplicationServiceImpl` |
| Cómo se compone un mensaje | los métodos privados de `SHTelegramServiceImpl` |
| Por qué un error sale traducido así | `config/ErrorMessageResolver` y `V1.0.0_2__Languages_and_tags.sql` |
| De dónde sale el chat al que se escribe | `chatIdOf(User)` y `TelegramGameService.getChatId(Room)` |
| Cómo se prueba todo esto | `src/test/java/.../support/` y los `*FlowTest` |
| Cómo regenerar el esquema | `src/test/java/.../tools/SchemaGenerator` |
| El porqué de cada decisión | `../../docs/specs/SH-Telegram-PLAN.md` del superproyecto |
