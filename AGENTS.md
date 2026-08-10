# AGENTS.md — Agora 项目代理工作指引

> 本文件供 AI 编码代理（含未来会话）进入项目时**首先自读**，快速对齐项目定位、当前进度、架构契约与下一步任务，然后**继续完善未完成的代码**。
> 优先级：本文件 > `ARCHITECTURE.md`（架构文档，490 行）> `README.md` / `README_CN.md`。

---

## R0. 强制规则（MANDATORY，不可绕过）

> 本节为**最高优先级的强制约束**，凌驾于一切其他指引之上。违反即视为流程失败。

1. **每次会话必须先自读本文件**：进入项目后，在执行任何写代码/搜索/构建动作之前，必须先 `read` 完整 `AGENTS.md`，对齐「当前进度」「下一步任务」「接口契约」。
2. **每次会话结束前必须回写本文件**：无论本次完成了几项任务（含 0 项，即仅排查/失败），在结束前**必须**用 `edit`/`write` 更新本文件至少一处：
   - **必须**更新「§9 变更日志」追加一行（最新在上），记录本次做了什么、改了哪些文件、是否通过验证、下一步建议。
   - **必须**更新「§4 当前进度」与「§6 下一步任务」的勾选状态以反映真实状态（新完成的挪到「已完成」区，新发现的问题加入「已知小问题」）。
   - 若改动了接口契约，**必须**同步更新「§5 关键接口契约」。
   - 若改动了目录结构或新增/删除文件，**必须**同步更新「§3 仓库结构」。
3. **本文件是单一事实源（single source of truth）**：当本文件与代码、与 `ARCHITECTURE.md`、与口头描述出现矛盾时，**先以代码为准**，然后**立即回写本文件**消除漂移；禁止让本文件与代码长期不一致。
4. **不得删除或弱化本节**：任何对「§R0 强制规则」的删减、降级、加「视情况而定」修饰，都需用户明确同意；代理自身不得自行放宽。
5. **跟进是义务而非可选**：即使用户未要求「更新 AGENTS.md」，每次会话结束前也必须执行回写；用户明确说「不用更新」时才可跳过，并在变更日志注明「依用户要求跳过本次回写」。
6. **语言只保留中英文**（MANDATORY）：App 的语言资源**仅** `values/`（英文）与 `values-zh/`（简体中文）。**禁止**新增 `values-es`/`values-fr`/`values-de`/`values-ru`/`values-ja`/`values-ko`/`values-ar`/`values-vi`/`values-pt-rBR`/`values-zh-rTW` 等其他语言目录。语言选项在 `SettingsLanguagePage.kt` 与 `MainActivity.attachBaseContext()` 中声明，两者必须同步（当前为 `system`/`en`/`zh`）。
7. **不打包自定义字体**（MANDATORY）：**禁止**在 `res/font/` 下添加 `.ttf`/`.otf` 文件。UI 字体使用 `FontFamily.Default`（系统默认），代码/终端字体使用 `FontFamily.Monospace`（系统等宽）。字体定义在 `ui/theme/Type.kt`（`OutfitFamily`/`MonoFamily`）。
8. **编译验证必须提交到 GitHub 上编译**（MANDATORY）：本地为离线环境，缺 Android SDK/NDK/CMake 工具链，**无法** `./gradlew assembleFdroidRelease`。因此**任何代码改动后的编译验证必须通过 `git commit && git push` 提交到 GitHub**（`origin = https://github.com/ojbkxc/Agora.git`，分支 `master`），由 GitHub CI（`.github/workflows/build.yml`，见 §R2）执行构建。**禁止**在未 push 到 GitHub 编译通过前声称某子任务「完成/已验证」。
9. **通过 GitHub 编译报错迭代修复**（MANDATORY）：push 后若 GitHub CI 编译/测试失败，**必须**读取 CI 日志中的报错，据报错本地修复后**再次 commit & push**，循环直至 CI 全绿。**不得**跳过 CI 失败直接推进下一子任务；**不得**用 `@Suppress`/注释掉测试/降低 lint 阈值等方式绕过 CI 报错（除非用户明确同意）。CI 全绿是子任务完成的**唯一**编译验证判据。
10. **自动推进项目（auto-continue，默认行为）**（MANDATORY）：用户说「自动继续」/「继续」/「auto」或未明确叫停时，代理**必须自主连续推进**项目任务，不得每完成一小步就停下来询问下一步。具体要求：
    - 进入项目后按 §0 流程**自主**挑选下一个最高优先级的最小可独立交付子任务并开工，不等用户逐项指派。
    - 单个子任务完成后**立即**开始下一个，无需请求许可；仅在遇到「方向性分歧」「破坏性操作」「违反硬约束」「信息严重不足且无法合理推断」时才用 `question` 工具询问用户。
    - 推进过程中**主动**走 §R2.3 CI 修复闭环、§R0 回写，不要等用户提醒。
    - 用户未说「自动继续」时也鼓励减少不必要的中途提问，但可在阶段切换时简要汇报进度；用户说「自动继续」后则**连续作业**直到任务全部完成或遇阻才停下汇报。
    - 停下汇报时应附「已完成的 / 正在做的 / 下一步打算做的」三段式摘要，便于用户一句话继续（如「继续」「换方向」「停」）。

