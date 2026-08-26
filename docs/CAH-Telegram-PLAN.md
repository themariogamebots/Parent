# Plan de implementación: CAH-Telegram

> Proyecto nuevo `CAH-Telegram`: capa de presentación/entrada de Telegram sobre `CAH-Engine`.
> Parent: `org.themarioga:parent:2.0.0` (el BOM del reactor). Dependencias: `Commons-Telegram` + `cah-engine`.
> Fecha: 2026-08-26 · Decisiones de diseño: **cerradas** (§3)

---

## 1. Objetivo y alcance

Construir la **implementación Telegram** del juego CAH, sabiendo que **no será la única** (habrá otras
plataformas: web, Discord…). Todo lo que no sea específico de CAH queda reutilizable en
`Commons-Telegram`, para que `SH-Telegram` lo aproveche cuando exista.

El proyecto contiene **dos bots** (los dos que hoy viven en `Bots/`):

| Bot | Rol | Chats |
|---|---|---|
| **CCLH bot** (juego) | Crear/configurar/jugar partidas | Grupo (partida) + privado (mano de cartas) |
| **Dictionaries bot** | Gestión de diccionarios, cartas y colaboradores | Solo privado |

**Requisito duro:** `CCLHApplicationServiceImpl` y `DictionariesApplicationServiceImpl` (la tabla de
comandos y callbacks de Telegram) se mantienen **100% retrocompatibles**: mismos nombres de comando,
mismas claves de callback, misma semántica de `__` para los datos. Lo que cambia por debajo es a quién
llaman.

**Fuera de alcance:** cambiar reglas de juego (viven en `CAH-Engine`) y `SH-Telegram` (pero se deja
preparado el terreno común).

---

## 2. Punto de partida

### 2.1 Lo que se reutiliza tal cual

- **`Commons-Engine`** — `User`, `Room`, `Lang`, `UserService`, `RoomService`, `I18NService`,
  `SecurityUtils`, `UserDetails`, `UserRole`, `AbstractHibernateDao`. PKs `UUID` (`Base`).
- **`CAH-Engine`** — `CAHService` (fachada de orquestación), `GameService`/`PlayerService`/`RoundService`,
  `DictionaryService`/`CardService`, entidades `Game`/`Round`/`Player`/`PlayedCard`/`Dictionary`/`Card`.
  **Clave:** `CAHServiceImpl` toma el `Room` **por parámetro** y el `User` **de la sesión de seguridad**
  (`SecurityUtils.getUser()`, `CAHServiceImpl.java:479`). Eso define el contrato que la capa de Telegram
  tiene que cumplir.
- **`Commons-Telegram`** — `ApplicationService`, `BotService`, `BotMessageService`,
  `LongPollingBotServiceImpl`, `WebhookBotServiceImpl`, `BotMessageUtils`, `BotCreationUtils`,
  `LetsEncryptConfig`.

### 2.2 Lo que se migra sin tocar

- `cah/game/app/CCLHApplicationServiceImpl.java` (483 líneas).
- `cah/dictionaries/app/DictionariesApplicationServiceImpl.java` (858 líneas).

### 2.3 Lo que se tira

- `CCLHBotServiceImpl` (1496 l.) y `DictionariesBotServiceImpl` (1684 l.) — vieja interfaz entre lógica
  de juego y fachada de Telegram, obsoleta tras el refactor de `Commons-Engine`. **Se reescriben**; el
  renderizado de mensajes y teclados se canibaliza (§9.5, §9.6).
- `TelegramGameServiceImpl` / `TelegramPlayerServiceImpl` — **no compilan** (referencias a
  `gameService`/`tableService`/`GameTypeEnum` inexistentes, variables duplicadas, `TelegramGame.room`
  que no existe). Sirven como boceto.
- `TgSecurityUtils` — vacío (4 líneas).
- `CCLHBotConfig` / `DictionariesBotConfig` — se rehacen (§9.4).

### 2.4 Problemas del intento anterior que este plan corrige

1. **`TelegramGame` mezclaba dos conceptos**: su `id` era el chat de Telegram *y* apuntaba a un `Game`.
   Imposible representar "este grupo ya jugó antes y ahora juega otra partida". Se separa en
   `telegram_room` (permanente) y `telegram_game` (efímera) — §6.
2. **No había sesión**: cada método recibía `userId` suelto y el engine ya no acepta eso.
3. **Estaba en `Bots/`**, mezclado con lo que será SH. Ahora: lo genérico a `Commons-Telegram`, lo de
   CAH a `CAH-Telegram`.
4. **`User.name` hacía de identidad y de nombre visible a la vez**, con `UserAlreadyExistsException`
   garantizada en cuanto dos personas se llaman igual. Se resuelve con el split de §3/D2.

### 2.5 ⚠️ Estado del esquema

Las migraciones de `CAH-Engine/src/main/resources/db/migration/**` están **desincronizadas del modelo
actual de entidades**:

- Los `V2.0.0_*.sql` contienen `create Game t_lang`, `alter Game t_user` — un *search & replace* de
  `table`→`Game` que rompió el SQL. **No ejecutan.**
- Las tablas son `t_user`, `t_game`, `t_player` con PK `BIGINT`; las entidades actuales son `Users`,
  `Room`, `Game`… con PK `UUID`. No hay correspondencia.
- `V1.0.0_6__TelegramTables.sql` ya crea `t_telegram_game`/`t_telegram_player` con el diseño viejo.

Y **hay datos de producción que conservar** (diccionarios y cartas de usuarios). Esto convierte la
migración en una fase con peso propio: **F2** (§9.2).

> 🔎 Hallazgo que abarata la migración: en el esquema viejo, `t_user.id` **es el id de usuario de
> Telegram** (`CCLHBotServiceImpl:94` llama a `createOrReactivate(userId, …)` con el id de Telegram).
> Es decir, la tabla `telegram_user` nueva se puede poblar directamente desde el `t_user` viejo sin
> pedirle nada a nadie.

---

## 3. Decisiones de diseño (cerradas)

| # | Decisión | Resuelto |
|---|---|---|
| D1 | La identidad de Telegram (tabla de mapeo, `UserDetails`, utils) vive en **`Commons-Telegram`**, no en `CAH-Telegram` | ✔ |
| D2 | **`User` se parte en `name` + `username`** | ✔ |
| D3 | **`Room` se parte igual: `name` + `roomname`** | ✔ |
| D4 | Se añade **`CAHService.createGame(Room)`** | ✔ |
| D5 | El contexto del chat va en sesión; la **`Room` se resuelve perezosa** | ✔ |
| D6 | `TelegramUserDetails` al `SecurityContext` + `TelegramContext` aparte, con una fachada única | ✔ |
| D7 | Registro **solo con `/start`** | ✔ |
| D8 | **Un único despliegue** con los dos bots | ✔ |
| D9 | Colisión de `@alias`: **se lo lleva el nuevo dueño** | ✔ |
| D10 | Paquete de `Commons-Telegram` → **`org.themarioga.commons.telegram`** | ✔ |
| D11 | **`Bots/` sale del reactor ya** (F0) | ✔ |

