# CLAUDE.md — CCLH (superproyecto)

## Codebase Overview

CCLH es un reactor Maven de seis submódulos git que implementa bots de Telegram para
dos juegos de cartas: **Cartas Contra la Humanidad** y **Secret Hitler**. La arquitectura
es una pila de librerías: una base compartida agnóstica de plataforma, un motor de reglas
por juego encima, una capa de infraestructura de Telegram, y **dos** aplicaciones Spring
Boot en la cima, una por juego, cada una con su despliegue y su base de datos.

**Stack**: Java 17+ · Spring Boot 4.1.0 · Hibernate 6 / JPA · Spring Security · Flyway ·
telegrambots 10.0.0 · JUnit 5 · Mockito · DBUnit · H2 (dev/test) / MariaDB (pre/pro).

**Estructura** (orden del reactor = orden de dependencias):

| Módulo | Qué es |
|---|---|
| `Commons-Engine` | Base compartida: `User`, `Room`, `Lang`, `Tag`, `Game`/`Player` abstractos, DAO/Service genéricos, i18n en BD, security |
| `CAH-Engine` | Reglas de Cartas Contra la Humanidad (partidas, rondas, votación, diccionarios) |
| `SH-Engine` | Reglas de Secret Hitler (roles, leyes, elecciones, poderes ejecutivos) |
| `Commons-Telegram` | Infraestructura de bots: identidad, sesión por update, dispatch, mensajería |
| `CAH-Telegram` | **Aplicación desplegable.** Dos bots (`cclh` + `dictionaries`) y el esquema de BD de CAH |
| `SH-Telegram` | **Aplicación desplegable.** Bot de Secret Hitler y su propio esquema de BD |

Para la arquitectura detallada, los flujos, las convenciones y los gotchas documentados,
ver [docs/CODEBASE_MAP.md](docs/CODEBASE_MAP.md). Cada submódulo tiene además su propio
`docs/CODEBASE_MAP.md` (ojo: los de Commons-Engine, CAH-Engine y SH-Engine contienen
afirmaciones obsoletas — el mapa raíz lista cuáles).

Ninguna de las dos aplicaciones se ha probado nunca contra Telegram de verdad: ni con un token
real, ni en modo webhook. Es el único punto abierto en los dos planes.

## Comandos habituales

```bash
mvn clean install                     # construye el reactor entero (tests OMITIDOS por defecto)
mvn -Ptest test                       # ejecuta los tests (sin este perfil no corre ninguno)
cd CAH-Telegram && mvn -Pdev spring-boot:run   # levanta CAH con H2, bots deshabilitados
cd SH-Telegram  && mvn -Pdev spring-boot:run   # levanta SH  con H2 en el 8081, bot deshabilitado
```

**No ejecutar `mvn -Pcheck`**: el formatter compartido está roto y reescribe ficheros in situ,
llevándose por delante el formato de todo el reactor.

## Cosas que conviene saber antes de tocar nada

- **Los tests están desactivados por defecto** (`tests.skip=true` en el POM raíz).
- **El esquema de BD vive en las aplicaciones**, no en los motores, y es **generado**: cada una tiene
  su `src/test/java/.../tools/SchemaGenerator.java`, que hay que ejecutar a mano para los dos
  dialectos (h2 y mariadb). `ddl-auto=validate` + `SchemaBaselineTest` fallan si hay deriva.
  **Son dos bases de datos distintas**: `cah.models.game.Game` y `sh.models.Game` mapearían a la
  misma tabla `game` si compartieran una.
- **Todo texto visible es un tag i18n** resuelto contra la tabla `tag`, no un fichero `.properties`.
  Un literal español en el código hace fallar un test. Los tags nuevos van en el
  `V*.0.0_2__Languages_and_tags.sql` de la aplicación que toque (`V2` en CAH, `V1` en SH),
  en los dos idiomas y los dos dialectos, y hay que subir el contador de `SchemaBaselineTest`.
- **Las claves de comando y de `callback_data` son contrato con lo ya desplegado**: Telegram guarda
  los botones dentro de los mensajes para siempre, así que renombrar una clave rompe partidas en curso.
  Hay tests que fijan los conjuntos exactos.
- **Los IDs de motor son `UUID`; cualquier `long` en una firma es un ID de Telegram.** Confundirlos
  es la fuente número uno de bugs del port.
- **`createOrUpdate` (merge) no sirve para insertar** entidades cuyo ID se deriva de una asociación
  (`TelegramGame`, `TelegramPlayer`): hay que usar `create` (persist).
- **Una BD de CAH recién creada no puede crear partidas**: `cah.game.default-dictionary-id` apunta a
  un UUID fijo que solo existe tras ejecutar `CAH-Telegram/tools/legacy_data_migration.py`. SH no
  tiene ese problema: no hay datos que migrar.
- **En Secret Hitler, mandar algo al chat equivocado arruina la partida.** El rol, las leyes que ve el
  presidente, el resultado de una investigación y las leyes espiadas van solo al privado de una
  persona. Es la invariante alrededor de la que están escritos los tests de `SH-Telegram`.
- **El webhook ya recibe updates reales de Telegram**, pero todo lo que viene después del
  controller (dispatch, sesión, handlers, envío) sigue sin probarse en vivo, y long polling con un
  token real tampoco — la fase F7 del plan (`docs/specs/CAH-Telegram-PLAN.md`) sigue abierta ahí.
