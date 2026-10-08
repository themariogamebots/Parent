# Spec: jugadores IA en Cartas Contra la Humanidad

> Módulos afectados: `CAH-Engine` (reglas), `CAH-Telegram` (UI, esquema, tags), `Commons-Engine` (un método).
> Fecha: 2026-10-08 · Estado: **decisiones cerradas** (§10).

---

## 1. Objetivo y alcance

Permitir que el creador de una partida añada **jugadores IA** para completarla. Una IA juega carta y
vota como cualquier otro jugador; para el motor es un `Player` más y cuenta para los quórums.

**En esta versión**: una única estrategia, `RandomAIPlayerStrategy`, que:
- juega una carta al azar de su mano **en cuanto empieza la ronda**;
- vota una carta al azar (nunca la suya) **en cuanto se abre la votación**.

**Preparado para después**: la estrategia es una interfaz (§4), para poder enchufar una que aprenda qué
cartas blancas ganan con cada carta negra (§9).

**Fuera de alcance**: Secret Hitler, que se pide IA desde la plataforma (la IA vive en el motor y es
agnóstica de Telegram) y retrasos artificiales para "simular que piensa".

---

## 2. Punto de partida (lo que condiciona el diseño)

| Hecho | Dónde | Consecuencia |
|---|---|---|
| El quórum de juego y de voto solo cuenta `game.getPlayers().size()` | `RoundServiceImpl.java:214-230` | Una IA registrada como `Player` completa rondas sin tocar las reglas |
| `playCard`/`voteCard`/`addPlayer` sacan el jugador del usuario de la **sesión** | `CAHServiceImpl.java:269,308,479` | La IA no tiene sesión: el motor necesita variantes que reciban el `Player` |
| `Player.user` es `nullable=false, unique=true` | `Commons-Engine/.../models/Player.java:16` | Cada IA necesita **su propio** `User` |
| `UserService` no tiene `delete` | `Commons-Engine/.../services/intf/UserService.java` | Hay que añadirlo para limpiar los usuarios IA |
| La capa de Telegram recorre `TelegramPlayer`s para pintar manos y opciones de voto | `CCLHTelegramServiceImpl.java:473,885,940` | Una IA **sin** `TelegramPlayer` queda fuera de forma natural |
| El quórum de borrado es `players.size()/2 + 1` | `GameServiceImpl.java:371` | Con IAs, los humanos podrían no alcanzarlo nunca → hay que contar solo humanos |
| En `CLASSIC` la presidencia rota por `joinOrder` sobre **todos** los jugadores | `RoundServiceImpl.java:244` | Una IA puede ser presidente, y se deja así (Q2) |
| `nextRound` borra la ronda terminada | `CAHServiceImpl.java:358` | Hoy no queda historial del que aprender → se añade `round_result` (Q3, §9) |
| En `CLASSIC`/`DICTATORSHIP` el grupo no ve las cartas jugadas hasta el fin de ronda; en `DEMOCRACY` sí | `CCLHTelegramServiceImpl.openVoting` | Se unifica: el grupo las ve siempre al abrir la votación (Q4, §6.4) |
| Las cartas se listan en el orden en que se jugaron | `getGameVoteCardMessage`, `sendVoteOptions` | La IA juega la primera: su carta se reconocería → se barajan (§6.4) |

---

## 3. Modelo de datos

### 3.1 `Player` (CAH-Engine)

Columna nueva:

```java
@Column(nullable = false)
private Boolean ai = false;
```

Se añade a `cah.models.game.Player`, no a la base de Commons: la IA es un concepto de CAH de momento.

### 3.2 `User` de la IA

Un `User` **por jugador IA**, creado al añadirlo y borrado cuando se quita o termina la partida:

| Campo | Valor |
|---|---|
| `username` | `"ai:" + UUID.randomUUID()` (cabe en los 64 caracteres; no choca con `tg:<id>` ni con alias, porque en un alias de Telegram no puede ir `:`) |
| `name` | Lo pasa la plataforma ya traducido (§6.3). El motor no sabe de i18n |
| `lang` | El del creador de la partida |
| `active` | `true` |

