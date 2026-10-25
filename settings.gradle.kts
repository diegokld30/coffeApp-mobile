// AgroVisión — mapa de módulos.
// La modularización cumple dos propósitos (§6.1 SC-ARQ-2026-001): acelerar la
// compilación incremental y convertir el aislamiento de red en una propiedad
// verificable por el sistema de compilación, no en una regla que el equipo recuerda.

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "agrovision"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ─────────────────────────────────────────────────────────────────────────────
// ESTADO DE CONSTRUCCIÓN
//
// Los módulos comentados están diseñados pero todavía no escritos. Se incluyen
// aquí, comentados y con su nombre definitivo, en vez de omitirlos: el grafo
// completo se lee de un vistazo y activarlos es descomentar una línea.
//
// Ver README.md § «Qué está hecho y qué no» para el estado detallado.
// ─────────────────────────────────────────────────────────────────────────────

// ── Ensamblaje ────────────────────────────────────────────────────────────────
include(":app")

// ── Núcleo ────────────────────────────────────────────────────────────────────
include(":core:model")          // Dominio. Kotlin puro, sin Android.
include(":core:common")         // Result, despachadores, UiText, UUIDv7.
include(":core:designsystem")   // Tema Compose, tokens, componentes de campo.
include(":core:database")       // Room, DAOs, migraciones.
include(":core:datastore")      // Preferencias, puntero de ranura de modelo activa.
include(":core:storage")        // Imágenes: compresión, EXIF, miniaturas, purga.
include(":core:data")           // Implementaciones de repositorio (ADR-012).

// ── Características ───────────────────────────────────────────────────────────
include(":feature:scan")
// Pendientes. Las rutas ya están declaradas en AgroVisionDestinations.
include(":feature:catalog")
include(":feature:review")
// include(":feature:history")
// include(":feature:settings")

// ── Inteligencia artificial ───────────────────────────────────────────────────
// Los módulos de preprocesado, inferencia y OOD tienen PROHIBIDA toda dependencia
// de red. Lo impone MlIsolationConventionPlugin, no la buena voluntad de quien
// edita el build.gradle.kts.
include(":ml:preprocessing")
include(":ml:inference")
include(":ml:ood")
include(":ml:registry")

// ── Red y sincronización ──────────────────────────────────────────────────────
include(":network:api")
include(":sync:worker")