### D1 — La identidad de Telegram vive en `Commons-Telegram`

La tabla `telegram_user`, el `TelegramUserDetails`, el `TelegramSecurityUtils` y el servicio de
login/registro son **agnósticos del juego**: sirven igual a CAH que a SH. Solo lo específico de CAH
(`telegram_room`, `telegram_game`, `telegram_player`, renderizado de cartas) va en `CAH-Telegram`.

Implicación: **`Commons-Telegram` pasa a depender de `engine-commons`** (hoy no depende). La dirección
es correcta — `engine-commons` no sabe nada de Telegram — y el parent ya trae
`spring-boot-starter-data-jpa` a todos los módulos.

### D2 — `User` se parte en `name` + `username`

```java
@Entity
@Table(name = "Users", indexes = {
        @Index(columnList = "name"),
        @Index(columnList = "username", unique = true)})
public class User extends Base {
    @Column(length = 64, nullable = false, unique = true)
    private String username;      // identidad: único, estable, multiplataforma
    @Column(length = 256, nullable = false)
    private String name;          // nombre visible: ni único ni estable
    ...
}
```

Para Telegram:

| Campo | Valor | Ejemplo |
|---|---|---|
| `username` | `@alias` **en minúsculas y sin la `@`**, o `tg:<telegramId>` si no tiene alias | `themarioga` · `tg:123456789` |
| `name` | Nombre visible construido por `BotMessageUtils.getUsername(from)` | `Mario García (@themarioga)` |

Detalles que importan:

- **Minúsculas**: Telegram trata los alias como *case-insensitive*, así que normalizar en minúsculas es
  lo que hace que el índice único se comporte como espera el usuario.
- **El prefijo `tg:` no puede colisionar** con un alias real: Telegram no admite `:` en los alias.
- `/deletegamebyusername` y `/add_collab` siguen funcionando **igual que siempre** con
  `userService.getByUsername(...)`, normalizando la entrada (quitar `@` inicial, minúsculas).

### D3 — `Room` se parte igual

`Room.name` = título visible del grupo (duplicable), `Room.roomname` = `tg:<chatId>` (único). Simétrico
con `User`, y evita que dos grupos con el mismo título compartan `Room`, que es exactamente lo que
pasaría hoy con `createOrReactivate(chatTitle)`.

### D4 — `CAHService.createGame(Room)`

Cambio aditivo en `CAH-Engine`: `createGame(Room)` con la lógica actual menos la creación de sala, y
`createGame(String)` delegando en ella. La capa Telegram crea/resuelve `Room` + fila de mapeo y se la
pasa al engine **en la misma transacción**, sin ventanas en las que exista una `Room` sin mapeo.

### D5 — La `Room` en sesión: contexto sí, resolución perezosa

**Sí** a guardar el contexto del chat; **no** a resolver la `Room` de BD en cada update:

- Resolverla *eagerly* implica un SELECT extra en **cada** update, incluidos los del chat privado
  (jugar carta, votar, menús de diccionarios) donde no hay `Room` en juego.
- El caso difícil no es el grupo, es el **privado**: cuando juegas una carta por privado hay que saber
  a qué partida pertenece, y eso **no se resuelve por chat sino por jugador**
  (`playerService.findByUser`).

Diseño: un `TelegramContext` en `ThreadLocal` con los datos crudos del update — `chatId`, `chatType`,
`chatTitle`, `messageId`, `callbackQueryId`, `botName` — y un campo `room` **perezoso y cacheado
durante la petición**:

```java
Room room = TelegramSecurityUtils.getRoom();   // resuelve la 1ª vez; null si el chat es privado
```

Los handlers dejan de arrastrar `chatId` a mano, no se paga el SELECT si no se usa, y cada hilo tiene
el suyo cuando llegue la concurrencia real del modo webhook.

### D6 — Dónde va cada cosa de la sesión

`TelegramUserDetails extends UserDetails` (el de `engine-commons`) va al `SecurityContextHolder`,
porque es lo que `CAHServiceImpl` y `I18NServiceImpl` leen vía `SecurityUtils`. Los datos del *chat*
(que no son identidad) van al `TelegramContext`. **`TelegramSecurityUtils` es el acceso único a las dos
cosas**, para que en el código de bot nunca haya que tocar `SecurityContextHolder` a pelo.

### D7 — Registro solo con `/start`

Es además lo correcto: para jugar hace falta que el bot pueda escribirte por privado, cosa imposible
sin un `/start` previo. Cualquier otro comando de un usuario sin mapeo responde con un tag i18n
`ERROR_USER_NOT_REGISTERED` invitando a hacer `/start` en privado.

### D8 — Un único despliegue

Una app Spring Boot con los dos bots (`cclh.bot.enabled`, `dictionaries.bot.enabled`), compartiendo BD
y tabla `telegram_user`: quien se registra en el bot de diccionarios ya está registrado para jugar.

### D9 — Colisión de `@alias`: se lo lleva el nuevo dueño

Si A libera `@foo` y B lo coge, al conectarse B el `username` `foo` todavía pertenece a la fila de A.
**Política:** degradar la fila antigua a `tg:<suTelegramId>` y asignar el alias al dueño actual.
Telegram garantiza un único dueño vivo, así que el estado refleja la realidad. El algoritmo, con el
orden de `flush` que exige el índice único, está en §7.4.

### D10 — Rename de paquete en `Commons-Telegram`

`org.themarioga.game.*` → `org.themarioga.commons.telegram.*`. Es un rename mecánico y su único
consumidor es `Bots/`, que sale del reactor en F0. Hacerlo **antes** de que exista `SH-Telegram`.

### D11 — `Bots/` fuera del reactor en F0

Se saca de `<modules>` desde el primer día. El código sigue en disco y en git para canibalizar el
renderizado de mensajes, pero deja de compilarse (cosa que hoy, además, no hace).

---

## 4. Arquitectura destino

```
                        ┌──────────────────────────────────────────┐
   Telegram Update ───► │ Commons-Telegram                         │
                        │  UpdateDispatcher                        │
                        │   └─ AuthUpdateInterceptor ──────────────┼──► telegram_user (mapeo)
                        │        · resuelve TelegramUser           │        │
                        │        · SecurityUtils.setUserDetails()  │        ▼
                        │        · TelegramContextHolder.set()     │   engine-commons: User + Lang
                        │        · finally → clear()               │
                        └───────────────────┬──────────────────────┘
                                            │ CommandHandler / CallbackQueryHandler
                        ┌───────────────────▼──────────────────────┐
                        │ CAH-Telegram                             │
                        │  CCLHApplicationServiceImpl  (comandos)  │  ◄── se mantiene 100%
                        │  DictionariesApplicationServiceImpl      │  ◄── se mantiene 100%
                        │        │                                 │
                        │        ▼                                 │
                        │  CCLHTelegramService (orquesta + render) │──► telegram_room / telegram_game
                        │  DictionariesTelegramService             │    telegram_player
                        └───────────────────┬──────────────────────┘
                                            ▼
                        ┌──────────────────────────────────────────┐
                        │ CAH-Engine: CAHService (Room por         │
                        │             parámetro, User de sesión)   │
                        └──────────────────────────────────────────┘
```

