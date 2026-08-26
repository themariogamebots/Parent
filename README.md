# CCLH

Superproyecto con todo el código de los bots de **Cartas Contra la Humanidad** y **Secret Hitler**.

Este repositorio contiene el POM padre (que hace también de BOM) y referencia el resto de proyectos
como submódulos de git.

## Módulos

| Módulo | Qué es |
|---|---|
| `Engine-Commons` | Librería base: `User`, `Room`, `Lang`, i18n, seguridad y DAO genérico de Hibernate |
| `CAH-Engine` | Motor de juego de Cartas Contra la Humanidad |
| `SH-Engine` | Motor de juego de Secret Hitler |
| `TelegramBotUtils` | Librería común de bots de Telegram: identidad, sesión y despacho de updates |
| `CAH-Telegram` | Aplicación: los bots de Telegram de CAH (juego y diccionarios) |

## Clonar

Los módulos son submódulos, así que hay que traerlos:

```bash
git clone --recurse-submodules https://github.com/themarioga/<este-repo>.git
```

Si ya lo has clonado sin ellos:

```bash
git submodule update --init --recursive
```

## Construir

Desde la raíz, que construye todos los módulos en el orden correcto:

```bash
mvn clean install          # sin tests
mvn clean -P test install  # con tests
```

> Ojo: usa `clean`. Maven no recompila un módulo cuyas fuentes no han cambiado aunque sí haya
> cambiado la librería de la que depende, y sin `clean` se pueden ejecutar tests contra bytecode
> viejo con errores que despistan.

## Planes y documentación

Los planes de trabajo viven en [`docs/`](docs/). Cada módulo tiene además su propio `CLAUDE.md` y su
`docs/CODEBASE_MAP.md`.