No tiene `TelegramUser` asociado, así que `chatIdOf` devuelve `null` y las rutinas de difusión
(`sendMessageToEveryone`) lo saltan.

**Commons-Engine**: se añade `void delete(User user)` a `UserService`/`UserServiceImpl`.

### 3.3 Esquema

**La V2 ya está desplegada**, así que sus migraciones no se tocan: Flyway compara checksums y no arrancaría.
Todo va en una versión nueva, **V2.1.0**, para h2 y mariadb:

| Fichero | Contenido |
|---|---|
| `V2.1/V2.1.0_1__AI_players.sql` | `alter table player add column ai …` |
| `V2.1/V2.1.0_2__AI_players_tags.sql` | Los tags de §6.6, en es y en |

El DDL se escribe a mano, pero **partiendo de lo que genera `SchemaGenerator`**: se ejecuta, se compara con la
baseline y se copian a la migración las diferencias, con los mismos tipos que la baseline:

```sql
-- h2
alter table player add column ai boolean default false not null;
-- mariadb
alter table player add column ai bit default false not null;
```

El `default` es lo que permite añadir la columna `not null` con partidas en curso. Sale de
`@ColumnDefault("false")` en la entidad, así que coincide con lo que genera `SchemaGenerator`.

La tabla `round_result` (§9) **no** va aquí: su DDL tiene que salir de la entidad, que se escribe en F5. Irá en
`V2.1.0_3__Round_results.sql`.

`SchemaBaselineTest` sigue valiendo tal cual: valida el esquema que queda después de **todas** las migraciones.

---

## 4. Estrategia

Paquete nuevo en `CAH-Engine`: `org.themarioga.engine.cah.ai`.

```java
public interface AIPlayerStrategy {

    /** Carta de la mano de {@code ai} que juega en {@code round}. Nunca null si la mano no está vacía. */
    Card chooseCardToPlay(Round round, Player ai);

    /** Carta jugada que vota {@code ai} en {@code round}. Nunca la suya. */
    Card chooseCardToVote(Round round, Player ai);
}
```

```java
@Component
public class RandomAIPlayerStrategy implements AIPlayerStrategy {

    private final Random random;

    public RandomAIPlayerStrategy() { this(new SecureRandom()); }

    RandomAIPlayerStrategy(Random random) { this.random = random; }   // los tests le pasan una semilla

    // chooseCardToPlay: elemento aleatorio de ai.getHand()
    // chooseCardToVote: elemento aleatorio de round.getPlayedCards() cuyo player != ai
}
```

Una sola implementación como `@Component`. Cuando haya una segunda, se elegirá por propiedad
(`cah.game.ai.strategy`), pero eso no se hace ahora.

---

## 5. Motor (`CAH-Engine`)

### 5.1 API nueva en `CAHService`

```java
Game addAIPlayer(Room room, String name);   // solo el creador, solo en CREATED
Game removeAIPlayer(Room room);             // solo el creador, solo en CREATED; quita la última IA añadida
```

`addAIPlayer` crea el `User` (§3.2) y el `Player` con `ai = true`, y lo añade con
`gameService.addPlayer` (respeta `maxNumberOfPlayers`). `removeAIPlayer` lo quita con
`gameService.removePlayer` y borra `Player` y `User`. Si no hay IAs, lanza `AIPlayerDoesntExistsException`.

### 5.2 Refactor de `playCard` / `voteCard`

El cuerpo actual pasa a dos métodos privados que reciben el jugador:

```java
public Game playCard(Room room, Card card) { Game g = getGameByRoom(room); return doPlayCard(g, getPlayerBySessionUserAndGame(g), card); }
private Game doPlayCard(Game game, Player player, Card card) { ... lo de ahora ... ; afterPlay(game); }

public Game voteCard(Room room, Card card) { ... igual con doVoteCard ... }
```