---

## R2. GitHub CI 编译验证策略（MANDATORY，配合 §R0.8–R0.9）

> 本节落实 §R0.8/R0.9 的「提交到 GitHub 编译 + 据报错修复」闭环。本地离线不可编译，GitHub CI 是**唯一**编译验证通道。

### R2.1 CI 触发条件
- **push tag `v*`**（如 `v1.0.0`）或手动 `workflow_dispatch` 触发 `.github/workflows/build.yml`。
- CI 在 GitHub-hosted runner（ubuntu-latest，可联网拉 SDK/NDK/依赖）上执行，规避本地离线缺工具链问题。
- 流水线结构：`get-version` → `build-android` → `release`（详见 §R2.2）。

### R2.2 CI 必须执行的步骤（全绿才算通过）
```
# .github/workflows/build.yml 执行流程
1. get-version: 从 git tag 提取 TAG (v1.0.0) 和 VERSION (1.0.0)
2. build-android:
   - checkout (submodules: recursive) — 拉取 llama.cpp + proot 子模块
   - setup JDK 21 (temurin) + Android SDK + NDK 28.2.13676358
   - 恢复签名密钥 (KEYSTORE_BASE64 secret → local.properties)
   - ./build-proot.sh force — 构建 PRoot 原生二进制 (libproot_*.so, libtalloc.so)
   - ./gradlew -p build-logic test — 构建插件测试
   - ./gradlew verifyKotlinFileSize — 源码大小策略 (每文件 ≤ 999 行)
   - ./gradlew assembleFdroidRelease — 构建 F-Droid Release APK
   - 重命名: app-fdroid-release.apk → Agora-v{VERSION}-android-arm64-v8a.apk
3. release: gh release create — 上传 APK 到 GitHub Release
```

### R2.3 据报错修复的迭代流程（每次 push 后必走）
1. `git push origin master`（或 `git push origin v1.0.0` 触发发版）。
2. 用 `gh run watch` 或浏览器查看 `https://github.com/ojbkxc/Agora/actions` 的运行结果。
3. 若失败：`gh run view --log-failed` 取报错日志，定位首个 `error:` / `FAILED` / `e: file://` 行。
4. 本地按报错修代码（修 import/类型/资源引用/Composable 签名等），**不**绕过（不 `@Suppress`、不删测试、不降低 lint 阈值）。
5. `git commit && git push`，回到步骤 2，直至 CI 全绿。
6. CI 全绿后才能在 §4/§6 勾选该子任务「完成」并在 §9 变更日志注明「CI 全绿验证通过」。

### R2.4 本地可做的静态检查（push 前自检，减少 CI 往返）
- 人工 review：import 路径、Composable 签名、资源引用（`R.string.*`/`R.drawable.*`）、`@Composable` 注解。
- 确认无 `R.font.*` 引用（§R0.7 禁止自定义字体）。
- 确认无非 en/zh 的语言资源目录或语言选项（§R0.6）。
- 确认 Kotlin 文件不超过 999 行（`./gradlew verifyKotlinFileSize` 基线）。