**Regla de oro:** `CAH-Telegram` **nunca** habla de `chatId` con el engine, y el engine **nunca** sabe
qué es Telegram. La traducción `chatId ↔ Room` y `telegramUserId ↔ User` ocurre solo en la frontera.

---

## 5. Reparto por módulo

### 5.1 Cambios en `Commons-Engine` (F1)

Radio de impacto verificado: **pequeño**. `UserDaoImpl.getByUsername` ya consulta `u.name` (es cambiar
una palabra) y el único llamador productivo de la búsqueda de sala por nombre es `CAHServiceImpl:82-84`.

| Fichero | Cambio |
|---|---|
| `models/User.java` | + `username` (64, `nullable=false`, `unique=true`); `name` deja de ser identidad; índice único nuevo |
| `models/Room.java` | + `roomname` (128, `nullable=false`, `unique=true`); `name` = título visible |
| `dao/impl/UserDaoImpl.java` | `getByUsername` → `WHERE u.username = :username` (hoy `u.name`) |
| `dao/impl/RoomDaoImpl.java` | `getRoomName` → `getByRoomname`, `WHERE r.roomname = :roomname` |
| `dao/intf/UserDao.java`, `RoomDao.java` | Firmas correspondientes |
| `services/intf/UserService.java` | `createOrReactivate(String username, String name, Lang)`; **+ `setUsername(User, String)`**; `rename(User, String)` se queda (nombre visible) |
| `services/impl/UserServiceImpl.java` | La deduplicación de `createOrReactivate` pasa a mirar `username`; `UserAlreadyExistsException` solo si el **username** existe y está activo |
| `services/intf/RoomService.java` | `createOrReactivate(String roomname, String name)`, `getByRoomname(String)`; `rename` se queda |
| `services/impl/RoomServiceImpl.java` | Ídem |
| `security/UserDetails.java` | `getUsername()` → `user.getUsername()` (**hoy devuelve `user.getName()`**, que dejaría a Spring Security con un "username" no único) |
| `security/SecurityUtils.java` | `getName()` sigue devolviendo el visible; **+ `getUsername()`** |
| Tests | `UserServiceTest`, `UserDaoTest`, `RoomServiceTest`, `RoomDaoTest` |

### 5.2 Cambios en `CAH-Engine` (F1)

- `CAHService` / `CAHServiceImpl`: **+ `Game createGame(Room room)`**; `createGame(String roomName)`
  delega en ella (`createOrReactivate(roomName, roomName)`), y se conserva para tests y otras
  plataformas.
- Tests: fixtures dbunit (`user.xml`, `room.xml`) y `cahschema.dtd` con las columnas nuevas; los tests
  `testCreateGame_ExistingRoom`/`_ReactivateRoom` pasan a buscar por `roomname`.

### 5.2 bis — Cambios en `SH-Engine` (F1) ⚠️ no previsto en la primera versión del plan

`SH-Engine` también depende de `engine-commons` y replica el mismo bloque de creación de sala, así que
el split le afecta igual. Cambios aplicados, idénticos a los de CAH:

- `SHService` / `SHServiceImpl`: `createGame(String)` busca por `roomname` y delega en una sobrecarga
  **`createGame(Room)`** nueva (ya deja el terreno listo para `SH-Telegram`).
- Fixtures dbunit + `shschema.dtd` + las dos llamadas a `createOrReactivate` de `SHServiceTest`.

> Trampa detectada al ejecutar: `maven-compiler-plugin` **no recompila** un módulo cuyas fuentes no han
> cambiado aunque sí haya cambiado la librería de la que depende. `SH-Engine` pasaba la compilación con
> bytecode viejo y fallaba luego en los tests con errores engañosos. **Verificar siempre con
> `mvn clean install`, no con `mvn install`.**

### 5.3 Nuevo en `Commons-Telegram` (F3) — genérico, lo hereda SH-Telegram

| Clase | Qué hace |
|---|---|
| `TelegramUser` (entidad) | `id` (Long, PK = id de Telegram), `user` (`@ManyToOne User`), `languageCode`, `lastSeen` |
| `TelegramUserDao` / `Impl` | `getByIdFetchingUser(Long)` con `JOIN FETCH tu.user u JOIN FETCH u.lang` (**crítico**, R1); `getByUser(User)` |
| `TelegramUserService` / `Impl` | `register(from)` → crea `User` + fila de mapeo; `login(from)` → carga, refresca `name`/`username` y aplica D9 |
| `TelegramUserDetails` | `extends UserDetails`; añade `telegramId`, `alias`, `languageCode` |
| `TelegramContext` | POJO: `chatId`, `chatType`, `chatTitle`, `messageId`, `callbackQueryId`, `botName`, `Room room` (perezoso) |
| `TelegramContextHolder` | `ThreadLocal<TelegramContext>` con `set`/`get`/`clear` |
| `TelegramSecurityUtils` | Fachada estática: `getTelegramId()`, `getAlias()`, `getUser()`, `getChatId()`, `isPrivate()`, `getRoom()`, `isRegistered()` |
| `TelegramRoomResolver` | Interfaz de un método `Room resolveRoom(long chatId, String title)`; la implementa cada juego. Permite `getRoom()` perezoso sin que Utils sepa de CAH |
| `UpdateDispatcher` | Extrae el bucle `consume`/`onWebhookUpdateReceived` (hoy **duplicado** en las dos impls) y lo rodea de interceptores |
| `UpdateInterceptor` | `before(Update)` / `after(Update)` |
| `AuthUpdateInterceptor` | §7.3 |

**Nota sobre `telegram_user`:** con el split de D2, el `@alias` vive en `User.username` y el nombre
visible en `User.name`, así que la tabla de mapeo **no los duplica**. Se queda mínima: id de Telegram,
`user_id`, `language_code`, `last_seen`.

**Arreglos** de paso en `Commons-Telegram`:
- Rename de paquete (D10).
- `pendingReplies` → `ConcurrentHashMap` (en webhook hay varios hilos de Tomcat).
- `BotMessageUtils.getReceivedCommand` indexa `pendingReplies` por `message.getChatId()` mientras
  `setPendingReply` lo indexa por `userId`: en privado coinciden, en grupo no. Unificar a `chatId`.
- `BotConstants`: añadir `TELEGRAM_MESSAGE_TYPE_GROUP` / `SUPERGROUP` (hoy solo existe `private`).

### 5.4 Nuevo en `CAH-Telegram`

