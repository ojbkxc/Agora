# AGENTS.md

> Instructions for AI coding agents working on the Agora project.

## Project Overview

Agora is a **BYOK (Bring Your Own Key) LLM client** for Android — a native Kotlin +
Jetpack Compose app that supports multiple LLM providers, agentic workflows, local
LLM inference (llama.cpp via NDK), and remote device control. All data is stored
locally; no telemetry, no tracking. License: MIT.

- **Application ID**: `com.newoether.agora`
- **Min SDK 24 / Target SDK 36 / Compile SDK 36**
- **NDK**: `28.2.13676358` — **ABI: `arm64-v8a` only**
- **Version**: `1.0.0` (versionCode = 1)

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.3.21 |
| UI | Jetpack Compose + Material 3 (Material You / dynamic color) |
| Architecture | MVVM + Coroutines & Flow (manual DI via `AppContainer`) |
| Storage | Room Database + DataStore Preferences |
| Network | OkHttp 5.3.2 + SSE streaming (no Retrofit, no Ktor) |
| Serialization | kotlinx.serialization 1.11.0 |
| Native | llama.cpp via Android NDK (CMake) — local LLM inference + embeddings |
| Sandbox | PRoot (Alpine Linux, F-Droid flavor) |
| Other | Coil, Lottie, Media3 ExoPlayer, CameraX, JSch (SSH), WorkManager |

## Build & Test Commands

```bash
# Build the F-Droid release APK (primary CI target)
./gradlew assembleFdroidRelease

# Build the Google Play release APK
./gradlew assemblePlayRelease

# Build the Play AAB bundle
./gradlew bundlePlayRelease

# Run unit tests
./gradlew test

# Run build-logic plugin tests (bytecode fix + source-size policy)
./gradlew -p build-logic test

# Verify Kotlin source file size budget (≤ 999 lines per file)
./gradlew verifyKotlinFileSize

# Build PRoot native binaries (libproot_exec.so, libproot_loader.so, libtalloc.so)
# Requires NDK 28.2.13676358; outputs to app/src/{main,fdroid}/jniLibs/arm64-v8a/
./build-proot.sh

# Lint
./gradlew lint
```

**Always run after modifying code:**
1. `./gradlew -p build-logic test` — verify build plugins
2. `./gradlew verifyKotlinFileSize` — enforce source size budget
3. `./gradlew assembleFdroidRelease` — confirm compilation

## Project Structure

```
Agora/
├── app/                        # Main Android app module (only Gradle module)
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   ├── schemas/                # Room DB schema snapshots (v10–v22)
│   └── src/
│       ├── main/               # Main source set
│       │   ├── AndroidManifest.xml
│       │   ├── assets/         # Provider icons (SVG/PNG)
│       │   ├── cpp/            # JNI native code (CMake: llama_jni, proot_jni)
│       │   ├── java/com/newoether/agora/
│       │   │   ├── api/        # LLM provider adapters (38 files)
│       │   │   ├── data/       # Room, DataStore, repositories (49 files)
│       │   │   ├── model/      # Data models / DTOs (17 files)
│       │   │   ├── viewmodel/  # ViewModels + generation controllers (86 files)
│       │   │   ├── ui/         # Compose UI (115 files)
│       │   │   ├── tool/       # Tool providers (13 files)
│       │   │   ├── service/    # Foreground service, WorkManager (8 files)
│       │   │   ├── mcp/        # MCP protocol client (4 files)
│       │   │   ├── sandbox/    # Sandbox interface
│       │   │   └── di/         # AppContainer (manual DI)
│       │   └── res/            # Resources (values, values-zh, drawable, raw, etc.)
│       ├── fdroid/             # F-Droid flavor (PRoot sandbox)
│       ├── play/               # Google Play flavor (no PRoot)
│       └── test/               # Unit tests
├── server/                     # Standalone Python services (rating + crash report)
├── thirdparty/                 # Native deps: llama.cpp (submodule), proot (submodule), talloc
├── build-logic/                # Gradle included build (bytecode fix + source-size plugin)
├── docs/                       # MkDocs user manual (en, zh)
├── fastlane/                   # Store metadata (en-US, zh-CN)
├── scripts/                    # Helper scripts (round_icon.py)
├── build-proot.sh              # PRoot native binary build script
├── gradle/libs.versions.toml   # Version catalog
└── .github/workflows/build.yml # CI/CD: build APK + GitHub Release
```