### R2.5 CI workflow 维护
- 若新增依赖或改变构建配置（NDK 版本、ABI、flavor），同步更新 `.github/workflows/build.yml` 与 `app/build.gradle.kts`。
- 若新增 signing secret，在 GitHub repo Settings → Secrets 配置后更新 workflow 的 `env` 映射。

---

## 0. 进入项目后的标准流程（必读）

1. **通读本文件**（尤其是「§R0 强制规则」「当前进度」「下一步任务」「编码约定」五节）。
2. 按「下一步任务」的优先级顺序挑选一个**最小可独立交付**的子任务开工。
3. 开工前用 `read`/`grep`/`glob` 阅读相关已有代码；**复用既有 Composable、ViewModel、Repository 与命名**，不要另起炉灶。
4. 每完成一个子任务：确认无 `R.font.*` 引用、无多余语言资源、Kotlin 文件 ≤ 999 行，然后 `git push` 触发 CI 验证。
5. **回写本文件**（强制，见 §R0）：更新「当前进度」「下一步任务」勾选状态，并在「变更日志」追加一行。
6. **不要**主动 `git commit`，除非用户明确要求。**不要**写未经请求的 README/文档。**不要**加注释除非用户要求。
7. **会话结束前再次确认 §R0 的回写已执行**；若未执行，补做后再结束。

---

## 1. 项目定位（一句话）

Agora 是 **BYOK（Bring Your Own Key）LLM 客户端** — Android 原生应用（Kotlin + Jetpack Compose），支持多 LLM 提供商、智能代理工作流、本地 LLM 推理（llama.cpp via NDK）、远程设备控制。所有数据本地存储，无遥测、无追踪。MIT 许可证。

## 2. 硬约束（任何改动都不得违反）

| 维度 | 约束 | 验证方式 |
|---|---|---|
| 应用 ID | `com.newoether.agora` | `app/build.gradle.kts` |
| ABI | **仅 `arm64-v8a`** | `ndk { abiFilters }` |
| SDK | minSdk 24 / targetSdk 36 / compileSdk 36 | `defaultConfig` |
| NDK | `28.2.13676358` | `ndkVersion` |
| 语言 | Kotlin 2.3.21 + Compose BOM 2026.05.01 | `gradle/libs.versions.toml` |
| i18n | **仅 en + zh**（§R0.6） | `res/values*/` 目录 |
| 字体 | **无自定义字体**（§R0.7） | `res/font/` 不存在 |
| 源码大小 | 每 Kotlin 文件 ≤ 999 行 | `./gradlew verifyKotlinFileSize` |
| 版本 | versionName `1.0.0` / versionCode `1` | `defaultConfig` |
| 产物命名 | `Agora-v{VERSION}-android-arm64-v8a.apk` | CI `build.yml` |
| 许可证 | MIT | `LICENSE` |

新增依赖前先评估对 APK 体积的影响；优先使用 `gradle/libs.versions.toml` 版本目录统一管理。

## 3. 仓库结构与模块划分