| Clase | Paquete | Qué hace |
|---|---|---|
| `CAHTelegramApplication` | `org.themarioga.telegram.cah` | `@SpringBootApplication(scanBasePackages="org.themarioga")`, `@EntityScan("org.themarioga")` |
| `TelegramRoom` (entidad) | `…cah.model` | `id` (Long, PK = chatId), `room` (`@ManyToOne Room`) |
| `TelegramGame` (entidad) | `…cah.game.model` | `game` (`@OneToOne Game`, PK), `firstMessageId`, `creatorMessageId`, `currentRoundMessageId` |
| `TelegramPlayer` (entidad) | `…cah.game.model` | `player` (`@OneToOne Player`, PK), `handMessageId` |
| DAOs + servicios de los 3 | `…cah.*.dao` / `.service` | Sobre `AbstractHibernateDao`, patrón `intf`/`impl` del reactor |
| `CAHTelegramRoomResolver` | `…cah.service.impl` | Implementa `TelegramRoomResolver` sobre `telegram_room` |
| `CCLHApplicationServiceImpl` | `…cah.game.app` | **Migrado tal cual** |
| `CCLHTelegramService` (+impl) | `…cah.game.service` | Sustituto de `CCLHBotService`: orquesta `CAHService` + render |
| `DictionariesApplicationServiceImpl` | `…cah.dictionaries.app` | **Migrado tal cual** |
| `DictionariesTelegramService` (+impl) | `…cah.dictionaries.service` | Sustituto de `DictionariesBotService` |
| `CAHTelegramBotsConfig` | `…cah.config` | Beans de los dos bots (§9.4) |
| `BotProperties` | `…cah.config` | `@ConfigurationProperties("cah.telegram")`: nombre, alias, versión, ownerId, ownerAlias, helpUrl, dictionariesPerPage… (hoy salen de `t_configuration` vía un `ConfigurationService` que **ya no existe**) |
| `ErrorMessageResolver` | `…cah.config` | Mapa `ErrorEnum → tag i18n`, compartido por los dos bots |
| `SecurityConfig` | `…cah.config` | Migrado de `Bots/security/SecurityConfig` |

---

## 6. Modelo de datos nuevo

```
-- Commons-Engine (modificadas)
Users
  id            UUID PK
  username      VARCHAR(64)  NOT NULL UNIQUE   -- NUEVO: identidad ("themarioga" | "tg:123456789")
  name          VARCHAR(256) NOT NULL          -- nombre visible, duplicable
  active        BOOLEAN NOT NULL
  lang_id       ... FK
  creation_date TIMESTAMP NOT NULL

Room
  id            UUID PK
  roomname      VARCHAR(128) NOT NULL UNIQUE   -- NUEVO: identidad ("tg:-1001234567890")
  name          VARCHAR(256) NOT NULL          -- título visible del grupo, duplicable
  active        BOOLEAN NOT NULL
  creation_date TIMESTAMP NOT NULL

-- Commons-Telegram (compartida CAH/SH)
-- El alias y el nombre visible NO se duplican aquí: viven en Users.username / Users.name
telegram_user
  id            BIGINT PK                      -- id de usuario de Telegram
  user_id       UUID NOT NULL UNIQUE FK → Users(id)
  language_code VARCHAR(8)
  last_seen     TIMESTAMP

-- CAH-Telegram
telegram_room                                  -- permanente: el grupo sobrevive a las partidas
  id            BIGINT PK                      -- id de chat de grupo
  room_id       UUID NOT NULL UNIQUE FK → Room(id)

telegram_game                                  -- efímera: vive lo que la partida
  game_id                  UUID PK FK → Game(id) ON DELETE CASCADE
  first_message_id         INT NOT NULL        -- mensaje del grupo con el menú/join
  creator_message_id       INT NOT NULL        -- privado de configuración del creador
  current_round_message_id INT                 -- mensaje de la carta negra de la ronda

telegram_player                                -- efímera
  player_id       UUID PK FK → Player(id) ON DELETE CASCADE
  hand_message_id INT NOT NULL                 -- privado con la mano de cartas
```

**Por qué `telegram_room` y `telegram_game` separadas:** el grupo (y su `Room`) es permanente y se
reutiliza partida tras partida; los `message_id` son de **una** partida concreta y se borran con ella.
Meterlo todo en una tabla — el intento anterior — obliga a inventar filas fantasma entre partidas.

**Consultas que hay que soportar:**
- `telegram_game` por `game.creator` → `/deletemygames`
- `telegram_game` todas → `/deleteallgames` (y avisar a cada grupo)
- `telegram_player` por `player.game` → repartir manos, limpiar al terminar
- `telegram_user` por `user_id` → escribir por privado a un `User` del engine

---

## 7. Gestión de usuario y sesión

### 7.1 Normalización del username

```java
static String usernameOf(org.telegram.telegrambots.meta.api.objects.User from) {
    return StringUtils.hasText(from.getUserName())
            ? from.getUserName().toLowerCase(Locale.ROOT)   // Telegram es case-insensitive
            : "tg:" + from.getId();                          // ':' imposible en un alias real
}
```

La entrada del usuario en `/deletegamebyusername` y `/add_collab` se normaliza igual, quitando además
una `@` inicial.

### 7.2 Alta (`/start` en privado)

```
/start ──► AuthUpdateInterceptor: no hay mapeo → contexto "anónimo con datos de Telegram"
       ──► CCLHApplicationServiceImpl."/start"          (sin cambios de firma)
       ──► CCLHTelegramService.registerUser()
             @Transactional {
               lang = i18NService.getLanguage(from.getLanguageCode())      // fallback al idioma por defecto
               user = userService.createOrReactivate(usernameOf(from),
                                                     BotMessageUtils.getUsername(from), lang)
               telegramUserDao.create(new TelegramUser(from.getId(), user, from.getLanguageCode()))
             }
       ──► bienvenida + ayuda
```

Idempotente: si ya existe el mapeo, `/start` **no falla** — refresca datos y reenvía la bienvenida. Si
el `User` existe pero está inactivo, `createOrReactivate` lo reactiva solo.

### 7.3 Sesión por petición (`AuthUpdateInterceptor`)

Una vez por update, antes de despachar el handler, en `Commons-Telegram`:

```java
try {
    TelegramContextHolder.set(TelegramContext.from(update, botName));   // chat, mensaje, callback id
    TelegramUser tgUser = telegramUserService.login(from);              // 1 query con JOIN FETCH
    if (tgUser != null) {
        SecurityUtils.setUserDetails(new TelegramUserDetails(tgUser));  // → SecurityContextHolder
    }
    dispatch(update);
} finally {
    SecurityContextHolder.clearContext();
    TelegramContextHolder.clear();
}
```

- **El `finally` no es opcional.** En long-polling el hilo se reutiliza para todos los updates; en
  webhook, los de Tomcat se reciclan entre peticiones. Sin limpiar, el usuario N+1 hereda la sesión del
  usuario N. Es el fallo más peligroso del diseño (R2).
- Los handlers que hoy llaman `cclhBotService.loginUser(id)` como primera línea **siguen compilando y
  funcionando**: `loginUser` pasa a ser un verificador no-op
  (`if (!TelegramSecurityUtils.isRegistered()) throw new UserNotRegisteredException()`), lo que
  preserva la retrocompatibilidad literal de `CCLHApplicationServiceImpl` sin tocar una línea.

### 7.4 `login` con refresco de datos y robo de alias (D9)