La IA solo entra por los `do*`. **No se usa el `SecurityContext` para hacerse pasar por la IA.**

### 5.3 Cuándo actúa la IA

Todo síncrono y dentro de la transacción que ya hay abierta:

| Momento | Qué hace |
|---|---|
| Al final de `startRound` (después de repartir) | Cada IA que no sea presidente de la ronda juega `strategy.chooseCardToPlay`. Si con eso han jugado todos, la ronda pasa a `VOTING` y se ejecuta la fila siguiente |
| Cada vez que la ronda pasa a `VOTING` | `DEMOCRACY`: vota cada IA. `CLASSIC` con IA de presidente: la IA vota una vez, y eso cierra la ronda (`ENDING`) |
| Cuando la votación cierra la ronda | Se registra `round_result` (§9), se puntúa y se comprueba el fin de partida como ya se hacía |

`DICTATORSHIP` no cambia: el presidente es siempre el creador, que es humano.

La IA **nunca encadena rondas**: `nextRound` lo sigue llamando la plataforma.

### 5.4 Reglas que cambian por haber IAs

1. **Quórum de borrado** (`GameServiceImpl.voteForDeletion`): `humanos/2 + 1` en lugar de `jugadores/2 + 1`.
2. **Mínimo de humanos para empezar** (`GameServiceImpl.startGame`): `humanos >= cah.game.min-human-players`
   (= `2`), además del mínimo de jugadores que ya existía. El botón "Empezar" de `sendMainMenu` aplica la misma
   condición.
3. **Borrado de la partida** (`deleteGameByCreator`, y lo que use la plataforma al terminarla): se recogen los
   `User` de las IAs **antes** de borrar la partida y se borran **después**.
4. **Presidencia en `CLASSIC`**: no cambia. La IA puede ser presidente y elige ganadora con `chooseCardToVote`.

### 5.5 Errores nuevos (`CAHErrorEnum`)

| Código | Nombre | Cuándo |
|---|---|---|
| 58 | `AI_PLAYER_NOT_FOUND` | `removeAIPlayer` sin IAs en la partida |
| 59 | `GAME_NOT_ENOUGH_HUMANS` | `startGame` sin el mínimo de humanos |

Con sus excepciones (`AIPlayerDoesntExistsException`, `GameNotEnoughHumansException`). Por la convención
de `ErrorMessageResolver.tagOf`, los tags son `ERROR_AI_PLAYER_NOT_FOUND` y `ERROR_GAME_NOT_ENOUGH_HUMANS`.

---

## 6. Telegram (`CAH-Telegram`)

### 6.1 Menú principal (`sendMainMenu`, estado `CREATED`)

Dos filas nuevas, debajo de "Unirse":

- `GAME_ADD_AI_BUTTON` → `game_add_ai`, si `players < maxNumberOfPlayers`.
- `GAME_REMOVE_AI_BUTTON` → `game_remove_ai`, si hay alguna IA.

Las dos pasan por `getGameAndCheckCreator`. El mensaje del grupo ya lista los nombres de los jugadores
(`getCurrentPlayerNumberMessage`), así que las IAs salen ahí con su nombre.

### 6.2 Callbacks

`game_add_ai` y `game_remove_ai` en `CCLHApplicationServiceImpl`. Son **claves nuevas**, no renombradas, así
que no rompen nada de lo desplegado. Hay que añadirlas al conjunto `CALLBACKS` de `CCLHApplicationServiceTest`.

### 6.3 Nombre de la IA

La plataforma lo resuelve con el tag `AI_PLAYER_NAME` (`"IA {0}"` / `"AI {0}"`), donde `{0}` es
el número de IA dentro de la partida (1, 2, …). Usa el idioma del creador.

### 6.4 Flujo de ronda

**a) El estado puede llegar más avanzado.** Como la IA actúa dentro del motor, a la capa de Telegram le
pueden llegar estados que antes no veía:

| Llamada | Antes devolvía | Ahora también puede devolver | Qué hacer |
|---|---|---|---|
| `playCard` | `PLAYING`, `VOTING` | `ENDING` (IA presidente en `CLASSIC`) | `endRound(...)` directamente |
| `startGame` / `nextRound` | ronda en `PLAYING` | — (con mínimo de 2 humanos no puede pasar; ver §8) | — |

Cuando la IA presidente cierra la ronda al instante, el grupo ve directamente el mensaje de fin de ronda
(`GAME_END_ROUND`), que **ya** lista todas las cartas jugadas con su autor y la ganadora. No hace falta nada más.

`openVoting` en `CLASSIC` con IA de presidente no llega a ejecutarse, porque la ronda ya viene en `ENDING`.
Aun así se protege: si el presidente no tiene `TelegramPlayer`, se loguea en `debug` y no en `error`.

**b) Cartas jugadas en el grupo en todos los modos (Q4, también sin IA).** Hoy `openVoting` solo edita el
mensaje de ronda del grupo en `DEMOCRACY`. Pasa a hacerlo siempre:

- `DEMOCRACY`: `GAME_VOTE_CARD`, como ahora ("…ahora los jugadores votarán por privado").
- `CLASSIC`/`DICTATORSHIP`: tag nuevo `GAME_VOTE_CARD_PRESIDENT`, con las mismas cartas y el nombre del presidente
  que va a elegir.

**c) Orden de las cartas.** `getGameVoteCardMessage` y `sendVoteOptions` barajan las cartas jugadas en lugar de
listarlas en el orden de juego. Si no, la carta de la IA (que siempre juega la primera) se reconocería. De paso
se arregla la misma filtración que ya había entre humanos: el primero en jugar quedaba arriba.

Para que el grupo y los privados no muestren órdenes distintos que delaten nada, el orden se calcula una vez
por ronda: se baraja con una semilla derivada del `round.getId()`. No hace falta persistirlo.

### 6.5 Ruido en logs

`chatIdOf` loguea `warn` cuando un usuario no tiene `TelegramUser`. Para jugadores IA es lo esperado: los
bucles que recorren jugadores los saltan antes de llamar a `chatIdOf` (`player.getAi()`).

### 6.6 Tags nuevos

En `V2.1.0_2__AI_players_tags.sql` (§3.3), en **es** y **en** y en **h2** y **mariadb**:

| Tag | es | en |
|---|---|---|
| `GAME_ADD_AI_BUTTON` | `Añadir jugador IA` | `Add AI player` |
| `GAME_REMOVE_AI_BUTTON` | `Quitar jugador IA` | `Remove AI player` |
| `AI_PLAYER_NAME` | `IA {0}` | `AI {0}` |
| `ERROR_AI_PLAYER_NOT_FOUND` | `No hay ningún jugador IA que quitar.` | `There is no AI player to remove.` |
| `ERROR_GAME_NOT_ENOUGH_HUMANS` | `Hacen falta al menos dos jugadores humanos.` | `At least two human players are needed.` |
| `GAME_VOTE_CARD_PRESIDENT` | `<b>Ronda {0}</b>\n\nLa carta negra de esta ronda es <b>{1}</b>\n\nLos jugadores eligieron las siguientes cartas blancas:\n\n<b>{2}</b>\n\nAhora <b>{3}</b> elegirá la ganadora.` | `<b>Round {0}</b>\n\nThe black card of this round is <b>{1}</b>\n\nThe players chose the following white cards:\n\n<b>{2}</b>\n\nNow <b>{3}</b> will choose the winner.` |

`SchemaBaselineTest.EXPECTED_TAGS`: 216 → **224** (incluye los dos de `V2.0.1_1`, ver §12).

**Sin emojis**: ningún texto de la V2 los tiene y no está garantizado que la BD desplegada sea `utf8mb4`. Con
`utf8` (3 bytes), un 🤖 haría fallar el `INSERT`, y con él la migración y el arranque.

> `ERROR_GAME_NOT_ENOUGH_HUMANS` va sin parámetro porque `ErrorMessageResolver` no formatea. Si el mínimo se
> cambia por configuración, el texto se queda desfasado; se acepta.