```
Agora/
├── AGENTS.md                          # 本文件（代理工作指引）
├── ARCHITECTURE.md                    # 架构文档（490 行）
├── README.md / README_CN.md           # 英文/中文说明
├── build.gradle.kts                   # 顶层构建（声明插件）
├── settings.gradle.kts                # include(":app") + includeBuild("build-logic")
├── gradle.properties                  # Gradle 配置
├── gradle/libs.versions.toml          # 版本目录（AGP/Kotlin/Compose/Room 等）
├── build-proot.sh                     # PRoot 原生二进制构建脚本（232 行）
├── mkdocs.yml                         # MkDocs 文档配置（en + zh）
├── app/                               # 主 Android 应用模块（唯一 Gradle 模块）
│   ├── build.gradle.kts              # 应用构建配置（flavors: play + fdroid）
│   ├── proguard-rules.pro            # ProGuard 规则
│   ├── schemas/                      # Room DB schema 快照（v10–v22）
│   └── src/
│       ├── main/                      # 主源集
│       │   ├── AndroidManifest.xml
│       │   ├── assets/               # Provider 图标（SVG/PNG）
│       │   ├── cpp/                  # JNI 原生代码（CMake）
│       │   │   ├── CMakeLists.txt    # 构建 agora_llama + agora_proot
│       │   │   ├── llama_jni.cpp     # llama.cpp JNI 绑定
│       │   │   ├── llama_chat_jni.cpp
│       │   │   └── proot_jni.cpp     # PRoot JNI stub
│       │   ├── java/com/newoether/agora/
│       │   │   ├── AgoraApplication.kt   # Application（持有 AppContainer）
│       │   │   ├── MainActivity.kt       # 唯一 Activity（Compose 入口）
│       │   │   ├── api/               # LLM Provider 适配器（38 文件）
│       │   │   │   ├── LlmProvider.kt    # Provider 接口 + StreamEvent
│       │   │   │   ├── HttpClient.kt     # OkHttp 单例 + SSE
│       │   │   │   ├── openai/           # OpenAI/DeepSeek/Qwen/OpenRouter/Groq/Custom
│       │   │   │   ├── anthropic/        # Anthropic Claude
│       │   │   │   ├── gemini/           # Google Gemini
│       │   │   │   ├── ollama/           # 本地 Ollama
│       │   │   │   └── local/            # llama.cpp 本地推理
│       │   │   ├── data/              # Room + DataStore + Repository（49 文件）
│       │   │   ├── model/             # 数据模型 / DTO（17 文件）
│       │   │   ├── viewmodel/         # ViewModel + 生成控制器（86 文件）
│       │   │   ├── ui/                # Compose UI（115 文件）
│       │   │   │   ├── chat/          # 聊天界面（58 文件）
│       │   │   │   ├── settings/      # 设置界面（34 文件）
│       │   │   │   ├── theme/         # Type.kt / Theme.kt / Color.kt
│       │   │   │   ├── tasks/         # 任务历史（4 文件）
│       │   │   │   ├── onboarding/    # 欢迎引导
│       │   │   │   └── components/    # 通用组件
│       │   │   ├── tool/              # 工具提供者（13 文件）
│       │   │   ├── service/           # 前台服务 + WorkManager（8 文件）
│       │   │   ├── mcp/               # MCP 协议客户端（4 文件）
│       │   │   ├── sandbox/           # 沙盒接口
│       │   │   ├── automation/        # 任务、循环、调度
│       │   │   ├── di/AppContainer.kt # 手动 DI 容器
│       │   │   └── util/              # 工具类（CrashReporter 等）
│       │   └── res/                   # 资源
│       │       ├── values/            # 英文（默认）— 7 个 xml
│       │       ├── values-zh/         # 简体中文 — 6 个 xml
│       │       ├── values-night/      # 夜间主题
│       │       ├── drawable/          # 图标
│       │       ├── raw/               # 欢迎视频（MP4）
│       │       └── xml/               # backup/data extraction rules
│       ├── fdroid/                    # F-Droid flavor（PRoot 沙盒）
│       ├── play/                      # Google Play flavor（无 PRoot）
│       └── test/                      # 单元测试
├── server/                            # 独立 Python 服务（非 APK 运行时）
│   ├── rating/                        # 评分提交 API（SQLite, port 8091）
│   └── crash/                         # 崩溃报告接收（JSONL, port 8092）
├── thirdparty/                        # 第三方原生依赖
│   ├── llama.cpp/                     # git submodule
│   ├── proot/                         # git submodule
│   └── talloc/                        # 内联源码
├── build-logic/                       # Gradle included build（字节码修复 + 源码大小策略）
├── docs/                              # MkDocs 用户手册（en + zh）
├── fastlane/                          # 应用商店元数据（en-US + zh-CN）
├── scripts/                           # 辅助脚本（round_icon.py）
├── config/                            # 源码大小基线配置
└── .github/workflows/
    ├── build.yml                      # CI/CD: 构建 APK + GitHub Release
    └── mkdocs.yml                     # 文档部署到 GitHub Pages
```

**数据流**：`UI (Compose) → ViewModel → Repository → (Room/DataStore | LlmProvider → OkHttp SSE | LlamaEngine JNI)`；工具调用经 `tool/`；后台任务经 `service/` + WorkManager。

## 4. 当前进度（截至 2026-08-10）