```java
@Transactional
public TelegramUser login(org.telegram...User from) {
    TelegramUser tg = telegramUserDao.getByIdFetchingUser(from.getId());   // JOIN FETCH user + lang
    if (tg == null) return null;                                           // no registrado → /start

    User user    = tg.getUser();
    String uname = usernameOf(from);

    if (!uname.equals(user.getUsername())) {
        User squatter = userDao.getByUsername(uname);                      // ¿lo tiene otro?
        if (squatter != null && !squatter.getId().equals(user.getId())) {
            TelegramUser old = telegramUserDao.getByUser(squatter);
            userService.setUsername(squatter,
                    old != null ? "tg:" + old.getId() : "free:" + squatter.getId());
            telegramUserDao.flush();          // libera el índice único ANTES del siguiente UPDATE
        }
        userService.setUsername(user, uname);
    }

    String display = BotMessageUtils.getUsername(from);
    if (!display.equals(user.getName())) userService.rename(user, display);

    tg.setLastSeen(new Date());
    return tg;
}
```

El `flush()` intermedio es lo que evita el `ConstraintViolationException`: sin él, Hibernate puede
ordenar los dos `UPDATE` al revés y chocar con el índice único.

### 7.5 Acceso a los datos desde el código de juego

```java
User    user    = SecurityUtils.getUser();             // el engine lo hace solo
String  uname   = SecurityUtils.getUsername();         // identidad
String  display = SecurityUtils.getName();             // nombre visible
Long    tgId    = TelegramSecurityUtils.getTelegramId();
long    chatId  = TelegramSecurityUtils.getChatId();
boolean priv    = TelegramSecurityUtils.isPrivate();
Room    room    = TelegramSecurityUtils.getRoom();     // perezoso; null en privado
```

---

## 8. Gestión de sala

### 8.1 Resolución `chatId → Room`

```java
// CAHTelegramRoomResolver (implementa TelegramRoomResolver)
@Transactional
public Room resolveRoom(long chatId, String title) {
    TelegramRoom tgRoom = telegramRoomDao.getById(chatId);
    if (tgRoom == null) {
        Room room = roomService.createOrReactivate("tg:" + chatId, title);   // D3
        tgRoom = telegramRoomDao.create(new TelegramRoom(chatId, room));
    } else if (title != null && !title.equals(tgRoom.getRoom().getName())) {
        roomService.rename(tgRoom.getRoom(), title);                          // el grupo se renombró
    }
    return tgRoom.getRoom();
}
```

Se invoca **solo** cuando hace falta: `/create` y callbacks de grupo. Nunca en privado. El camino
inverso (`Room → chatId`, necesario para escribir al grupo desde un evento del privado) es
`telegramRoomDao.getByRoom(room).getId()`.

### 8.2 Flujo de `/create`

```
/create en grupo
  ├─ interceptor: sesión lista (si no está registrado → "haz /start en privado")
  ├─ CCLHTelegramService.startCreatingGame()
  │    @Transactional {
  │      room = roomResolver.resolveRoom(chatId, chatTitle)   // crea Room + telegram_room si es la 1ª vez
  │      game = cahService.createGame(room)                   // D4; creador = SecurityUtils.getUser()
  │    }
  ├─ mensaje al grupo             → firstMessageId
  ├─ mensaje al creador (privado) → creatorMessageId
  └─ telegramGameService.create(game, firstMessageId, creatorMessageId)
```

Excepciones del engine a traducir a i18n: `GameAlreadyExistsException` (ya hay partida en el grupo),
`GameCreatorAlreadyExistsException` (ya tienes una partida abierta en otro grupo),
`RoomNotActiveException`, `UserNotActiveException`.

### 8.3 Del privado al grupo

Cuando un jugador juega una carta **por privado**, hay que actualizar el mensaje **del grupo**:

```
player = playerService.findByUser(SecurityUtils.getUser())
game   = player.getGame()
chatId = telegramRoomDao.getByRoom(game.getRoom()).getId()
msgId  = telegramGameDao.getByGame(game).getCurrentRoundMessageId()
```

Por eso `telegram_room` y `telegram_game` necesitan acceso bidireccional indexado.

---

## 9. Plan de trabajo por fases

Cada fase deja el reactor **compilando** (`mvn -q install` desde la raíz).

### F0 — Preparación del reactor (½ jornada) ✅ HECHA

- [x] Sacar `Bots` de `<modules>` del pom raíz (D11). El directorio se queda en disco como referencia.
- [x] Crear `CAH-Telegram/` + `git init` (cada módulo del reactor tiene su repo).
- [x] `pom.xml` del módulo: parent `org.themarioga:parent:2.0.0`, dependencias `Commons-Telegram` +
      `cah-engine` + `flyway-core` + `flyway-mysql`; perfiles `dev` (H2) / `pre` / `pro` (driver
      MariaDB, que `Bots/pom.xml` **no declaraba** en ningún perfil).
- [x] Añadir `<module>CAH-Telegram</module>` al pom raíz.
- [x] `CAHTelegramApplication` adelantada de F4: el módulo sin main class no empaqueta
      (`spring-boot-maven-plugin` falla con «Unable to find main class»).

**Aceptación:** ✅ `mvn clean install` construye el reactor entero con `CAH-Telegram` dentro y sin `Bots`.

Desviaciones respecto a lo planeado:
- **No** se ha añadido `cah-telegram` al `dependencyManagement` del BOM: el BOM versiona *librerías* que
  otros consumen, y esto es una aplicación ejecutable. `Bots` tampoco estaba.
- Entorno: no hay `mvn` en el `PATH`; se usa el Maven 3.9.16 que trae IntelliJ en
  `~/.local/share/JetBrains/Toolbox/apps/intellij-idea/plugins/maven-plugin/lib/maven3/bin`.
- **Spring Boot 4.1 movió anotaciones de sitio**: `@EntityScan` está ahora en
  `org.springframework.boot.persistence.autoconfigure`, y `SecurityAutoConfiguration` en
  `org.springframework.boot.security.autoconfigure`. Es la causa de dos de los errores de compilación
  de `Bots`, y hay que tenerlo presente al migrar `SecurityConfig` en F4.

### F1 — Split de identidad en `Commons-Engine` + `createGame(Room)` (1 jornada) ✅ HECHA

- [x] `User.username` / `Room.roomname` y toda la lista de §5.1.
- [x] `UserDetails.getUsername()` → `user.getUsername()`; `SecurityUtils.getUsername()`.
- [x] `CAHService.createGame(Room)` (§5.2) y `SHService.createGame(Room)` (§5.2 bis).
- [x] Tests actualizados, con los casos nuevos: username duplicado → excepción, **nombre visible
      duplicado → permitido** (tanto en `User` como en `Room`), y `setUsername` como primitiva del
      robo de alias (D9).

**Aceptación:** ✅ `mvn clean -P test install` verde de punta a punta — **335 tests** (49 commons,
207 CAH, 79 SH), 0 fallos.

Arreglos de paso, no planeados:
- `Room.toString()` imprimía `"Room{id=" + getName()` — devolvía el nombre como si fuera el id.
- `TagDaoTest` estaba **roto de antes** (falla en `master`, sin relación con el split): `TagDaoImpl`
  inyecta el `EntityManager` por constructor sobre un campo `final` y `@InjectMocks` no le hacía llegar
  el mock stubbeado. Se construye a mano en un `@BeforeEach`. Bloqueaba el build con tests.