---

## 7. Configuración

```properties
cah.game.min-human-players=2
```

En `GameConfig` (`cah.game.*`) y en `application.properties` de `CAH-Telegram`.

---

## 8. Casos límite

| Caso | Comportamiento |
|---|---|
| Solo 1 humano en `CLASSIC`/`DICTATORSHIP` y es el presidente | Todos los que juegan serían IA, así que la ronda saldría ya en `VOTING` de `startRound`, y `sendRound` no lo soporta. **No puede darse: el mínimo es de 2 humanos** |
| Empate a votos | Sin cambios: `getMostVotedCard` desempata al azar |
| Empate a puntos al final | Sin cambios: gana el de menor `joinOrder`. Las IAs se unen después del creador |
| Un humano sale en `CREATED` y la partida se queda por debajo del mínimo de humanos | Se permite; `startGame` lo rechaza después |
| Borrado por votación con IAs | Cuentan solo los humanos (§5.4.1) |
| Admin `/deleteallgames`, `/deletegamebyusername` | Pasan por `deleteGameByCreator`, que limpia los `User` IA (§5.4.3) |
| La IA no tiene cartas en la mano | No puede pasar: `startRound` rellena todas las manos antes de que juegue. Si pasa, `PlayerCannotDrawCardException`, como con un humano |

---

## 9. Evolución: aprender qué cartas ganan

**Implementado (F5): el histórico.** La IA sigue siendo aleatoria, pero cada votación cerrada deja su rastro:

```
round_result(id, creation_date, dictionary_id, black_card_id, white_card_id,
             votes, ai_votes, won, candidates, votation_mode, ai_player)
```

- Una fila por carta jugada. La escribe `RoundResultService.recordRound` desde `CAHServiceImpl.doVoteCard` al
  cerrarse la votación, antes de que `nextRound` borre la ronda con sus cartas y votos.
- `ai_votes` y `ai_player` sirven para descontar o excluir lo que hagan las IAs al entrenar, porque si no,
  la IA aleatoria ensuciaría los datos. `candidates` es el número de cartas que compitieron: ganar entre dos
  no vale lo mismo que entre ocho.
- **Sin claves ajenas.** Cartas y diccionarios se pueden borrar (`CardService.delete`, `DictionaryService.delete`),
  y el histórico no tiene por qué perderse con ellos ni impedir que se borren. Van como ids sueltos.
- Índices por `(black_card_id, white_card_id)` y por `white_card_id`, para las dos consultas de la estrategia
  futura. `RoundResultService.getByBlackCardId` es la primera.
- Migración `V2.1.0_3__Round_results.sql`, con el DDL copiado de lo que genera `SchemaGenerator`.

Estrategia futura (`StatsAIPlayerStrategy`), como orientación:
- Puntuación de una blanca = combinación de su tasa de victoria **global** y la del **par** negra×blanca
  (con suavizado de Laplace; el par tiene muy pocos datos).
- Un ε de exploración (p. ej. 10 %) para que no juegue siempre las mismas.
- Para votar, la misma puntuación sobre las cartas jugadas.
- Otra opción, por la misma interfaz: delegar la elección en un LLM.

---

## 10. Decisiones

Cerradas el 2026-10-08.

| Id | Pregunta | Decisión |
|---|---|---|
| Q1 | Mínimo de humanos para empezar | 2 |
| Q2 | ¿Puede la IA ser presidente en `CLASSIC`? | Sí, y elige ganadora al azar |
| Q3 | ¿Registrar `round_result` ya en esta versión? | Sí |
| Q4 | ¿Se enseñan en el grupo las cartas jugadas antes de anunciar la ganadora? | Sí, **también en partidas sin IA** (§6.4 b) |

---

## 11. Plan de trabajo