### ✅ 已完成
- **语言精简为 en + zh**：删除 `values-zh-rTW/` 及 ar/de/es/fr/ja/ko/pt-rBR/ru/vi 共 10 个语言资源目录；精简 `SettingsLanguagePage.kt`（13→3 选项）、`MainActivity.attachBaseContext()`（Locale 映射）、`DefaultSystemPrompt.titleForLocale()`、`DocumentationFab.kt`；补全 `values-zh/automation_strings.xml`（93 条简体中文翻译）；精简 `mkdocs.yml`（11→2 语言）+ 删除 `docs/zh-Hant/` 等 9 个文档语言目录。
- **删除全部自定义字体**：删除 `res/font/` 下 9 个 TTF 文件（~23.1MB）；`Type.kt` 改为 `MonoFamily = FontFamily.Monospace` / `OutfitFamily = FontFamily.Default`；`Theme.kt` / `MainActivity.kt` / `SettingsSandboxPage.kt` 字体引用改用系统字体；清理无效 import。
- **版本号改为 1.0.0**：`app/build.gradle.kts` `versionCode = 1`, `versionName = "1.0.0"`。
- **编译流水线改造**：重写 `.github/workflows/build.yml`，参照 RustSync 模式：push tag `v*` 触发 → `get-version` → `build-android`（PRoot + APK）→ `release`（GitHub Release），产物命名 `Agora-v{VERSION}-android-arm64-v8a.apk`。
- **AGENTS.md 创建**：本文件。
- **变更已推送**：commit `5f0de741` → `origin/master`（308 文件，+361/-35086 行）。