### F2 — Esquema y datos legacy ✅ HECHA (falta el pase contra MariaDB real)

⚠️ **El backup de producción se perdió casi entero.** Lo que sobrevive es un backup **anterior, de la
versión 0.1.0**, en dos CSV (`dictionaries.csv` y `cards.csv`), más los `INSERT` de los tags dentro de
las migraciones viejas. No hay backup de usuarios, ni de colaboradores, ni de partidas.

- [x] **Baseline `V3.0.0_1__Baseline.sql`** para MariaDB y H2, **generado desde las entidades** con
      `CAH-Telegram/src/test/java/.../tools/SchemaGenerator.java`, aplicando las mismas naming
      strategies que Spring Boot 4.1 usa en runtime. 17 tablas y 43 claves ajenas. Escribirlo a mano
      con la herencia `TABLE_PER_CLASS` de `Game`/`Player` habría sido pedir problemas.
- [x] **`V3.0.0_2__Languages_and_tags.sql`**: los 2 idiomas y los tags i18n recuperados de los
      `INSERT` de las migraciones `V2.0.0`, que estaban intactos aunque el DDL que los rodeaba
      estuviera corrompido.
      > ⚠️ **Corregido en F5**: esta primera recuperación solo miró las migraciones de
      > `Commons-Engine` y se dejó fuera **134 de los 180** tags que usa el código, que vivían en las
      > de `CAH-Engine`. Ver F5.
- [x] **Histórico V1/V2 retirado del classpath** a `<módulo>/docs/legacy-db-migration/` con un README
      que explica por qué. Seguía publicándose dentro de los jars y Flyway lo ejecutaba.
- [x] **Conversor `CAH-Telegram/tools/legacy_data_migration.py`**: CSV → SQL, para MariaDB y H2.
- [x] **Ensayo end-to-end sobre H2 limpia**: baseline + tags + datos legacy, todo aplicado sin errores
      y verificado con consultas (recuentos, integridad referencial y escapado de comillas).
- [ ] **Pase contra una MariaDB real**: el script `mariadb` sale del mismo generador pero **no se ha
      ejecutado contra MariaDB**, solo contra H2. Falta hacerlo antes de tocar producción.
- [ ] **Poner los dos CSV a salvo**: hoy son el único backup que queda y viven sueltos en el
      escritorio.

**Resultado del ensayo (verificado, no estimado):**

| | |
|---|---|
| Usuarios sintetizados | 65 (+ sus 65 filas de `telegram_user`) |
| Diccionarios | 71 de 132 (21 publicados) |
| Cartas | 14.565 = 12.147 blancas + 2.418 negras |
| Idiomas / tags | 2 / 96 |
| Cartas huérfanas, diccionarios sin creador, diccionarios vacíos | 0 / 0 / 0 |
| Diccionario por defecto | `00000000-0000-4000-a000-000000000001` → "Clasico", publicado, 405+60 ✅ |

Decisiones de la fase:

- **Alcance**: solo los 71 diccionarios con cartas. Los otros 61 son fichas creadas y abandonadas.
- **Idioma**: todos `es`.
- **UUID deterministas** derivados del id antiguo, con un "nodo" distinto por tipo de entidad
  (`a000` diccionarios, `a001` usuarios, `a002` cartas). Hace el script reejecutable sin duplicar,
  permite rastrear qué fila era cuál, y es lo que hace posible fijar `cah.game.default-dictionary-id`
  en la configuración.
- **Usuarios sintetizados**: `username = name = "tg:<id de telegram>"`. Como el `creator_id` del
  backup **ya era el id de Telegram**, la tabla de equivalencias sale directa. Y no es un apaño
  permanente: el `login()` de F3 le pone a cada uno su alias y su nombre real la primera vez que
  vuelva a escribir al bot. **La base de datos se arregla sola con el uso.**
- **El baseline vive en `CAH-Telegram`, no repartido por módulos.** El esquema es propiedad de la
  aplicación desplegada (de qué entidades usa), no de las librerías: `SH-Telegram` tendrá un conjunto
  de entidades distinto y necesitará su propio baseline.
- **Fechas ausentes** (60 diccionarios y 6.786 cartas) → `2020-01-01`, anterior a cualquier dato real.
- **Los colaboradores se pierden**: no hay CSV.

Dos hallazgos bloqueantes descubiertos al arrancar el contexto, ambos para **F4**:

1. **Los dos starters de Telegram chocan.** `telegrambots-springboot-longpolling-starter` y
   `...-webhook-starter` registran los dos un bean `telegramBotsApplication`, y Boot 4 no permite
   override de beans: con los dos en el classpath **la aplicación no arranca**. `Bots` nunca lo
   detectó porque ni compilaba. Hay que decidir cómo resolverlo (§12).
2. **Flyway no se ejecutaba.** En Boot 4 la autoconfiguración de Flyway se sacó a un módulo aparte,
   `org.springframework.boot:spring-boot-flyway`: con solo `flyway-core` (que es lo que declaraba
   `Bots`) las migraciones se ignoran en silencio, sin un solo aviso en el log.

### F3 — Identidad y sesión en `Commons-Telegram` (2 jornadas) ✅ HECHA

- [x] Rename de paquete a `org.themarioga.commons.telegram` (D10), alineando de paso los subpaquetes
      con la convención del motor (`model`→`models`, `service`→`services`).
- [x] Dependencia `engine-commons`.
- [x] `TelegramUser` + DAO con `JOIN FETCH` de `user.lang` (R1).
- [x] `TelegramUserService` (`register` / `login` con D9, §7.4) y `TelegramUserUtils` con la
      normalización de alias (§7.1).
- [x] `TelegramUserDetails`, `TelegramContext`, `TelegramContextHolder`, `TelegramSecurityUtils`,
      `TelegramRoomResolver`.
- [x] `UpdateDispatcher` + `UpdateInterceptor` + `AuthUpdateInterceptor`; las dos impls de bot delegan
      en el dispatcher (elimina la duplicación línea por línea que había).
- [x] Arreglos: `ConcurrentHashMap`, clave de `pendingReplies` unificada a `chatId`, constantes de
      tipo de chat.
- [x] **17 tests nuevos**: registro idempotente, alias sintético, refresco de nombre visible, cambio de
      mayúsculas que no es cambio, **robo de alias en el orden correcto** (D9, verificado con
      `InOrder` incluido el `flush`), robo a un usuario de otra plataforma, **el contexto queda limpio
      tras cada update** (R2), login que revienta sin tumbar el update, rol de admin, y que la sala
      **no se resuelve en privado** y **solo una vez** en grupo.

**Aceptación:** ✅ `mvn clean -P test install` verde — **352 tests** en el reactor entero.

Decisiones tomadas sobre la marcha:
- **`telegram_user` se queda más pequeña de lo planeado**: con el split de D2 el alias vive en
  `User.username` y el nombre visible en `User.name`, así que la tabla de equivalencias no los duplica
  (solo `id`, `user_id`, `language_code`, `last_seen`). §6 actualizado.