| Fase | Contenido | Tests |
|---|---|---|
| F1 | `UserService.delete` en Commons-Engine | Test de servicio |
| F2 | `Player.ai`, `AIPlayerStrategy`, `RandomAIPlayerStrategy`, `addAIPlayer`/`removeAIPlayer`, refactor `do*`, acciones de la IA (§5.3), reglas de §5.4, errores | `CAHServiceTest`: los tres modos con 1–2 IAs hasta el final de la partida; quórum de borrado; mínimo de humanos; limpieza de `User`; `RandomAIPlayerStrategy` con semilla (nunca vota la suya) |
| F3 | Migraciones V2.1.0 (h2 + mariadb): columna `ai` y tags; `EXPECTED_TAGS` | `SchemaBaselineTest`, `SchemaBaselineMariaDbTest` |
| F4 | Callbacks, menú, `ENDING` en `playerPlayCardQuery`, filtros de `chatIdOf`, cartas en el grupo en todos los modos y orden barajado (§6.4) | `CCLHApplicationServiceTest` (conjunto `CALLBACKS`), `GameFlowTest`: partida completa con IAs en `DEMOCRACY` y `CLASSIC`; `CLASSIC` sin IA edita el mensaje del grupo al abrir la votación; grupo y privado muestran el mismo orden |
| F5 ✔ | `round_result`: entidad, migración `V2.1.0_3`, DAO, escritura al cerrar la votación | `RoundResultServiceTest`; en `CAHServiceTest` las filas tras una ronda, con y sin IA; en `GameFlowTest` las filas tras una votación |

Todo con `mvn -Ptest test`. **No** `mvn -Pcheck`.

---

## 12. Hallazgos durante la implementación (F4 y F5)

Al probar una partida completa de punta a punta salieron tres problemas que el spec no preveía:

1. **Las opciones de voto no le llegaban a nadie (bug previo, en producción).** `sendVoteOptions`,
   `showPlayedCardToItsPlayer` y `showVoteToItsVoter` leían `Player.getPlayedCard()`/`getVotedCard()`, que el motor
   no rellena nunca. Las partidas se quedaban paradas en la primera votación. Ahora la capa de Telegram saca la carta
   jugada y la votada de `round.getPlayedCards()`/`getVotedCards()`. El presidente de `CLASSIC`/`DICTATORSHIP` no
   juega carta, así que su privado usa dos tags propios: `PLAYER_PRESIDENT_VOTE_CARD` y `PLAYER_PRESIDENT_VOTED_CARD`,
   en su propia migración (`V2.0.1_1__President_vote_tags.sql`) para poder desplegar el arreglo antes que la IA.
   Los campos `Player.playedCard`/`votedCard` del motor siguen ahí, muertos.
2. **La partida no se borraba si la última ronda no la cerraba el creador (bug previo, oculto tras el 1).** `endGame`
   usaba `deleteGameByCreator`, que exige que la sesión sea del creador. Se añade `CAHService.endGame(Game)`, que solo
   acepta partidas en `ENDING` (a las que solo se llega por las reglas) y borra también los usuarios IA.
3. **Cartas jugadas que no se borraban de `player_hand_card` (lo provocaba la IA).** La IA juega en la misma transacción
   en la que se le reparte, y `orphanRemoval` no ve como huérfana una carta que todavía no se ha insertado. La fila se
   quedaba y la partida ya no se podía borrar (FK). `removeCardFromHand` borra la carta de forma explícita, y
   `insertWhiteCardsIntoPlayerHand` usa persist en lugar de merge, para que las cartas de la mano sean las instancias
   gestionadas.
4. **En un empate, el grupo podía ver una ganadora distinta de la que se llevaba el punto (bug previo).**
   `getMostVotedCard` desempataba con un `SecureRandom` compartido. El motor lo llamaba para dar el punto y
   `endRound` volvía a llamarlo para anunciar la ganadora, así que eran dos tiradas independientes. Ahora el
   desempate ordena las cartas empatadas por id y elige con una semilla sacada del id de la ronda: sigue siendo
   aleatorio entre rondas, pero siempre igual para la misma ronda. Además, `won` en `round_result` coincide con
   lo que se anuncia.