### 🟡 已知问题
- **未通过 CI 编译验证**：当前改动尚未通过 GitHub CI 实际构建验证（需 push tag `v1.0.0` 触发流水线）。本地离线无法 `./gradlew assembleFdroidRelease`。
- **PRoot 二进制需 CI 构建**：`build-proot.sh` 产物（`libproot_*.so`, `libtalloc.so`）被 `.gitignore` 忽略，CI 中由 `./build-proot.sh force` 现场构建。
- **签名密钥**：Release 签名需在 GitHub Secrets 配置 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`；未配置时回退 debug 签名。

### ❌ 未完成
1. **CI 编译验证**：push tag `v1.0.0` 触发流水线，据 CI 报错迭代修复至全绿。
2. **首次发版**：CI 全绿后确认 GitHub Release 产物 `Agora-v1.0.0-android-arm64-v8a.apk` 正确。

## 5. 关键接口契约（不要破坏既有签名）

### 应用入口（已固化）
- `AgoraApplication`：持有 `AppContainer`（手动 DI 容器，进程级单例）。
- `MainActivity.attachBaseContext(newBase: Context)`：根据 `SettingsManager.appLanguage` 设置 Locale（当前仅 `en`/`zh`/`system`）。
- `MainActivity.onCreate()`：安装 Splash → 初始化 DebugLog → 创建通知渠道 → 请求通知权限 → Compose `setContent { AgoraTheme { ... } }`。

### LLM Provider 契约（已固化）
- `LlmProvider` 接口（`api/LlmProvider.kt`）：定义 `StreamEvent` 密封类（TextChunk / thoughtChunk / ToolCallUpdate / ToolCallRequest / UsageUpdate / Retrying / Error）。
- `HttpClient`（`api/HttpClient.kt`）：OkHttp 单例，SSE 流式解析（`BufferedSource` 逐行读 `data:`）。
- Provider 实现：OpenAI / Anthropic / Gemini / DeepSeek / Qwen / OpenRouter / Groq / Ollama / Custom / Local（llama.cpp JNI）。

### 主题与字体契约（已固化，§R0.7）
- `OutfitFamily = FontFamily.Default`（UI 文本）— `ui/theme/Type.kt`。
- `MonoFamily = FontFamily.Monospace`（代码/终端/崩溃日志）— `ui/theme/Type.kt`。
- `AgoraTheme(themeMode, colorSchemePreset, schemeStyle, dynamicColor, fontPreference, customFontPath, content)` — `ui/theme/Theme.kt`。
- `ChatType` 对象：聊天界面的排版 scale（title/input/body/sub/meta/code 六层），`chatFontFamily` 可变（由 Theme.kt 根据 fontPreference 设置）。

### 数据层契约（已固化）
- Room Database v22（`data/local/ChatDatabase`）：树形消息结构，schema 快照在 `app/schemas/`。
- `SettingsManager`（`data/SettingsManager`）：DataStore Preferences，管理所有用户设置（appLanguage / themeMode / colorScheme / fontPreference 等）。
- `AppContainer`（`di/AppContainer.kt`）：手动 DI，提供 `chatViewModelFactory()` / `conversationRepository` 等进程级单例。

### i18n 契约（已固化，§R0.6）
- 语言选项：`SettingsLanguagePage.kt` 中 `LanguageOption("system"|"en"|"zh")`。
- Locale 映射：`MainActivity.attachBaseContext()` 中 `when (langCode) { "zh" -> Locale("zh","CN"); "en" -> Locale("en"); else -> null }`。
- 文档语言映射：`DocumentationFab.kt` 中 `langTag.startsWith("zh") -> "zh/"`，其余 → 英文根。
- 系统提示标题：`DefaultSystemPrompt.titleForLocale()` 中 `"zh" -> 简体中文标题`，其余 → "Default"。

### Product Flavors（已固化）
- `play`：Google Play 版，`PlaySandboxManager`（无 PRoot）。
- `fdroid`：F-Droid 版，`ProotSandboxManager`（PRoot + Alpine Linux）。
- CI 构建 fdroid flavor：`./gradlew assembleFdroidRelease`。

### 原生构建（已固化）
- CMake（`app/src/main/cpp/CMakeLists.txt`）：构建 `agora_llama`（llama.cpp JNI）+ `agora_proot`（PRoot JNI stub）。
- PRoot 二进制（`build-proot.sh`）：构建 `libproot_exec.so` / `libproot_loader.so` / `libtalloc.so` → `app/src/{main,fdroid}/jniLibs/arm64-v8a/`。
- 子模块：`thirdparty/llama.cpp` + `thirdparty/proot`（checkout 需 `--recurse-submodules`）。

## 6. 下一步任务（按优先级，逐项勾选）

> 每项都是可独立交付的最小单元。完成即打勾并移到「已完成」区。

### P0 — CI 编译验证与首次发版
- [ ] 配置 GitHub Secrets（`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`）用于 Release 签名（可选，未配置则用 debug 签名）。
- [ ] `git tag v1.0.0 && git push origin v1.0.0` 触发流水线。
- [ ] 据 CI 报错迭代修复至全绿（§R2.3）。
- [ ] 确认 GitHub Release 产物 `Agora-v1.0.0-android-arm64-v8a.apk` 正确。

### P1+ — 后续迭代（按需推进）
- [ ] 功能开发 / bug 修复 / 性能优化等用户指派任务。

## 7. 编码约定（强制）

- **语言**：代码与注释一律英文（标识符、doc comment、日志消息）；本文件和面向用户的文档用简体中文。
- **不写注释**除非用户要求；让类型与函数名自解释。KDoc（`/** */`）允许且鼓励用于 public API。
- **UI**：100% Jetpack Compose + Material 3，无 XML 布局（`themes.xml` 仅用于启动屏）。单 Activity 架构。
- **架构**：MVVM + Coroutines & Flow。ViewModel 持有 `StateFlow`，UI 通过 `collectAsState()` 订阅。
- **DI**：手动 DI via `AppContainer`，不用 Hilt/Dagger。
- **网络**：OkHttp + SSE，不用 Retrofit/Ktor。流式响应逐行解析 `data:` 行。
- **序列化**：`kotlinx.serialization`（JSON）。
- **存储**：Room（树形消息）+ DataStore Preferences。数据库迁移需新增 schema 快照到 `app/schemas/`。
- **i18n**（§R0.6）：仅 `values/`（en）+ `values-zh/`（zh）。新增字符串需同时在两处添加。`SettingsLanguagePage.kt` 与 `MainActivity.attachBaseContext()` 必须同步。
- **字体**（§R0.7）：`OutfitFamily` = `FontFamily.Default`，`MonoFamily` = `FontFamily.Monospace`。禁止 `R.font.*` 引用。
- **命名**：Composable 函数 PascalCase（如 `ChatApp`），ViewModel/Repository/Manager 后缀明确，包名单数。
- **源码大小**：每 Kotlin 文件 ≤ 999 行（`./gradlew verifyKotlinFileSize` 强制）。
- **测试**：单元测试放 `app/src/test/`，F-Droid 专属测试放 `app/src/testFdroid/`。
- **产物**：CI 产出 `Agora-v{VERSION}-android-arm64-v8a.apk`，仅 `arm64-v8a` ABI。

## 8. 常用命令

```bash
# 构建 F-Droid Release APK（CI 主目标）
./gradlew assembleFdroidRelease