## Key Conventions

### Language / i18n
- **Only English (`values/`) and Simplified Chinese (`values-zh/`) are supported.**
- Do NOT add other language resource directories (`values-es`, `values-fr`, etc.).
- Language options are defined in `SettingsLanguagePage.kt` and locale mapping in
  `MainActivity.attachBaseContext()` — keep these in sync (currently `system`, `en`, `zh`).
- String resources are split across multiple files per module:
  `strings.xml`, `automation_strings.xml`, `mcp_strings.xml`,
  `sandbox_shared_strings.xml`, `tool_streaming_strings.xml`, `view_image_strings.xml`.

### Fonts
- **No custom font files are bundled.** All fonts use Android system defaults:
  - `OutfitFamily` → `FontFamily.Default` (UI text)
  - `MonoFamily` → `FontFamily.Monospace` (code blocks, crash logs, terminal)
- Font definitions live in `app/src/main/java/com/newoether/agora/ui/theme/Type.kt`.
- Do NOT add `.ttf`/`.otf` files to `res/font/` — use system fonts to keep APK small.

### Product Flavors
- `play` — Google Play build (no PRoot; `PlaySandboxManager`)
- `fdroid` — F-Droid build (PRoot + Alpine Linux; `ProotSandboxManager`)
- CI builds the **fdroid** flavor: `./gradlew assembleFdroidRelease`

### Native Build
- CMake builds `agora_llama` (llama.cpp JNI) and `agora_proot` (PRoot JNI stub).
- PRoot binaries (`libproot_*.so`, `libtalloc.so`) are built via `build-proot.sh`,
  **not** CMake. They are git-ignored and must be rebuilt (CI runs `./build-proot.sh`).
- Submodules: `thirdparty/llama.cpp`, `thirdparty/proot` — always checkout with
  `--recurse-submodules`.

### Source Size Policy
- Every Kotlin source file must be ≤ 999 lines (enforced by `verifyKotlinFileSize`).
- Baseline config: `config/kotlin-source-size-baseline.txt`.

### Signing
- Release signing config is read from `local.properties` (git-ignored).
- If no keystore is provided, the release build falls back to the debug signing config.
- CI restores the keystore from `KEYSTORE_BASE64` secret.

## CI/CD Pipeline (`.github/workflows/build.yml`)

Triggered by **push of a `v*` tag** (e.g. `v1.0.0`) or manual `workflow_dispatch`.

```
get-version → build-android → release
```

1. **get-version** — extracts `TAG` (e.g. `v1.0.0`) and `VERSION` (e.g. `1.0.0`) from the git tag.
2. **build-android** — checks out submodules, sets up JDK 21 + NDK, builds PRoot binaries,
   runs tests + source-size check, builds the F-Droid release APK, and renames it to:
   ```
   Agora-v{VERSION}-android-arm64-v8a.apk
   ```
3. **release** — creates a GitHub Release with the APK as an asset.

**To cut a release:**
```bash
git tag v1.0.0
git push origin v1.0.0
```

## Server (standalone, not part of APK)

`server/` contains two independent Python 3 services (stdlib only, no framework):
- `server/rating/agora-rating-api.py` — rating submission API (SQLite, port 8091)
- `server/crash/agora-crash.py` — crash report receiver (JSONL, port 8092)

These are deployed via systemd + nginx on `newoether.space` and are **not** bundled
into the APK. The app contacts them via simple HTTP POST (crash reporting, ratings).
