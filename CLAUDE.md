# CLAUDE.md — CCLH (superproyecto)

## Codebase Overview

CCLH es un reactor Maven de cinco submódulos git que implementa bots de Telegram para
dos juegos de cartas: **Cartas Contra la Humanidad** (desplegable, en uso) y
**Secret Hitler** (motor completo, sin capa de bot todavía). La arquitectura es una
pila de librerías: una base compartida agnóstica de plataforma, un motor de reglas por
juego encima, una capa de infraestructura de Telegram, y una única aplicación Spring Boot
en la cima que arranca dos bots independientes en un mismo proceso.

**Stack**: Java 17+ · Spring Boot 4.1.0 · Hibernate 6 / JPA · Spring Security · Flyway ·
telegrambots 10.0.0 · JUnit 5 · Mockito · DBUnit · H2 (dev/test) / MariaDB (pre/pro).

**Estructura** (orden del reactor = orden de dependencias):

| Módulo | Qué es |
|---|---|
| `Commons-Engine` | Base compartida: `User`, `Room`, `Lang`, `Tag`, `Game`/`Player` abstractos, DAO/Service genéricos, i18n en BD, security |
| `CAH-Engine` | Reglas de Cartas Contra la Humanidad (partidas, rondas, votación, diccionarios) |
| `SH-Engine` | Reglas de Secret Hitler (roles, leyes, elecciones, poderes ejecutivos) — **sin consumidor** |
| `Commons-Telegram` | Infraestructura de bots: identidad, sesión por update, dispatch, mensajería |
| `CAH-Telegram` | **La aplicación desplegable.** Dos bots (`cclh` + `dictionaries`) y el **esquema de BD canónico** de todo el reactor |

Para la arquitectura detallada, los flujos, las convenciones y los 36 gotchas documentados,
ver [docs/CODEBASE_MAP.md](docs/CODEBASE_MAP.md). Cada submódulo tiene además su propio
`docs/CODEBASE_MAP.md` (ojo: los de Commons-Engine, CAH-Engine y SH-Engine contienen
afirmaciones obsoletas — el mapa raíz lista cuáles).

## Comandos habituales

```bash
mvn clean install                     # construye el reactor entero (tests OMITIDOS por defecto)
mvn -Ptest test                       # ejecuta los tests (sin este perfil no corre ninguno)
cd CAH-Telegram && mvn -Pdev spring-boot:run   # levanta la app con H2, bots deshabilitados
```

**No ejecutar `mvn -Pcheck`**: el formatter compartido está roto y reescribe ficheros in situ,
llevándose por delante el formato de todo el reactor.

## Cosas que conviene saber antes de tocar nada

- **Los tests están desactivados por defecto** (`tests.skip=true` en el POM raíz).
- **El esquema de BD vive en `CAH-Telegram`**, no en los motores, y es **generado**:
  se regenera ejecutando a mano `CAH-Telegram/src/test/java/.../tools/SchemaGenerator.java`
  para los dos dialectos (h2 y mariadb). `ddl-auto=validate` + `SchemaBaselineTest` fallan si hay deriva.
- **Todo texto visible es un tag i18n** resuelto contra la tabla `tag`, no un fichero `.properties`.
  Un literal español en el código hace fallar un test. Los tags nuevos van en
  `V3.0.0_2__Languages_and_tags.sql`, en los dos idiomas y los dos dialectos.
- **Las claves de comando y de `callback_data` son contrato con lo ya desplegado**: Telegram guarda
  los botones dentro de los mensajes para siempre, así que renombrar una clave rompe partidas en curso.
  Hay tests que fijan los conjuntos exactos.
- **Los IDs de motor son `UUID`; cualquier `long` en una firma es un ID de Telegram.** Confundirlos
  es la fuente número uno de bugs del port.
- **`createOrUpdate` (merge) no sirve para insertar** entidades cuyo ID se deriva de una asociación
  (`TelegramGame`, `TelegramPlayer`): hay que usar `create` (persist).
- **Una BD recién creada no puede crear partidas**: `cah.game.default-dictionary-id` apunta a un UUID
  fijo que solo existe tras ejecutar `CAH-Telegram/tools/legacy_data_migration.py`.
- **Nada de esto ha hablado todavía con Telegram de verdad** — la fase F7 del plan
  (`docs/CAH-Telegram-PLAN.md`) sigue abierta en ese punto.
