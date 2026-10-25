# AgroVisión — app Android

Reconocimiento de plagas del café con visión artificial **en el teléfono**, sin
conexión. Reescritura a Kotlin nativo de una app publicada en Google Play que
estaba hecha con Ionic + Angular + Capacitor.

**Conserva la identidad de despliegue. Cambia todo lo demás.**

> Estructura creada por **Diego Fernando Calderón Silva**, arquitecto de software.
> Proyecto libre y abierto.

## Tecnologías

| Área | Tecnología |
|---|---|
| Lenguaje | Kotlin 2.1 · JDK 17 |
| Interfaz | Jetpack Compose (Material 3) · Plus Jakarta Sans e IBM Plex Mono |
| Arquitectura | Hexagonal multimódulo · MVVM / MVI · Hilt |
| Datos locales | Room · DataStore |
| Cámara | CameraX |
| Visión artificial | LiteRT (TensorFlow Lite) en el dispositivo, sin conexión |
| Red y sincronización | Retrofit · OkHttp · kotlinx.serialization · WorkManager |
| Seguridad | Firma Ed25519 de modelos (BouncyCastle) · Play Integrity |
| Pruebas | JUnit 5 · MockK · Turbine · Robolectric |
| Build | Gradle (Kotlin DSL) · plugins de convención · Android 5.1+ (API 22) |

---

## ⚠️ Antes de tocar `:app`

Esta app **ya está publicada**. Lee [`PRESERVE.md`](PRESERVE.md) primero: es un
contrato, no una recomendación.

| Invariante | Valor | Si se rompe |
|---|---|---|
| `applicationId` | `io.ionic.starter` | Se publica una app distinta y se abandona a los usuarios actuales |
| Componente lanzador | `io.ionic.starter.MainActivity` | Los accesos directos existentes dejan de funcionar |
| `versionCode` | monotónico creciente | Play rechaza la subida |
| Migraciones Room | explícitas, nunca destructivas | El productor pierde sus capturas sin sincronizar |

El paquete Kotlin es `co.agrovision` y el `applicationId` es `io.ionic.starter`.
**Que difieran es deliberado**: Android lo permite, y aprovecharlo deja el código
con un paquete decente sin romper la continuidad de la app publicada. El
componente lanzador publicado se conserva con un `activity-alias`.

```bash
./gradlew verifyPublishedIdentity    # falla el build si algo de eso se rompe
```

## Arrancar en una máquina nueva

```bash
# 1. JDK 17 (no 21, no 26 — AGP 8.7 quiere 17)
brew install --cask temurin@17          # macOS
sudo apt install openjdk-17-jdk         # Linux

# 2. Android SDK — o instala Android Studio, que lo trae
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
echo "sdk.dir=$HOME/Android/Sdk"        > local.properties    # Linux

# 3. Compilar y probar
./gradlew :app:assembleDebug
./gradlew test                          # 43 pruebas
```

Para verlo corriendo en el emulador:

```bash
scripts/run-emulator.sh --camara=iphone   # o --camara=mac, o --camara=virtual
```

Arranca el emulador, compila, instala y abre la app. `--no-build` va más rápido
cuando no cambiaste código; `--fresh` desinstala antes para probar el primer
arranque real.

> La escena virtual del emulador es una sala con estantería, sin nada verde: la
> compuerta de calidad la rechaza —correctamente— por falta de contenido vegetal.
> Para probar el escaneo de verdad hace falta apuntar a una hoja real.

## Estado

Compila, las 43 pruebas pasan y la app corre en dispositivo.