- **`TelegramUserUtils` es una clase aparte** en vez de ampliar `BotMessageUtils`, porque el
  `getUsername(User)` que ya existe allí devuelve el **nombre visible** y no puedo renombrarlo sin
  tocar los `ApplicationServiceImpl` que deben quedarse igual. Tener `usernameOf` al lado de
  `getUsername` con significados distintos sería una trampa garantizada.
- **Rol de admin por configuración**: `telegram.bots.admin-ids` (lista de ids de Telegram) en el
  interceptor. `SecurityUtils.isAdmin()` ya existía en commons pero era inalcanzable desde Telegram, y
  los comandos de administración de F6 lo necesitan.
- `TelegramUserDao` extiende `AbstractHibernateDao`, cuya API base (`findOne(UUID)`, `deleteById`) no
  aplica a una entidad con clave `Long`. Se usan los buscadores propios; conviene no llamar a los
  heredados.

Arreglo de paso, no planeado:
- **`Base.equals`/`Base.hashCode` reventaban con NPE** en cualquier entidad todavía sin persistir (id y
  `creationDate` nulos). Comparar o meter en un `Set` una entidad nueva era un NPE seguro. Ahora son
  null-safe con `Objects.equals`/`Objects.hash`, sin cambiar la semántica cuando los valores existen.

### F4 — Esqueleto de `CAH-Telegram` y arranque (1 jornada) ✅ HECHA

- [x] `CAHTelegramApplication`, `SecurityConfig`, `BotProperties`, `application.properties` (migradas
      de `Bots/`, sustituyendo lo que venía de `t_configuration`).
- [x] Entidades + DAOs + servicios de `TelegramRoom`, `TelegramGame`, `TelegramPlayer`, con las
      consultas que necesitarán los comandos de administración de F6 (`getByCreator`, `getAll`).
- [x] `CAHTelegramRoomResolver` + baseline regenerado con las 3 tablas nuevas (20 tablas en total).
- [x] `CAHTelegramBotsConfig` rehecho: un `@Bean` por bot y modo, sin nombres repetidos.
- [x] **Ciclo de dependencias roto** y `spring.main.allow-circular-references` eliminado.
- [x] **12 tests** en el módulo: esquema, catálogo i18n, cableado de los dos bots y resolutor de salas.

**Aceptación:** ✅ `mvn clean -P test install` verde — **364 tests** en el reactor.

Los dos problemas que impedían arrancar, resueltos:

1. **Conflicto de starters** → `TelegramBotsRegistrarConfig` en `Commons-Telegram` sustituye a las dos
   autoconfiguraciones, que se excluyen con `spring.autoconfigure.exclude`. Se eligió esta opción
   (frente al modo global excluyendo una) tras mirar qué hacían: **son envoltorios triviales**, un
   `@Bean` y un registrador, así que "mantenerlo nosotros" no cuesta prácticamente nada y elimina el
   conflicto de raíz en vez de esquivarlo. Un test comprueba que solo existe un objeto de aplicación
   y que el bean `telegramBotsApplication` ya no aparece.
2. **El ciclo `ApplicationService` ↔ `BotService`** (que `allow-circular-references` no arreglaba: esa
   opción solo actúa sobre inyección por campo o setter, no por constructor). Se rompió separando las
   dos razones por las que se pedía el bot entero:
   - `BotMessageServiceImpl` pasa a recibir `(TelegramClient, botName)` en vez de `BotService`.
   - Las respuestas pendientes salen de `BotService` a un `PendingReplyRegistry` propio, al que
     acceden por igual el bot y la lógica de juego.

   El orden queda en un solo sentido: `TelegramClient → BotMessageService → ApplicationService → bot`.

Hallazgos y decisiones de la fase:

- **El modo webhook nunca pudo funcionar en el proyecto antiguo.** El starter de webhook **no publica
  ningún endpoint HTTP**: solo expone un `receiveUpdate(path, update)` que alguien tiene que llamar, y
  en `Bots` no había ningún controlador. La configuración de seguridad abría `POST /callback/**` y
  detrás no había nada. Ahora lo atiende `TelegramWebhookController`, y la URL a declarar en Telegram
  es `<host>/callback/<botName>`.
- **El registro de bots tolera fallos**: `registerBot` llama a Telegram para validar el token, así que
  un token inválido o una caída de Telegram al arrancar tumbaría todo el despliegue. Se registra el
  error y se sigue con los demás bots.
- **Los clientes y los servicios de mensajería son por bot** y solo se crean si ese bot está
  habilitado; así un despliegue con un único bot no necesita el token del otro.
- **`BotService` adelgaza**: fuera `setPendingReply` y `getPendingReplies`.
- Los `ApplicationService` reales llegan en F5 y F6; el test de cableado usa uno vacío. Lo que se
  prueba aquí es el arranque, no los comandos.

⚠️ Lo que **no** cubren los tests: el registro real contra la API de Telegram (los tests usan tokens
falsos y el 401 se registra como error), y el modo webhook de punta a punta.

### F5 — Bot de diccionarios (2–3 jornadas) 🔶 EN CURSO

Antes que el de juego: solo usa chat privado, no necesita `Room` ni partidas, y valida de punta a punta
la sesión.

- [ ] Migrar `DictionariesApplicationServiceImpl` **sin cambios**.
- [ ] `DictionariesTelegramServiceImpl` (~57 métodos) sobre `DictionaryService`/`CardService`. El
      renderizado se canibaliza de `DictionariesBotServiceImpl` (1684 l.), sustituyendo el acceso a
      datos por las APIs nuevas y los `long id` por `UUID`.
- [ ] `ErrorMessageResolver` (`ErrorEnum → tag i18n`), reutilizable por los dos bots.
- [x] **Auditoría i18n** ✅ — se hizo la primera, porque sin textos no hay nada que portar:
      - El catálogo pasa de **96 a 366 textos** (183 tags × 2 idiomas, simétricos). Faltaban **134**:
        la recuperación de F2 solo miró las migraciones de `Commons-Engine`, y los textos del bot de
        diccionarios estaban en las de `CAH-Engine` (`V2.0.0_2`, `_4` y `_5`). Sin esto, el bot
        habría enseñado el nombre del tag en crudo en casi todas sus pantallas.
      - **Cuatro tags** aparecían en las dos fuentes con textos distintos. Se escoge la variante
        acentuada y la que concuerda con el significado: `ERROR_PLAYER_ALREADY_VOTED_DELETION` decía
        *"Ya has votado una carta"*, que es de otra cosa (y en inglés, el mismo error).
      - **Ocho textos son nuevos** porque el código los pedía y no existían en ninguna migración:
        `UNKNOWN_ERROR` y los tres `COLLABORATOR_ADD_*`, en los dos idiomas.
      - **Tres tags son erratas del código viejo** (`GAME_ONLY_CREATOR_CAN_DELETE` en vez de
        `ERROR_GAME_ONLY_CREATOR_CAN_DELETE`, `PLAYER_DOES_NOT_EXISTS` y `DICTIONARY_NOT_PUBLISHED`):
        al usuario se le enseñaba el nombre del tag. No se añaden; el código nuevo usa el correcto.
      - Dos tests lo blindan: que estén los textos del bot de diccionarios, y que los dos idiomas
        tengan el mismo número de tags.