# 构建 Google Play Release APK
./gradlew assemblePlayRelease

# 构建 Play AAB bundle
./gradlew bundlePlayRelease

# 单元测试
./gradlew test

# 构建插件测试（字节码修复 + 源码大小策略）
./gradlew -p build-logic test

# 源码大小策略验证（每文件 ≤ 999 行）
./gradlew verifyKotlinFileSize

# 构建 PRoot 原生二进制（需 NDK 28.2.13676358）
./build-proot.sh

# Lint
./gradlew lint

# 发版（触发 CI 流水线）
git tag v1.0.0
git push origin v1.0.0
# → CI 自动构建 Agora-v1.0.0-android-arm64-v8a.apk 并发布到 GitHub Release

# 查看 CI 运行状态
gh run watch
gh run view --log-failed    # 失败时查看报错日志
```

环境：本地离线，缺 Android SDK/NDK/CMake，**无法**本地 `./gradlew assembleFdroidRelease`。编译验证走 GitHub CI（§R2）。子模块 checkout 需 `--recurse-submodules`。

## 9. 变更日志（追加新行，最新在上）

- 2026-08-10 重写 AGENTS.md 参照 AXON 严谨流程：新增 §R0 强制规则（10 条，含 i18n/字体/CI 验证/auto-continue）、§R2 GitHub CI 编译验证策略、§0 标准流程、§1 项目定位、§2 硬约束、§3 仓库结构、§4 当前进度、§5 关键接口契约、§6 下一步任务、§7 编码约定、§8 常用命令、§10 参考索引。未改代码，仅重写 AGENTS.md。下一步：push tag v1.0.0 触发 CI 验证。
- 2026-08-10 语言精简 + 字体删除 + 流水线改造 + 首版 AGENTS.md：删除 10 个非 en/zh 语言资源目录 + 9 个文档语言目录；删除 9 个 TTF 字体文件（~23.1MB）改用系统字体；版本号改为 1.0.0/1；重写 `.github/workflows/build.yml`（tag 触发 → APK → GitHub Release，产物 `Agora-v{VERSION}-android-arm64-v8a.apk`）；创建 AGENTS.md；从 `.gitignore` 移除 AGENTS.md 忽略规则。已推送 commit `5f0de741` → `origin/master`（308 文件，+361/-35086 行）。未通过 CI 编译验证。

## 10. 参考索引

- 架构文档：`ARCHITECTURE.md`（490 行，详细架构说明）。
- 版本目录：`gradle/libs.versions.toml`（AGP/Kotlin/Compose/Room 等版本统一管理）。
- 上游借鉴：`/opt/github/RustSync`（编译流水线参照：tag 触发 → 产物命名 → GitHub Release 模式）。
- 关键文件速查：
  - 应用入口：`app/src/main/java/com/newoether/agora/MainActivity.kt`
  - Application：`app/src/main/java/com/newoether/agora/AgoraApplication.kt`
  - DI 容器：`app/src/main/java/com/newoether/agora/di/AppContainer.kt`
  - Provider 接口：`app/src/main/java/com/newoether/agora/api/LlmProvider.kt`
  - HTTP 客户端：`app/src/main/java/com/newoether/agora/api/HttpClient.kt`
  - 主题：`app/src/main/java/com/newoether/agora/ui/theme/{Type,Theme,Color}.kt`
  - 语言选项：`app/src/main/java/com/newoether/agora/ui/settings/SettingsLanguagePage.kt`
  - 系统提示：`app/src/main/java/com/newoether/agora/data/DefaultSystemPrompt.kt`
  - 构建配置：`app/build.gradle.kts`
  - CI 流水线：`.github/workflows/build.yml`
  - PRoot 构建：`build-proot.sh`
  - 原生构建：`app/src/main/cpp/CMakeLists.txt`