| Módulo | Estado |
|---|---|
| `build-logic/` | ✅ 12 plugins de convención, incluido el de aislamiento de red |
| `:core:common` `:core:model` | ✅ dominio completo: entidades, puertos, casos de uso |
| `:core:designsystem` | ✅ tokens OKLCH→sRGB, tira de severidad, modo exterior |
| `:core:database` | ✅ Room v2 con `MIGRATION_1_2` y esquemas exportados |
| `:core:data` | ✅ repositorios + importación de datos de Capacitor |
| `:ml:*` | ✅ preprocesado, LiteRT, compuerta OOD, ranuras A/B con Ed25519 |
| `:feature:scan` | ✅ CameraX, guía de encuadre, tres compuertas |
| `:feature:catalog` | ✅ búsqueda y fichas — 8 plagas sembradas |
| `:feature:history` | ⬜ ruta y pestaña existen; falta la pantalla |
| `:feature:review` | ⬜ solicitudes de revisión |
| `:feature:settings` | ⬜ consentimiento, política de red, modo exterior |

**Pendiente fuera del código:** contraseña y alias del keystore, `versionCode`
más alto real de Play Console, y aval de un ingeniero agrónomo con tarjeta
profesional vigente para las recomendaciones de manejo (§13.6 — sin él las
fichas no muestran dosis).

## Cómo está armado

```
app/            navegación, inicio, inyección
core/           model · common · designsystem · database · datastore · storage · data
feature/        scan · catalog
ml/             preprocessing · inference · ood · registry    ← SIN RED
sync/worker     tres disparadores de sincronización
network/api     Retrofit, el único módulo que habla con el servidor
build-logic/    plugins de convención
```

**Los módulos `ml/` no pueden hacer red, y el build lo impone.** Tres barreras:
el grafo de dependencias no incluye ningún cliente HTTP, un plugin de convención
revisa el classpath resuelto y falla, y una regla de lint marca `java.net.*` como
error. Añadir OkHttp a `:ml:inference` rompe la compilación con un mensaje claro.

No es paranoia: el modelo decide qué se le dice a un productor sobre su cultivo.
Que esa decisión no pueda depender de una respuesta de red es una propiedad que
conviene tener garantizada por el compilador, no por disciplina.

## Las tres compuertas

```
FOTO
 ├─ C1 calidad ────── borrosa / oscura / sin hoja ──► «acércate», sin gastar cómputo
 ▼
INFERENCIA ──► logits + embedding
 ├─ C2 OOD ────────── energía + Mahalanobis ────────► «no la reconozco» ──► agrónomo
 ├─ C3 calibración ── p_max < τ ó margen < δ ───────► «no estoy seguro» ──► agrónomo
 ▼
DIAGNÓSTICO + ficha + opción de corregir
```

La compuerta 2 opera sobre el **embedding**, nunca sobre el softmax. Un umbral de
confianza detecta la *duda* del modelo; no detecta su *ignorancia*. Una plaga
jamás vista produce softmax de 0,95 sobre la clase visualmente parecida.

Por eso el cargador rechaza cualquier `.tflite` con menos de dos salidas: sin
embedding no existen ni el detector OOD ni la agrupación por similitud.

## Documentación

- [`PRESERVE.md`](PRESERVE.md) — contrato de identidad ante Play Store
- [`docs/adr/`](docs/adr/) — decisiones arquitectónicas y por qué se tomaron

## Repositorios hermanos

Este repositorio contiene **solo la app**. Fuera de él viven:

| Qué | Dónde |
|---|---|
| `CLAUDE.md` | especificación completa de la plataforma |
| `ml/` | pipeline de entrenamiento: JMuBEN, calibración, exportación INT8, firma Ed25519 |
| `backend/` `admin-web/` | aún sin construir |

El pipeline de modelos es lo que produce los cinco artefactos que instala esta
app (`model.tflite`, `labels.json`, `calibration.json`, `metrics.json`,
`signature.bin`). Ver [ADR-016](docs/adr/0016-modelo-propio-sobre-jmuben.md).

## Seguridad

> ⚠️ El keystore, su contraseña y `google-services.json` **nunca** entran a este
> repositorio. `.gitignore` los excluye, pero la responsabilidad es de quien
> commitea.
>
> El keystore es lo único irreemplazable del proyecto: sin él no se puede volver
> a publicar una actualización de la app que ya está en Play, y Google no lo
> repone.