**Aceptación:** flujo manual completo: crear diccionario → añadir cartas → publicar → compartir →
colaborar.

### F6 — Bot de juego (4–5 jornadas)

- [ ] Migrar `CCLHApplicationServiceImpl` **sin cambios**.
- [ ] `CCLHTelegramServiceImpl` por bloques:
      1. `/start`, `/lang`, `/help` (comparte código con F5)
      2. `/create` + menú de configuración (modo, puntuación, diccionario, nº jugadores, rondas/puntos)
      3. join/leave + arranque + reparto de manos por privado
      4. ronda: carta negra al grupo, mano por privado, `play_card`, `vote_card`, cierre de ronda,
         marcador, fin de partida
      5. borrados: `game_delete_group`, `game_delete_private`, `/deletemygames`,
         `/deletegamebyusername` (vía `getByUsername` normalizado, D2), `/deleteallgames`
      6. admin: `/sendmessagetoeveryone`, `/toggleglobalmessages`
- [ ] Al terminar/borrar partida: limpiar `telegram_game` y `telegram_player`.

**Aceptación:** partida completa de 3 jugadores en un grupo de pruebas, en los dos modos de puntuación.

### F7 — Endurecimiento (1–2 jornadas)

- [ ] Modo webhook probado (es donde aparece la concurrencia real).
- [ ] Revisión de transacciones: una por acción de negocio, con las llamadas a la API de Telegram
      **fuera** (R4).
- [ ] Tests de integración con dbunit siguiendo el patrón de `CAH-Engine`.
- [ ] Perfil `pro`: Let's Encrypt, SSL, `dependency-check`.

### F8 — Documentación y cierre

- [ ] `CLAUDE.md` + `docs/CODEBASE_MAP.md` del módulo nuevo, con el formato de `CAH-Engine`.
- [ ] Archivar el repo de `Bots`.

---

## 10. Riesgos y trampas concretas

**R1 — `LazyInitializationException` con `user.lang`.** `User.lang` es `@ManyToOne(fetch = LAZY)`. El
`User` guardado en `UserDetails` viaja **fuera de la transacción**, y `I18NServiceImpl.get(tag)` llama a
`SecurityUtils.getLang()` desde el código del bot, sin sesión de Hibernate abierta → petardazo al
traducir el primer mensaje. **Mitigación:** el query de `login` hace
`JOIN FETCH tu.user u JOIN FETCH u.lang`. Test explícito en F3.

**R2 — Fuga de sesión entre usuarios.** §7.3. Sin el `finally`, con long-polling
(`LongPollingSingleThreadUpdateConsumer`, un solo hilo) **todos** los updates posteriores heredan la
identidad del primero. Es un fallo de seguridad, no de estilo.

**R3 — Llamadas asíncronas y `ThreadLocal`.** `BotMessageService.sendMessageAsync` ejecuta el callback
en **otro hilo**: allí no hay ni `SecurityContext` ni `TelegramContext`. Se usa en la creación de
partida (capturar `messageId`). **Mitigación:** capturar `User`, `Room` e ids en variables locales
*antes* de lanzar el async. Nunca llamar a `SecurityUtils`/`TelegramSecurityUtils` dentro de un callback.

**R4 — Transacción y API de Telegram.** Una llamada a Telegram dentro de un `@Transactional` bloquea
una conexión de BD durante cientos de ms. Patrón: transacción → commit → enviar mensajes → transacción
corta para guardar `message_id`.

**R5 — Migración de datos** (F2). El riesgo real del proyecto: el índice único de `username` va a sacar
a la luz duplicados latentes en los datos viejos. De ahí el ensayo obligatorio sobre backup.

**R6 — Concurrencia por partida.** Dos jugadores votando a la vez en modo webhook pueden entrelazar
lecturas/escrituras sobre la misma `Round`. El engine no tiene *locking* optimista hoy. **Mitigación
mínima:** documentarlo; si aparece, añadir `@Version` a `Round`/`Game` y reintentar. No bloquea F6
(long-polling es monohilo).

**R7 — Alcance del "100% retrocompatible".** Se mantiene la **interfaz de comandos y callbacks**. El
texto exacto de los mensajes depende de la tabla `Tag`, cuyo estado tras el refactor de
`Commons-Engine` es desconocido → auditoría en F5.

---

## 11. Resumen de cambios en módulos existentes

| Módulo | Cambio | Riesgo |
|---|---|---|
| `Commons-Engine` | Split `name`/`username` en `User` y `name`/`roomname` en `Room` + APIs y tests (§5.1) | ✅ hecho |
| `CAH-Engine` | + `createGame(Room)`; adaptación a las firmas nuevas de `RoomService`; fixtures + DTD | ✅ hecho |
| `SH-Engine` | Lo mismo que CAH: `createGame(Room)`, fixtures + DTD, dos llamadas en tests (§5.2 bis) | ✅ hecho |
| `Commons-Telegram` | Rename de paquete; + dependencia `engine-commons`; + identidad/sesión; + `UpdateDispatcher`; arreglos de `pendingReplies` | ✅ hecho |
| Pom raíz | − `Bots`, + `CAH-Telegram` (módulo y BOM) | Bajo |
| `Bots` | Fuera del reactor en F0; se archiva en F8 | — |
| BD producción | Baseline V3 + migración `BIGINT`→`UUID` + split de `name` | **Alto** — F2, con ensayo sobre backup |

---

## 11 bis. Los dos starters de Telegram — resuelto en F4

Los dos starters registran un bean `telegramBotsApplication` y Boot 4 no permite override, así que no
pueden convivir. **Se optó por registrar los bots nosotros** (`TelegramBotsRegistrarConfig`) y excluir
las dos autoconfiguraciones con `spring.autoconfigure.exclude`, en vez de elegir un modo global y
excluir solo la otra.

El motivo: al abrir los starters resultaron ser **envoltorios triviales** —un `@Bean` que crea el
objeto de aplicación y un registrador que le pasa los bots—, así que asumirlos no añade mantenimiento
real y elimina el conflicto de raíz en lugar de esquivarlo. De propina quedó al descubierto que el
starter de webhook no publica ningún endpoint HTTP, cosa que había que arreglar igualmente.

## 12. Orden de ataque recomendado

```
F0 reactor  →  F1 split identidad  →  F2 esquema+datos  →  F3 sesión Telegram
                                                                  ↓
            F8 docs  ←  F7 hardening  ←  F6 bot juego  ←  F5 bot diccionarios  ←  F4 esqueleto
```

F1 va antes que F2 porque el baseline del esquema se genera **desde las entidades ya partidas**. F5 va
antes que F6 porque valida la sesión completa con la mitad de superficie.

**Estimación total: 14–19 jornadas**, de las que 2–3 son la migración de datos.
