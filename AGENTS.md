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
- **`git status` 确认无残留未 commit 修改**：会话开始前和 commit 前各执行一次，确保所有修改的文件都被 staged。**这是最常见的 CI 失败根因之一**——修改了文件但忘记 commit，CI 用的是旧版本。
- 人工 review：import 路径、Composable 签名、资源引用（`R.string.*`/`R.drawable.*`）、`@Composable` 注解。
- **Kotlin 类型检查（本地无法编译，必须人工查）**：
  - `suspend` 函数/lambda：确认 lambda 类型匹配（`suspend (T) -> Unit` vs `(T) -> Unit`）。`Flow.emit()` / `MutableSharedFlow.emit()` 是 suspend，不能在普通 lambda 中调用。
  - `nullable` 类型：确认 `String?` vs `String` 传递正确。`StateFlow<T?>.value` 返回 `T?`，传给非空参数需加 `?: return` 或 `!!`。
  - 新增参数：确认所有调用点都传了正确类型的参数。
- **新增字符串资源**：确认 `values/strings.xml`（en）+ `values-zh/strings.xml`（zh）**都**添加了同名 key。
- **新增设置项**：确认 `SettingsPreferenceSchema` + `SettingsManager` + `SettingsRepository` + UI 四层**都**添加了。
- 确认无 `R.font.*` 引用（§R0.7 禁止自定义字体）。
- 确认无非 en/zh 的语言资源目录或语言选项（§R0.6）。
- 确认 Kotlin 文件不超过 999 行（`./gradlew verifyKotlinFileSize` 基线）。

### R2.5 CI workflow 维护
- 若新增依赖或改变构建配置（NDK 版本、ABI、flavor），同步更新 `.github/workflows/build.yml` 与 `app/build.gradle.kts`。
- 若新增 signing secret，在 GitHub repo Settings → Secrets 配置后更新 workflow 的 `env` 映射。

---

## 0. 进入项目后的标准流程（必读）

1. **通读本文件**（尤其是「§R0 强制规则」「当前进度」「下一步任务」「编码约定」五节）。
1b. **`git status` 检查残留修改**：若工作目录有未 commit 的修改（来自前次会话遗漏），先理解其内容并 commit，再开始新工作。**不要**在新工作开始前 `git stash` 或 `git checkout -- .` 丢弃前次修改——先搞清楚是什么、是否需要保留。
2. 按「下一步任务」的优先级顺序挑选一个**最小可独立交付**的子任务开工。
3. 开工前用 `read`/`grep`/`glob` 阅读相关已有代码；**复用既有 Composable、ViewModel、Repository 与命名**，不要另起炉灶。
4. 每完成一个子任务：执行 §R2.4 静态检查清单，然后 `git add -A && git status` 确认所有修改已 staged，`git commit && git push` 触发 CI 验证。
5. **回写本文件**（强制，见 §R0）：更新「当前进度」「下一步任务」勾选状态，并在「变更日志」追加一行。
6. **不要**主动 `git commit`，除非用户明确要求。**不要**写未经请求的 README/文档。**不要**加注释除非用户要求。
7. **会话结束前再次确认 §R0 的回写已执行**；若未执行，补做后再结束。

---

## 1. 项目定位（一句话）

Agora 是 **BYOK（Bring Your Own Key）LLM 客户端** — Android 原生应用（Kotlin + Jetpack Compose），支持多 LLM 提供商、智能代理工作流、本地 LLM 推理（llama.cpp via NDK）、远程设备控制。所有数据本地存储，无遥测、无追踪。MIT 许可证。

## 2. 硬约束（任何改动都不得违反）

| 维度 | 约束 | 验证方式 |
|---|---|---|
| 应用 ID | `com.lxseek.chat` | `app/build.gradle.kts` |
| ABI | **仅 `arm64-v8a`** | `ndk { abiFilters }` |
| SDK | minSdk 24 / targetSdk 36 / compileSdk 36 | `defaultConfig` |
| NDK | `28.2.13676358` | `ndkVersion` |
| 语言 | Kotlin 2.3.21 + Compose BOM 2026.05.01 | `gradle/libs.versions.toml` |
| i18n | **仅 en + zh**（§R0.6） | `res/values*/` 目录 |
| 字体 | **无自定义字体**（§R0.7） | `res/font/` 不存在 |
| 源码大小 | 每 Kotlin 文件 ≤ 999 行 | `./gradlew verifyKotlinFileSize` |
| 版本 | versionName `1.0.40` / versionCode `41` | `defaultConfig` |
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
│       │   ├── java/com/lxseek/chat/
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
│       │   │   ├── shell/             # 内嵌 Conch 管理（ConchServiceManager）
│       │   │   ├── service/           # 前台服务 + WorkManager（8 文件）
│       │   │   ├── mcp/               # MCP 协议客户端（4 文件）
│       │   │   ├── sandbox/           # 沙盒接口
│       │   │   ├── automation/        # 任务、循环、调度
│       │   │   ├── di/AppContainer.kt # 手动 DI 容器
│       │   │   └── util/              # 工具类（CrashReporter / AppExecutors / ErrorSanitizer / TtsManager / SshClient 等）
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
├── server/                            # 服务端代码
│   ├── rating/                        # 评分提交 API（Python/SQLite, port 8091）
│   ├── crash/                         # 崩溃报告接收（Python/JSONL, port 8092）
│   └── conch/                         # 内嵌 Conch shell 服务器（Go 源码 + gomobile 绑定）
│       ├── mobile/mobile.go           # gomobile 绑定包（导出 Start/Stop/PublicKey/IsRunning）
│       ├── build-android.sh           # gomobile bind → app/libs/conch.aar
│       ├── main.go                    # 独立 conch 服务器入口（非 Android 用）
│       ├── config/ crypto/ handler/ shell/ buildinfo/  # conch 核心包
│       └── go.mod                     # Go module: github.com/newo-ether/conch
├── thirdparty/                        # 第三方原生依赖
│   ├── llama.cpp/                     # git submodule
│   ├── proot/                         # git submodule
│   └── talloc/                        # 内联源码
├── build-logic/                       # Gradle included build（字节码修复 + 源码大小策略）
├── docs/                              # MkDocs 用户手册（en + zh）
├── fastlane/                          # fastlane 自动化（Fastfile/Appfile/Gemfile + 元数据 en-US + zh-CN）
│   ├── Fastfile                       # lane 定义（build_fdroid/build_play/github_release/validate_metadata/generate_changelog/release）
│   ├── Appfile                        # package_name("com.newoether.agora")
│   ├── Gemfile                        # fastlane Ruby 依赖
│   └── metadata/android/             # F-Droid 元数据（en-US + zh-CN，含 changelogs + screenshots）
├── scripts/                           # 辅助脚本（round_icon.py）
├── config/                            # 源码大小基线配置
└── .github/workflows/
    ├── build.yml                      # CI/CD: 构建 APK + GitHub Release
    ├── ci.yml                         # PR/push 编译检查
    ├── fastlane.yml                   # fastlane 元数据验证（PR/push 触发）
    └── mkdocs.yml                     # 文档部署到 GitHub Pages
```

**数据流**：`UI (Compose) → ViewModel → Repository → (Room/DataStore | LlmProvider → OkHttp SSE | LlamaEngine JNI)`；工具调用经 `tool/`；后台任务经 `service/` + WorkManager。

## 4. 当前进度（截至 2026-08-12）

### ✅ 已完成
- **v1.0.39 sherpa-onnx 端侧 ASR/VAD/TTS 集成 + 发版**：端侧 ASR（sherpa-onnx OnlineRecognizer 流式）+ Silero VAD（AudioRecord + VoiceRecorder 重写）+ 端侧 TTS（SherpaTtsEngine）+ 模型下载管理 + 设置 UI。首次 CI 因 `VoiceRecorder.kt:216` `File()` 包裹错误失败，修复后全绿。bump versionCode 39→40 / versionName 1.0.38→1.0.39，Build & Release #31883331881 全绿（get-version ✓ / build-android ✓ / release ✓），Release `Agora-v1.0.39-android-arm64-v8a.apk` (21.64 MB) 已发布。
- **v1.0.38 Apple 风格 UI 细化**：降低全局阴影/elevation（底部栏/顶栏/下拉菜单/语音覆盖等）、助手消息加浅灰气泡、统一圆角（18/20/24dp）、VoiceMicButton 统一风格（48dp/低阴影/弱脉冲）、设置卡片移除接缝改用细线分隔、顶栏胶囊圆角 50→16 + 高度 180→140dp。CI 全绿验证通过（CI #31791904839 ✓ / Build & Release #31791927093 ✓），Release `Agora-v1.0.38-android-arm64-v8a.apk` 已发布。
- **v1.0.37 修复 partialTranscript bug + 远程 ASR UI + 死代码清理**：① 修复 `_partialTranscript` 不更新 bug（收集 `SttManager.partialText`）；② 添加远程 ASR 设置 UI（Base URL/API Key/Model 输入框）；③ 删除 3 个死代码文件 + 2 个死字段/参数；④ 删除 84 个孤立字符串（恢复误删的 10 个 `sandbox_snackbar_*`）。CI 全绿验证通过（CI #31788398268 ✓ / Build & Release #31788418064 ✓），Release `Agora-v1.0.37-android-arm64-v8a.apk` 已发布。
- **v1.0.36 删除死代码 AsrModelManager + 孤立字符串**：v1.0.34 删除假 ASR UI 后的遗留清理，删除 `AsrModelManager.kt`（140 行死代码）+ 11 个孤立 ASR 字符串（en+zh）。CI 全绿验证通过（CI #31780615261 ✓ / Build & Release #31780642151 ✓），Release `Agora-v1.0.36-android-arm64-v8a.apk` 已发布。
- **v1.0.35 接线 VoiceGradientBackground**：发现 v1.0.34 创建的 `VoiceGradientBackground.kt` 从未被调用（死代码），接入 `VoiceConversationStatusOverlay` 作为动态渐变背景。CI 全绿验证通过（CI #31779195803 ✓ / Build & Release #31779205895 ✓），Release `Agora-v1.0.35-android-arm64-v8a.apk` 已发布。
- **v1.0.34 UI 细化 + 删除假 ASR 模型下载 UI**：三球波形动画（`VoiceWaveformIndicator.kt`，借鉴 VoiceRobot）+ 渐变背景（`VoiceGradientBackground.kt`）+ VoiceMicButton 用波形替换 halo + VAD 参数改进（借鉴 Half_duplex Silero VAD）+ 删除假 ASR 模型下载 UI（`SherpaAsrEngine`/`AsrModelManager` 未真正实现）+ amplitude 接线。CI 全绿验证通过（CI #31774607828 ✓ / Build & Release #31774617629 ✓），Release `Agora-v1.0.34-android-arm64-v8a.apk` 已发布。
- **v1.0.30 语音对话 UI + 存相册**：① VoiceMicButton 增强脉冲光晕（listening 时红色 halo ring 扩散动画 + 活跃时 elevation 8dp）。② 新建 `VoiceConversationStatusOverlay.kt`（128 行）——语音对话状态覆盖层，显示在底部栏上方：状态图标 + 状态文字（聆听中/思考中/朗读中）+ 部分识别文本（listening 时实时显示 STT partial transcript）。③ 分享面板加「保存到相册」按钮（SaveAlt 图标），`MessageExportController.saveLongImageToGallery()` 用 MediaStore API 29+ 存到 `Pictures/Agora/`，API 24-28 用 legacy 路径 + ACTION_MEDIA_SCANNER_SCAN_FILE。`MessageLongImageRenderer.renderToBitmap()` 公开方法。strings en+zh 各加 2 个字符串。CI 全绿验证通过（CI #31759321853 ✓ / Build & Release #31759324326 ✓）。
- **v1.0.29 ASR 设置 UI + v1.0.28 编译修复**：v1.0.28 分享选择 UI 有 3 个编译错误（ChatApp.kt:691 多余 `) {` / ChatTopBar.kt 缺 TextButton import / ShareSelectionFab.kt 错用 AutoMirrored.Filled.Description）。v1.0.29 修复 + 添加 ASR 设置 section（SettingsGenerationPage Section 8：引擎选择 Auto/System/Sherpa + 引擎状态 + 模型下载列表 + 下载进度）。设置四层加 `asr_engine_pref`（Schema/Manager/Repository/UI）。CI 全绿验证通过（CI #31757506824 ✓ / Build & Release #31757518101 ✓）。
- **v1.0.27 ASR 引擎抽象层**：新建 `speech/` 包 5 文件（SpeechEngine 接口 + SystemSpeechEngine + SherpaAsrEngine stub + SpeechRecognitionManager + AsrModelManager）。CI 全绿验证通过（CI #31750904823 ✓ / Build & Release #31750906971 ✓）。
- **v1.0.26 shell quoting 网关**：新建 `util/ShellQuote.kt`（75 行）——POSIX 单引号安全引用。`ShellMonitorTools.kt` tail_follow/kill_process 改用安全引用。CI 全绿验证通过（#31749416627 ✓ / #31749418527 ✓）。
- **v1.0.25 重复调用死循环检测**：新建 `viewmodel/ToolRepeatDetector.kt`（74 行）——同一签名连续重复 8 次注入警告打破循环。CI 全绿验证通过（#31748442635 ✓ / #31748445259 ✓）。
- **v1.0.24 结构化进程/系统监控工具**：新建 `ShellMonitorTools.kt`（276 行）——list_processes/kill_process/system_stats/tail_follow 4 个结构化工具。CI 全绿验证通过（CI #31747117562 ✓ / Build & Release #31747119949 ✓）。
- **v1.0.23 诊断日志写 Download 目录**：`CrashReporter.kt`（150→244 行）新增 `mirrorToDownloads()`（崩溃时镜像 JSON 到 MediaStore.Downloads/Agora）+ `exportDiagnostics()`（主动导出面包屑+TTS 日志到 Downloads）。`SettingsGenerationPage.kt` 加「保存到下载」按钮。CI 全绿验证通过（#31723724067 ✓）。
- **v1.0.22 StrictMode + NSC + AppExecutors + ErrorSanitizer**：借鉴 ZorvAI 全量分析高优先级 4 项优化。① StrictMode（`MainActivity.kt`，debug 检测主线程 IO/泄漏）；② 网络安全配置（`res/xml/network_security_config.xml`，仅 localhost/.local 放明文）；③ 分层线程池（`util/AppExecutors.kt`，IO+CPU 双池）；④ 错误脱敏（`util/ErrorSanitizer.kt`，stripHostAndIp）。CI 全绿验证通过（CI #31721923896 ✓ / Build & Release #31721946682 ✓）。
- **连续语音对话（P2，3.2）**：commit `437f831d` + review fixes `4e77a5a0`。新建 `SttManager.kt`（SpeechRecognizer 封装，retryable init + `initializing` flag 防重复创建）、`VoiceConversationController.kt`（状态机 IDLE→LISTENING→PROCESSING→SPEAKING→LISTENING，TTS grace window，sendJob/ttsObserverJob 跟踪取消）、`TtsPlaybackHelper.kt`（从 ChatViewModel 提取 playTtsForMessage，978→977 行）、`VoiceMicButton.kt`（脉冲动画 mic FAB，always-call rememberInfiniteTransition 防 Compose slot table 崩溃）。`ChatBottomBar` 加 mic 按钮，`ChatApp` 加 RECORD_AUDIO 权限 launcher + `isSupported` 检查 + 权限拒绝 Snackbar。`SettingsGenerationPage` 加 Voice Conversation 设置组。`MessageLongImageRenderer` 加 20000px 高度上限 + try-catch。CI 全绿验证通过（#31568711157 ✓）。
- **保存为长图（P1c）+ 多选模式隐藏底部栏（P1d）**：commit `5f603166`。新建 `MessageLongImageRenderer.kt`（text→Bitmap via StaticLayout→PNG→Intent.ACTION_SEND），`MessageExportController` 加 `shareMessagesAsLongImage`，`ShareSelectionFab` 加 Image 按钮，`ChatApp` 底部栏在多选模式隐藏。
- **v1.0.9 版本 bump + Build & Release workflow 改进**：versionCode 9→10 / versionName 1.0.8→1.0.9。build.yml 改进：`workflow_dispatch` 不再要求手动输入版本号，`get-version` 始终从 `build.gradle.kts` 读取 versionName 作为唯一事实源，消除"手动输入版本 ≠ 代码版本"的整类失败。
- **TTS/分享全量检查补缺**：commit `1aad5000`。TTS「正在朗读」文字指示 + TTS 引擎不可用 Snackbar 提示 + 长按消息进入多选模式。CI 全绿验证通过（#31554958375 ✓）。
- **分享导出功能（复制纯文本 + 高级过滤选项）**：commit `0c8c13b1`（P1a 复制纯文本）+ `f6fd830e`（P1b 思考/工具过滤）+ `ed3d5cab`（编译修复）。新建 `MessageExportController.kt`（44 行），`ConversationForkShareService` 加 `buildPlainText`/`formatPlainText`/`renderShare` 参数化，`ShareSelectionFab` 加 ContentCopy 按钮，`SettingsGenerationPage` 加 Export 设置组（两个 Switch），设置层 Schema/Manager/Repository 三层。CI 全绿验证通过（#31550658202 ✓）。
- **TTS 自动播放修复**：commit `b8dcb339`。根因：`onStreamCommit`（自动播放路径）缺少 init 重试和 grace window。提取 `playTtsForMessage` 共用方法，ChatViewModel 997→985 行。CI 全绿验证通过（#31550658202 ✓）。
- **v1.0.8 发版成功**：commit `a8924284`，versionCode 8→9 / versionName 1.0.7→1.0.8，修复 Build & Release CI 版本一致性校验。CI 全绿（Build & Release #31541408295 ✓ / CI #31539617357 ✓ / Fastlane #31539617199 ✓），Release `Agora-v1.0.8-android-arm64-v8a.apk` 已发布。
- **TTS 语音播报功能 CI 全绿 + 运行时调用失败修复**：commit `e7b8cb67` 的 CI 运行 #31489167800 成功（6m16s）**仅验证编译通过**，未覆盖运行时。2026-08-12 全量修复运行时调用失败（6 根因：init 不可重试 / playing 状态卡死 / listener 时机 / setLanguage+speak 返回值未检查 / pendingText 泄漏，详见 §9 当日条目），`TtsManager.kt` 109→162 行、`ChatViewModel.kt` 942→975 行。**CI 全绿验证通过（#31543876365 ✓）**。
- **v1.0.7 发版成功**：versionCode 7→8 / versionName 1.0.6→1.0.7，修复 Build & Release CI 版本一致性校验。CI 全绿（Build & Release #31476458515 ✓ / CI #31476408803 ✓ / Fastlane #31476408813 ✓），Release `Agora-v1.0.7-android-arm64-v8a.apk` 已发布。
- **MarkdownDimens 崩溃修复 CI 验证通过**：commit `4ac51844` 重新应用的修复已通过后续所有 CI 编译验证（v1.0.6/v1.0.7 发版 CI 全绿）。`ChatMarkdownCodeBlock`（MessageBubbleAssets.kt）用 `CompositionLocalProvider` 自给自足提供 `LocalMarkdownDimens`，Dialog 上下文崩溃已消除。
- **ChatApp.kt 拆分完成（938→757 行）**：三步提取 ① 底部栏区 → `ChatAppBottomBarSection.kt`（241行）；② 欢迎页 → `ChatAppWelcomeContent` in `ChatAppOverlays.kt`（192行）；③ showButton 计算 → `rememberChatAppScrollToBottomButtonVisible` in `ChatAppInteractionEffects.kt`（457行）。清理 13 个未使用 import。CI 全绿（commit `bcb73f5d` ✓）。
- **包名重命名 com.newoether.agora → com.lxseek.chat**：627 文件变更（578+ Kotlin 文件 package/import、198 测试文件、JNI 函数名 `Java_com_lxseek_chat_*`、proguard 规则、build.gradle.kts namespace/applicationId、Appfile、build-logic、Go 模块路径 `github.com/ojbkxc/conch`、README/docs URL）。目录重命名 `com/newoether/agora/` → `com/lxseek/chat/`（main + test + testFdroid）。开发者名字 "Newo Ether" → "ojbkxc"（strings.xml en/zh、docs、LICENSE）。build.yml 添加版本一致性校验（git tag 版本必须与 build.gradle.kts versionName 一致）+ 去除 workflow_dispatch 硬编码默认值。CI 全绿（CI ✓ / Fastlane ✓）。
- **fastlane 自动化**：创建 `fastlane/Fastfile`（lane: build_fdroid/build_play/github_release/validate_metadata/generate_changelog/release）、`Appfile`（package_name）、`Gemfile`（fastlane 依赖）；新增 `.github/workflows/fastlane.yml`（PR/push 触发元数据验证）；添加 versionCode 6 changelog（en-US + zh-CN）。CI 全绿（Fastlane ✓ / CI ✓）。
- **v1.0.5 发版成功**：修复 `DebugLog.i()` 编译错误（DebugLog 只有 d/e/w，无 i 方法）+ `showSnackbar()` 未包在协程中的编译错误。bump versionCode 5→6 / versionName 1.0.4→1.0.5。删除有问题的 v1.0.4 tag（指向含编译错误的 commit）和误标的 v1.0.3 tag。CI 全绿（Build & Release ✓ / CI ✓），Release `Agora-v1.0.5-android-arm64-v8a.apk` 已发布。
- **v1.0.4 发版成功**：修复崩溃日志上传/评分提交/更新检查指向原仓库 `newo-ether/Agora` 的问题，全部改为指向本仓库 `ojbkxc/Agora`。CI 全绿，Release 已发布。
- **v1.0.1 发版成功**：CI 全绿（get-version ✓ / build-android ✓ / release ✓），产物 `Agora-v1.0.1-android-arm64-v8a.apk` (27.56 MB) 已发布到 GitHub Release。
- **CI 权限修复**：build.yml 添加 `permissions: contents: write`，解决 `gh release create` 的 403 错误。
- **资源重复键修复**：删除多余的 `values-zh/automation_strings.xml`（88 个键已在 `strings.xml` 中），消除 Android 资源编译 duplicate resource 错误。
- **语言精简为 en + zh**：删除 `values-zh-rTW/` 及 ar/de/es/fr/ja/ko/pt-rBR/ru/vi 共 10 个语言资源目录；精简 `SettingsLanguagePage.kt`（13→3 选项）、`MainActivity.attachBaseContext()`（Locale 映射）、`DefaultSystemPrompt.titleForLocale()`、`DocumentationFab.kt`；精简 `mkdocs.yml`（11→2 语言）+ 删除 `docs/zh-Hant/` 等 9 个文档语言目录。
- **删除全部自定义字体**：删除 `res/font/` 下 9 个 TTF 文件（~23.1MB）；`Type.kt` 改为 `MonoFamily = FontFamily.Monospace` / `OutfitFamily = FontFamily.Default`；`Theme.kt` / `MainActivity.kt` / `SettingsSandboxPage.kt` 字体引用改用系统字体；清理无效 import。
- **编译流水线改造**：重写 `.github/workflows/build.yml`，参照 RustSync 模式：push tag `v*` 触发 → `get-version` → `build-android`（PRoot + APK）→ `release`（GitHub Release），产物命名 `Agora-v{VERSION}-android-arm64-v8a.apk`。
- **AGENTS.md 创建**：本文件。

### 🟡 已知问题
- **PRoot 二进制需 CI 构建**：`build-proot.sh` 产物（`libproot_*.so`, `libtalloc.so`）被 `.gitignore` 忽略，CI 中由 `./build-proot.sh force` 现场构建。
- **签名密钥**：Release 签名需在 GitHub Secrets 配置 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`；未配置时回退 debug 签名。

### ❌ 未完成
1. 滑动连续多选：未实现（已支持长按进入多选）。

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

### P0 — CI 编译验证与首次发版 ✅
- [x] 配置 GitHub Secrets（`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`）用于 Release 签名（可选，未配置则用 debug 签名）。
- [x] `git tag v1.0.1 && git push origin v1.0.1` 触发流水线。
- [x] 据 CI 报错迭代修复至全绿（§R2.3）：修复 1) values-zh 资源重复键 2) release job 403 权限。
- [x] 确认 GitHub Release 产物 `Agora-v1.0.1-android-arm64-v8a.apk` (27.56 MB) 正确。

### P1+ — 后续迭代（按需推进）
- [x] ChatApp.kt 拆分：底部栏 → ChatAppBottomBarSection.kt、欢迎页 → ChatAppOverlays.kt、showButton → ChatAppInteractionEffects.kt（938→757 行）。
- [x] TTS 语音播报功能：TtsManager + Settings + ViewModel + UI 喇叭按钮 + 设置页。CI 全绿验证通过（#31489167800 ✓，**仅编译验证**）。**2026-08-12 运行时调用失败全量修复**（6 根因，详见 §9 当日条目），CI 全绿验证通过（#31543876365 ✓）。**2026-08-12 自动播放修复**（`onStreamCommit` 缺 init 重试，提取 `playTtsForMessage` 共用方法），CI 全绿验证通过（#31550658202 ✓）。
- [x] MarkdownDimens 崩溃修复：CompositionLocalProvider 自给自足，CI 全绿验证通过。
- [x] v1.0.6 / v1.0.7 / v1.0.8 / v1.0.9 发版：CI 全绿，Release 已发布。
- [x] 分享导出 — 复制纯文本（P1a）+ 高级过滤选项（P1b，思考/工具开关）：CI 全绿验证通过（#31550658202 ✓）。
- [x] TTS「正在朗读」文字指示 + 引擎不可用 Snackbar 提示 + 长按消息进入多选模式：CI 全绿验证通过（#31554958375 ✓）。
- [x] Build & Release workflow 改进：版本号从 build.gradle.kts 自动读取，消除手动输入版本不一致问题。
- [x] 分享导出 — 保存为长图（P1c）：`MessageLongImageRenderer` text→Bitmap→PNG，CI 全绿（#31567125000 ✓）。
- [x] 分享导出 — 底部操作面板 UI 改造（P1d）：多选模式隐藏底部栏，ShareSelectionFab 含复制/长图/全选/确认。
- [x] 连续语音对话（P2，3.2）：SttManager + VoiceConversationController 状态机 + VoiceMicButton + RECORD_AUDIO 权限 + 设置。CI 全绿验证通过（#31568711157 ✓）。
- [x] v1.0.22 StrictMode + NSC + AppExecutors + ErrorSanitizer（借鉴 ZorvAI 优化 1/2/4/5）。CI 全绿验证通过（#31721923896 ✓ / #31721946682 ✓）。
- [x] v1.0.23 诊断日志写 Download 目录（借鉴 ZorvAI 优化 3）。CI 全绿验证通过（#31723724067 ✓ / #31723718876 ✓）。
- [x] v1.0.24 结构化进程/系统监控工具（Agent 深化 P0：list_processes/kill_process/system_stats/tail_follow）。CI 全绿验证通过（#31747117562 ✓ / #31747119949 ✓）。
- [x] v1.0.25 重复调用死循环检测（Agent 深化 P1：ToolRepeatDetector）。CI 全绿验证通过（#31748442635 ✓ / #31748445259 ✓）。
- [x] v1.0.26 shell quoting 网关（Agent 深化 P1：ShellQuote）。CI 全绿验证通过（#31749416627 ✓ / #31749418527 ✓）。
- [ ] Agent 能力深化（P2）：批量多服务器执行 + 工具分档下发 + 行动轨迹总线。
- [x] ASR 集成（P1）：sherpa-onnx 端侧 ASR（OnlineRecognizer 流式 + OfflineRecognizer 批处理 + Silero VAD）+ 系统 SpeechRecognizer 在线回退 + 模型下载管理 + 设置 UI（v1.0.27 抽象层 + v1.0.29 设置 UI + v1.0.39 sherpa-onnx 原生库 + 模型下载 UI 接线）。**v1.0.39 CI 全绿验证通过（#31877800161 ✓）**：sherpa-onnx .so 由 CI 下载（download-sherpa.sh）。
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

- 2026-08-15 v1.0.39 发版成功（本次会话）：bump versionCode 39→40 / versionName 1.0.38→1.0.39，打 tag `v1.0.39` push 触发 Build & Release。CI #31883321252（master push）全绿，Build & Release #31883331881（tag push）全绿（get-version ✓ / build-android ✓ / release ✓），Release `Agora-v1.0.39-android-arm64-v8a.apk` (21.64 MB) 已发布到 GitHub Release。本次发版内容 = v1.0.39 sherpa-onnx 端侧 ASR/VAD/TTS 集成 + VoiceRecorder.kt 编译修复。

- 2026-08-15 v1.0.39 CI 编译修复（本次会话）：首次 push v1.0.39 后 CI #31877316359 失败，`compileFdroidDebugKotlin` 报 `VoiceRecorder.kt:216` 类型错误——`File.createTempFile(...)` 已返回 `File`，却被外层 `File(...)` 构造器包裹（`File` 构造器只接受 `String`/`URI`），且 `mainHandler.looper.let { _ -> ... }` 是多余的无用 let。修复为 `val file = File.createTempFile("voice_record", ".wav")`。commit `235cb750`，CI #31877800161 全绿验证通过（build 6m7s ✓，含 Build F-Droid Debug APK ✓）。

- 2026-08-15 v1.0.39 sherpa-onnx 端侧 ASR/VAD/TTS 集成（CI 全绿验证通过 #31877800161）：用户要求"全部功能都过一遍"并选择"只做 P0 语音三项"。参照 Half_duplex_speech / sherpa-onnx/android/SherpaOnnxVadAsr / SherpaOnnxTtsEngine / VoxSherpa-TTS / maise。**P0-1 端侧 ASR**：① 复制 sherpa-onnx Kotlin API 源码 9 文件 874 行到 `com/k2fsa/sherpa/onnx/`（全部 ≤ 999 行，裁掉 getModelConfig 辅助函数）；② 新建 `download-sherpa.sh`（CI 从 GitHub Releases v1.13.5 下载 libonnxruntime.so + libsherpa-onnx-jni.so）；③ build.yml + ci.yml 加 download-sherpa.sh 步骤；④ 重写 `SherpaAsrEngine.kt`（92→216 行）：stub → 真实 AudioRecord + OnlineRecognizer 流式识别。**P0-3 Silero VAD**：重写 `VoiceRecorder.kt`（159→307 行）：MediaRecorder+幅度VAD → AudioRecord+SileroVad，输出 WAV。**P0-2 端侧 TTS**：新建 `SherpaTtsEngine.kt`（192 行）：sherpa-onnx OfflineTts + AudioTrack 播放；`TtsManager.kt` 优先用 sherpa。**模型下载器 + 设置 UI**：新建 `SherpaModelManager.kt`（163 行）从 GitHub Releases + HuggingFace 下载 VAD/ASR/TTS 模型 + 进度 StateFlow；新建 `SettingsSherpaModelsSection.kt`（160 行）模型状态 + 下载按钮 + 进度 Composable；`SettingsGenerationPage.kt`（917→923 行）ASR section 接线 `SettingsSherpaModelsSection`；strings.xml en+zh 各加 12 个 sherpa 字符串。**本地 commit 7d95f152 + 6fb2684f + 1f1f0554，待用户 push 验证 CI**（本地无法访问 GitHub HTTPS）。

- 2026-08-14 v1.0.38 Apple 风格 UI 细化（本次会话）：用户要求"整体的UI不够细腻…要有那种苹果的简洁实用的美"。全面分析 58 处阴影/elevation、10 种圆角值、间距体系后实施以下改动：① **降低阴影/elevation**：底部输入栏 shadowElevation 8→0 + tonalElevation 2→1；顶栏胶囊 shadowElevation 4→0 + tonalElevation 4→2；语音覆盖 tonalElevation 6→3 + shadowElevation 8→2；抽屉搜索栏 tonalElevation 8→2 + CircleShape→RoundedCornerShape(12)；所有下拉菜单 tonalElevation 16→6（20 处）；ShareSelectionFab shadowElevation 6→2 + RoundedCornerShape(50)→16；CircularBackButton tonalElevation 6→2。② **助手消息加浅灰气泡**：`Color.Transparent` → `surfaceVariant.copy(alpha=0.6f)`，对应 iMessage 灰气泡。③ **统一圆角**：气泡 20→18dp；错误气泡 12→16dp；底部栏 28→24dp；composer 遮罩顶角 20→24dp；顶栏胶囊 RoundedCornerShape(50)→16；抽屉 24→20dp；设置卡片 24→20dp + 移除 5dp 接缝改用 0.5dp HorizontalDivider。④ **VoiceMicButton 统一**：尺寸 46→48dp；active shadowElevation 8→4dp；脉冲振幅 1.1→1.05。⑤ **间距优化**：消息行垂直 8→6dp；顶栏高度 180→140dp；设置组标题颜色 primary→onSurfaceVariant + padding 12→8dp。bump versionCode 38→39 / versionName 1.0.37→1.0.38。CI 全绿验证通过（CI #31791904839 ✓ / Build & Release #31791927093 ✓），Release `Agora-v1.0.38-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.37 修复 partialTranscript bug + 远程 ASR 设置 UI + 死代码清理（本次会话）：全面检查发现并修复多个问题。① **Bug: `_partialTranscript` 永远不更新**（`VoiceConversationController.kt`）：`_partialTranscript` 仅在 `stop()`/`beginListening()` 中被设为 `""`，从未收集 `SttManager.partialText`，导致部分识别文本 UI 永远不显示。修复：添加 `partialJob` 在 `beginSystemListening()` 中收集 `SttManager.partialText` → `_partialTranscript`，`stop()` 中取消。② **Bug: 远程 ASR 设置 UI 缺失**（`SettingsGenerationPage.kt`）：`asrRemoteBaseUrl`/`asrRemoteApiKey`/`asrRemoteModel` 三个设置值已收集但从未在 UI 中显示。修复：当 `asrUseRemote=true` 时显示三个 `OutlinedTextField`（Base URL/API Key/Model）。③ **死代码清理**：删除 `AppExecutors.kt`（0 引用）、`ErrorSanitizer.kt`（0 引用）、`FontUtils.kt`（0 引用，自定义字体已删除）；删除 `VoiceConversationController.isListening` 死字段 + `ttsPlayingMessageId` 死参数（ChatViewModel 调用处同步更新）；`DataImporter.kt`/`SettingsAppearancePage.kt` 中 `readFontName()` 替换为 `file.nameWithoutExtension`。④ **孤立字符串清理**：删除 94 个孤立字符串（en+zh 各 94 个），但首次 CI 失败因误删 `sandbox_snackbar_*`（在 `app/src/fdroid/` 中使用，脚本只搜索了 `app/src/main/`）→ 恢复 10 个 `sandbox_snackbar_*` 字符串。bump versionCode 37→38 / versionName 1.0.36→1.0.37。CI 全绿验证通过（CI #31788398268 ✓ / Build & Release #31788418064 ✓），Release `Agora-v1.0.37-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.36 删除死代码 AsrModelManager + 11 个孤立 ASR 字符串（本次会话）：v1.0.34 删除假 ASR 模型下载 UI 后遗留的死代码清理。① **删除 `speech/AsrModelManager.kt`**（140 行）— 0 引用，完全死代码。② **删除 11 个孤立字符串**（en + zh 各 11 个）：`asr_engine_status`/`asr_model_download`/`asr_model_downloaded`/`asr_model_downloading`/`asr_model_delete`/`asr_model_active`/`asr_model_activate`/`asr_model_deactivate`/`asr_import_model`/`asr_import_model_desc`/`asr_native_not_loaded`，全部 0 引用。bump versionCode 36→37 / versionName 1.0.35→1.0.36。CI 全绿验证通过（CI #31780615261 ✓ / Build & Release #31780642151 ✓），Release `Agora-v1.0.36-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.35 接线 VoiceGradientBackground 到状态覆盖层（本次会话）：发现 `VoiceGradientBackground.kt`（v1.0.34 创建）从未被调用（死代码）。修改 `VoiceConversationStatusOverlay.kt`（139→142 行）：在 `Surface` 内用 `Box` 包裹，先放 `VoiceGradientBackground(matchParentSize())` 作为动态渐变背景，再放 `Column` 内容；Surface alpha 0.95→0.92 让渐变背景透出；删除未使用的 `background` import。bump versionCode 35→36 / versionName 1.0.34→1.0.35。CI 全绿验证通过（CI #31779195803 ✓ / Build & Release #31779205895 ✓），Release `Agora-v1.0.35-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.34 UI 细化 + 删除假 ASR 模型下载 UI（本次会话）：用户要求"把建议的都借鉴了，ui细化，业务细化，到成功编译"。① **三球波形动画**（新建 `VoiceWaveformIndicator.kt` 92 行，借鉴 VoiceRobot WaveformView）：三球相位差 0/0.9/1.8，idle = 0.16 + 0.06*sin(t)，`wave01` 正弦波 + `bezierArcY` 二次贝塞尔弧，振幅平滑 150ms `Animatable.animateTo`，`LocalDensity` 像素转换，Canvas `drawCircle` 三球。② **渐变背景动画**（新建 `VoiceGradientBackground.kt` 49 行，借鉴 VoiceRobot GradientBackgroundView）：`rememberInfiniteTransition` 驱动色相旋转，`Brush.linearGradient` 双色渐变。③ **VoiceMicButton 重写**（112 行）：用三球波形替换 halo 动画，listening 时显示 `VoiceWaveformIndicator`。④ **VoiceConversationStatusOverlay**（139 行）：添加波形 + amplitude 参数传递。⑤ **VAD 参数改进**（`VoiceRecorder.kt`，借鉴 Half_duplex Silero VAD）：65→60dB 阈值、2000→1500ms 静音停止、60→30s 最大录音、100→80ms 采样间隔。⑥ **删除假 ASR 模型下载 UI**（`SettingsGenerationPage.kt` 885 行）：用户指出 ASR 模型下载"根本没实现"（`SherpaAsrEngine.startListening()` 直接返回 error，`AsrModelManager.downloadModel()` 下载但不解压），删除假的模型下载列表 UI + unused vars + import。⑦ **amplitude 接线**：`VoiceConversationController` 转发 amplitude → `ChatApp` collectAsState → `ChatAppBottomBarSection` → `ChatBottomBar` → `VoiceMicButton`。bump versionCode 34→35 / versionName 1.0.33→1.0.34。**首次 CI 失败**（`VoiceWaveformIndicator.kt` garbled：`remember<parameter name=...>` + `drawCircle(color=color, radius(0f, 1f)` 截断 + 缺 `wave01` 函数）→ 重写整个文件，移动 tag 重触发。CI 全绿验证通过（CI #31774607828 ✓ / Build & Release #31774617629 ✓），Release `Agora-v1.0.34-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.33 可靠语音对话 — MediaRecorder + VAD + Whisper API（本次会话）：用户反馈"按了功能都不能正常实现，要跟 ChatGPT 那种效果一样"。根因：原实现仅依赖系统 `SpeechRecognizer`，在国产 ROM（MIUI/EMUI/ColorOS）上不可靠或完全不可用。**新建 `util/VoiceRecorder.kt`**（159 行）：`MediaRecorder`（AAC/M4A, 16kHz, mono）+ 基于幅度的 VAD（65dB 阈值 + LPF 平滑 + 说话后 2s 静音自动停止 + 60s 最大录音 + 300ms 最小语音时长）。**新建 `speech/RemoteTranscriber.kt`**（53 行）：`MultipartBody` 上传音频文件到 OpenAI 兼容 `/v1/audio/transcriptions`，复用 `HttpClient.client`（OkHttp），`kotlinx.serialization` 解析 `{"text":"..."}`。**重写 `VoiceConversationController.kt`**（143→225 行）：混合方案——远程 Whisper API 为主（`VoiceRecorder` 录音 → VAD 自动停止 → `RemoteTranscriber.transcribe()` → `sendMessage()`），系统 `SpeechRecognizer` 为回退；新增 `State.TRANSCRIBING` 状态；错误重试（最多 3 次）而非首次错误即终止对话；`ChatViewModel` 传入 `useRemoteAsr`/`remoteAsrBaseUrl`/`remoteAsrApiKey`/`remoteAsrModel` 参数，**自动从当前 provider 解析 API key/base URL**（若未单独配置则用当前选中模型的 provider 密钥和地址）。**设置四层**加 `asr_use_remote`/`asr_remote_base_url`/`asr_remote_api_key`/`asr_remote_model`（Schema + Manager + Repository + UI Switch）。`VoiceConversationStatusOverlay` + `VoiceMicButton` 加 `TRANSCRIBING` 状态支持。strings en+zh 各加 7 个字符串。bump versionCode 33→34 / versionName 1.0.32→1.0.33。CI 全绿验证通过（CI #31767753793 ✓ / Build & Release #31767766199 ✓），Release `Agora-v1.0.33-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.32 删除重复麦克风按钮（本次会话）：用户反馈"对话框有两个麦克风按钮"。v1.0.30 创建 `VoiceMicButton`（连续语音对话 FAB）+ v1.0.31 创建 `VoiceInputButton`（单次语音输入 IconButton），两者同时显示在 `ChatBottomBar`。用户选择只保留连续语音对话。删除 `VoiceInputButton.kt`/`VoiceInputOverlay.kt`/`VoiceInputController.kt` 3 文件，清理 `ChatApp.kt`/`ChatAppBottomBarSection.kt`/`ChatBottomBar.kt`/`ChatViewModel.kt` 中所有 `voiceInput` 引用 + strings en+zh 3 个 `voice_input_*` 字符串。bump versionCode 32→33 / versionName 1.0.31→1.0.32。

- 2026-08-14 v1.0.30 语音对话 UI + 存相册（本次会话）：① **VoiceMicButton 增强**（90→132 行）：listening 时红色 halo ring 扩散动画（Canvas + graphicsLayer alpha + scale 无限循环）+ 活跃时 elevation 8dp 增强可见性 + 56dp Box 包裹给 halo 留空间。② **VoiceConversationStatusOverlay**（新建 128 行）：语音对话状态覆盖层，`ChatApp` 中 align BottomCenter + padding(bottom = bottomBarHeight + 8dp) 定位在底部栏上方——状态图标（GraphicEq/Lightbulb/VolumeUp，按 state 着色 + 脉冲 alpha 动画）+ 状态文字（聆听中…/思考中…/朗读中…，labelLarge + FontWeight.Medium）+ 部分识别文本（listening 时显示 `"\u201C${partialTranscript}\u201D"`，bodyMedium + maxLines 2 + Ellipsis）。`ChatApp` 加 `voiceConversationPartial` collectAsState。③ **保存到相册**：`ShareSelectionFab` 加 SaveAlt 图标按钮，`ChatAppShareSelectionOverlay` 加 `onSaveToGallery` 参数，`ChatApp` 接线 `viewModel.saveLongImageToGallery()`。`MessageExportController.saveLongImageToGallery()`（77→155 行）：`MessageLongImageRenderer.renderToBitmap()` 生成 Bitmap → API 29+ 用 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` + `RELATIVE_PATH = Pictures/Agora` + `IS_PENDING` 协议存入相册 → API 24-28 用 `Environment.getExternalStoragePublicDirectory(PICTURES)/Agora/` + `ACTION_MEDIA_SCANNER_SCAN_FILE` 广播。`MessageLongImageRenderer` 加 `renderToBitmap()` 公开方法（88→93 行）。`ChatViewModel` 加 `saveLongImageToGallery()` 委托（989 行）。strings en+zh 各加 `share_save_to_gallery`/`share_saved_to_gallery`。bump versionCode 30→31 / versionName 1.0.29→1.0.30。CI 全绿验证通过（CI #31759321853 ✓ / Build & Release #31759324326 ✓），Release `Agora-v1.0.30-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.29 ASR 设置 UI + v1.0.28 编译修复（本次会话）：① **v1.0.28 编译修复**：v1.0.28 分享选择 UI 有 3 个编译错误——ChatApp.kt:691 多余 `) {` 致 syntax error、ChatTopBar.kt 缺 `TextButton` import、ShareSelectionFab.kt 误用 `Icons.AutoMirrored.Filled.Description`（应为 `Icons.Default.Description`）。② **ASR 设置 UI**：SettingsGenerationPage.kt（813→919 行）新增 Section 8 ASR Settings——引擎偏好选择（Auto/System/Sherpa 循环切换）+ 引擎状态显示（无可用引擎时警告）+ sherpa-onnx 模型下载列表（4 个模型：Zipformer bilingual/Paraformer zh/Whisper tiny/SenseVoice multi）+ 下载进度显示 + 已下载模型删除。设置四层加 `asr_engine_pref`（SettingsPreferenceSchema + SettingsManager + SettingsRepository + UI）。strings.xml en+zh 各加 12 个 ASR 字符串。bump versionCode 29→30 / versionName 1.0.28→1.0.29。CI 全绿验证通过（CI #31757506824 ✓ / Build & Release #31757518101 ✓），Release `Agora-v1.0.29-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.28 分享选择 UI 改进（本次会话，CI 失败→v1.0.29 修复）：多选模式顶栏（ShareSelectionTopBar：返回箭头 + 已选 N + 全选/取消全选）、分享按钮改为进入多选模式并预选消息、长按进入多选时停止 TTS、专用导出 Markdown 按钮、activateShareSelection() 支持 initialMessageId 预选。v1.0.28 CI 失败（3 个编译错误），由 v1.0.29 修复。

- 2026-08-14 v1.0.27 ASR 引擎抽象层 + 模型管理器（本次会话）：ASR 集成 P1 基础设施。新建 `speech/` 包含 5 个文件：① `SpeechEngine.kt`（71 行）——ASR 引擎接口；② `SystemSpeechEngine.kt`（37 行）——适配现有 SttManager；③ `SherpaAsrEngine.kt`（92 行）——sherpa-onnx stub，优雅降级；④ `SpeechRecognitionManager.kt`（108 行）——编排引擎选择；⑤ `AsrModelManager.kt`（140 行）——模型下载管理。代码无需原生库即可编译。CI 全绿验证通过（CI #31750904823 ✓ / Build & Release #31750906971 ✓），Release `Agora-v1.0.27-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.26 shell quoting 网关 — 防注入（本次会话）：Agent 能力深化 P1。新建 `util/ShellQuote.kt`（75 行）——POSIX 单引号安全引用（`quote()` 包单引号 + 转义内嵌引号、`buildCommand()` 拼接命令、`sanitize()` 移除 null/控制字符）。`ShellMonitorTools.kt`：`tail_follow` 路径改用 `ShellQuote.quote()` 替代不安全双引号；`kill_process` 验证 pid ≥ 1 防 kill 0/负数。bump versionCode 26→27 / versionName 1.0.25→1.0.26。CI 全绿验证通过（CI #31749416627 ✓ / Build & Release #31749418527 ✓），Release `Agora-v1.0.26-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.25 重复调用死循环检测（本次会话）：Agent 能力深化 P1。新建 `viewmodel/ToolRepeatDetector.kt`（74 行）——跟踪工具调用签名（toolName + arguments hashCode），同一签名连续重复 8 次时注入警告消息打破循环，防止模型卡在反复调用同一失败 SSH 命令。`GenerationManager.kt`（773→780 行）工具循环顶部集成 `repeatDetector.observe()`，检测到重复时设置 totalText 为警告并 break。bump versionCode 25→26 / versionName 1.0.24→1.0.25。CI 全绿验证通过（CI #31748442635 ✓ / Build & Release #31748445259 ✓），Release `Agora-v1.0.25-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.24 结构化进程/系统监控工具（本次会话）：Agent 能力深化 P0。新建 `tool/ShellMonitorTools.kt`（276 行）——封装 4 个结构化监控工具，通过 shell 命令 + JSON 解析返回可靠结构化数据：① `list_processes`（ReadOnly，ps aux → JSON 数组 pid/user/cpu/mem/command，可排序/限量）；② `kill_process`（HighRisk，kill -SIGNAL PID，内部确认门控）；③ `system_stats`（ReadOnly，loadavg + free + df + uptime → JSON）；④ `tail_follow`（ReadOnly，tail -n N → JSON，持续跟随用 background job）。`ShellToolDefinitions.kt`（225→271 行）加 4 个工具定义。`ShellToolProvider.kt`（661→729 行）接线 execute/handles/riskLevel/requiresApproval + 4 个执行方法。`ToolApproval.kt` 加 `kill_process` 到 `TOOLS_WITH_INTERNAL_CONFIRM` 避免双重确认。bump versionCode 24→25 / versionName 1.0.23→1.0.24。CI 全绿验证通过（CI #31747117562 ✓ / Build & Release #31747119949 ✓），Release `Agora-v1.0.24-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.23 诊断日志写 Download 目录（本次会话）：借鉴 ZorvAI 全量分析的优化 3。`CrashReporter.kt`（150→244 行）新增 `mirrorToDownloads()`——崩溃时镜像 JSON 到 `MediaStore.Downloads/Agora/`（API 29+ scoped storage 无需权限，API 24-28 用已有 `WRITE_EXTERNAL_STORAGE` maxSdk 28）；新增 `exportDiagnostics()`——主动导出面包屑 + TTS 日志到 Downloads，供非崩溃问题（如 TTS 不工作）诊断。`SettingsGenerationPage.kt`（804→813 行）TTS 诊断行新增「保存到下载」按钮，调用 `exportDiagnostics` + Toast 反馈。strings.xml en+zh 各加 `tts_save_to_downloads`。bump versionCode 23→24 / versionName 1.0.22→1.0.23。CI 全绿验证通过（CI #31723724067 ✓ / Build & Release #31723718876 ✓），Release `Agora-v1.0.23-android-arm64-v8a.apk` 已发布。

- 2026-08-14 v1.0.22 StrictMode + NSC + AppExecutors + ErrorSanitizer（本次会话）：借鉴 ZorvAI 全量分析的高优先级 5 项优化中的 4 项。① **StrictMode**（`MainActivity.kt`）：`BuildConfig.DEBUG` 条件下启用 `ThreadPolicy`（detectAll + penaltyLog）+ `VmPolicy`（detectLeakedSqlLiteObjects + detectLeakedClosableObjects + penaltyLog），检测主线程 IO + 资源泄漏。② **网络安全配置 NSC**（新建 `res/xml/network_security_config.xml`）：仅 localhost/.local/intranet 域允许明文，其余强制 HTTPS。`AndroidManifest.xml` 加 `networkSecurityConfig` 引用。③ **分层线程池**（新建 `util/AppExecutors.kt`）：IO 池（`max(2, cores*2)` 线程）+ CPU 池（`cores` 线程），替代共用 IO 池导致的 CPU 密集任务阻塞 IO。④ **错误脱敏**（新建 `util/ErrorSanitizer.kt`）：`stripHostAndIp()` 正则移除 IP/hostname，防错误气泡泄露内网地址。bump versionCode 22→23 / versionName 1.0.21→1.0.22。CI 全绿验证通过（CI #31721923896 ✓ / Build & Release #31721946682 ✓），Release `Agora-v1.0.22-android-arm64-v8a.apk` 已发布。

- 2026-08-14 ZorvAI 全量分析 + Agent 能力深化方案 + ASR 集成方案（本次会话）：① **ZorvAI 全量分析**发现 30 个可借鉴优化点，高优先级 5 项已全部实现（v1.0.22 + v1.0.23）。② **Agent 能力深化方案**（分析 ZorvAI 工具注册/ReAct/特权仲裁/进程管理/系统监控）：Agora SSH 基座已比 ZorvAI 专业，深化方向为 P0 结构化进程/监控工具（list_processes/kill_process/system_stats/tail_follow）+ P1 重复调用死循环检测 + 三态策略+审计 + shell quoting 网关 + P2 批量多服务器执行 + 工具分档下发 + 行动轨迹总线 + P3 持久 SSH 会话池 + 跨任务经验闭环 + 技能即工具。③ **ASR 集成方案**（分析 openclaw-assistant + sherpa-onnx）：推荐 sherpa-onnx 作为端侧 ASR 引擎（纯离线/流式 OnlineRecognizer + 批处理 OfflineRecognizer + VAD Silero/Ten），2Pass 模式兼顾实时性与精度，中英双语 Zipformer + Paraformer 模型从 GitHub Releases 下载，系统 SpeechRecognizer 作为在线回退。方案详见会话记录。

- 2026-08-13 v1.0.21 TTS 看门狗 — 30s 超时强制释放 isPlaying（本次会话）：借鉴 ZorvAI `QuroTtsHolder.kt` 的看门狗机制。`TtsManager.kt`（297→313 行）新增 `watchdogScope`（`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`）+ `watchdogJob: Job?` + `WATCHDOG_TIMEOUT_MS = 30_000L`。`speakInternal` 成功时启动看门狗协程 `launch { delay(30s); if (_isPlaying.value) { log; _isPlaying.value = false } }`，`onDone`/`onError`/`stop`/`shutdown` 时 `cancel()` 取消。**性能影响：零**——`delay()` 是协程挂起函数，不阻塞线程，挂起期间零 CPU 开销，取消即时。首次 CI 失败（缺 `import kotlinx.coroutines.launch`）→ 补 import，移动 tag 重触发。CI 全绿验证通过（CI #31718557855 ✓ / Build & Release #31718583166 ✓），Release `Agora-v1.0.21-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.20 TTS 根因修复完成 + ZorvAI 方案对比分析（本次会话）：**; **根因**：Android 11+（targetSdk 36）包可见性限制下，MIUI 限制 `bindService` 到系统 TTS 引擎（`com.xiaomi.mibrain.speech`），导致 `TextToSpeech` 构造器在 3-7ms 内返回 ERROR（`bindService` 返回 false）。**核心修复**：`AndroidManifest.xml` 添加 `QUERY_ALL_PACKAGES` 权限（normal 权限，无需用户授予，完全绕过包可见性限制）。用户确认 v1.0.20 小米 TTS 已能正常工作。**与 ZorvAI（github.com/Quor-a/ZorvAI）方案对比**：两者核心修复一致（`QUERY_ALL_PACKAGES`），差异在可靠性 vs 诊断能力——ZorvAI（`QuroTtsHolder.kt` 608 行）有串行队列+文本分块(30-160 字)+看门狗(30s)+云 TTS 回退，可靠性更强；Agora（`TtsManager.kt` 297 行）有内存日志缓冲+6 个 StateFlow 实时状态+导出/复制+PM 查询+引擎状态检查+手动 bindService 测试，诊断能力更强且代码更简洁。**Agora TTS 完整方案记录**：① `QUERY_ALL_PACKAGES` 权限（v1.0.20，根因修复）+ `<queries>` intent+package 声明（v1.0.14/v1.0.19，双保险）；② 2 参数构造器优先→PM 查询→系统默认→硬编码回退（v1.0.18，多引擎逐个尝试+300ms 延迟防实例污染）；③ `initGeneration` 防 stale 回调（v1.0.12）+ 主线程 speak（v1.0.15）+ `Log` 替代 DebugLog（v1.0.14）+ ProGuard keep 规则（v1.0.14）；④ 诊断工具：内存日志导出/复制（v1.0.16）+ 测试按钮+系统设置入口+安装 Google TTS 按钮（v1.0.13/v1.0.18）+ 6 个 StateFlow 实时状态（v1.0.14）+ 引擎安装/启用状态检查+手动 bindService 测试（v1.0.19）。CI 全绿验证通过（CI #31711914378 ✓ / Build & Release #31712047351 ✓），Release `Agora-v1.0.20-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.20 TTS 根因修复 — QUERY_ALL_PACKAGES 权限（本次会话）：用户要求「一定要小米的 TTS 能用」并提供参考项目 ZorvAI（github.com/Quor-a/ZorvAI）。拉取 ZorvAI 源码分析发现其 `QuroTtsHolder.kt` 注释记录了同样的 `OnInit status=-1` 问题，解决方案是在 `AndroidManifest.xml` 中添加 `QUERY_ALL_PACKAGES` 权限。**根因确认**：Android 11+（targetSdk 36）包可见性限制下，即使有 `<queries>` 声明，MIUI 仍限制 `bindService` 到系统 TTS 引擎（`com.xiaomi.mibrain.speech`），导致 `TextToSpeech` 构造器在 3-7ms 内返回 ERROR。`QUERY_ALL_PACKAGES` 是 normal 权限（无需用户授予），完全绕过包可见性限制，让 `bindService` 能绑定到任何 TTS 引擎。修复 `AndroidManifest.xml`：添加 `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" />`。bump versionCode 20→21 / versionName 1.0.19→1.0.20。CI 全绿验证通过（CI #31711914378 ✓ / Build & Release #31712047351 ✓），Release `Agora-v1.0.20-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.19 TTS 深度诊断 — package queries + 引擎状态检查 + 手动 bindService 测试（本次会话）：用户提供 v1.0.18 日志显示 `PM resolved engines: []` + 所有 `TextToSpeech` 构造器在 3-7ms 内返回 status=-1。根因分析：① MIUI 限制 `queryIntentServices` 即使有 `<queries>` intent 声明；② `bindService` 被立即拒绝（3ms 内返回 ERROR = bindService 返回 false）。修复：① `AndroidManifest.xml` `<queries>` 新增 4 个 `<package>` 声明（`com.xiaomi.mibrain.speech`/`com.google.android.tts`/`com.samsung.SMT`/`com.huawei.hivoice`），绕过 MIUI 的 `queryIntentServices` 限制；② `TtsManager.kt` init 中对每个已知引擎包用 `getPackageInfo` 检查安装/启用状态并日志 versionCode/versionName；③ 新增手动 `bindService(TTS_SERVICE)` 测试，区分 `TextToSpeech` 构造器 bug 和系统级 `bindService` 限制。bump versionCode 19→20 / versionName 1.0.18→1.0.19。CI 全绿验证通过（CI #31709650748 ✓ / Build & Release #31709671770 ✓），Release `Agora-v1.0.19-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.18 TTS 2 参数构造器优先 + 引擎间延迟 + 安装 Google TTS 按钮（本次会话）：用户反馈 v1.0.17「还是没声音」。策略调整：① **2 参数构造器优先**——之前所有版本都用 3 参数 `TextToSpeech(ctx, callback, engine)` 显式指定引擎，但 MIUI 上显式指定引擎可能触发 `bindService` 限制；改为先尝试 2 参数构造器 `TextToSpeech(ctx, callback)`（null engine = 让系统选择默认引擎，走隐式绑定路径），再回退到显式引擎；② **300ms 延迟**——引擎间切换加 `mainHandler.postDelayed(300ms)`，避免前一个 `TextToSpeech` 实例的 `bindService` 未完全清理就创建新实例导致实例污染；③ **PM 查询结果单独日志**——`queryIntentServices` 返回的引擎列表逐个 `getPackageInfo` 检查安装状态并单独日志；④ **安装 Google TTS 按钮**——新增 `installGoogleTtsIntent()`，设置页在 TTS 不可用时显示「安装 Google 语音」按钮（含 Play Store Web 回退）。修改 `util/TtsManager.kt`（234→266 行）：`enginesToTry` 改为 `List<String?>`，`tryNextEngine` 支持 null engine 分支，构造异常/失败回退加 300ms 延迟。修改 `ui/settings/SettingsGenerationPage.kt`（774→804 行）：新增条件项「安装 Google TTS」。strings.xml en+zh 各加 3 个字符串。bump versionCode 18→19 / versionName 1.0.17→1.0.18。首次 CI 失败（`Icons.Default.GetApp` 未解析）→ 改用 `Icons.Default.Build`，移动 tag 重触发。CI 全绿验证通过（CI #31707411230 ✓ / Build & Release #31707432134 ✓），Release `Agora-v1.0.18-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.15 TTS 关键修复——主线程 speak + 移除 AudioAttributes/focus + init 重试回退（本次会话）：用户反馈 v1.0.14「还有细节问题影响了，还是没声音」。全量复审 TtsManager.k) 在 `onInitResult`（binder 线程）中直接调用 `speakInternal()` flush `pendingText`——`TextToSpeech.speak()` 在 binder 线程执行，该线程无 Looper，某些 TTS 引擎要求 speak 在主线程调用，导致静默失败；② `setAudioAttributes(USAGE_MEDIA)` 可能覆盖引擎内部音频路由，路由到静音流；③ `requestAudioFocus` 可能被拒绝导致 TTS 静默。修复 `util/TtsManager.kt`（288→234 行）：① 新增 `mainHandler = Handler(Looper.getMainLooper())`，init 回调中 flush pendingText 时 `mainHandler.post { speakInternal(...) }` 强制切到主线程；② **移除 `setAudioAttributes`**——让引擎使用默认音频路由；③ **移除 `requestAudioFocus`/`abandonAudioFocus`** 及相关字段（`audioManager`/`audioFocusRequest`）和 import；④ init 失败时自动回退重试——3 参数构造器失败后用 2 参数构造器重试（最多 2 次）；⑤ 构造器加 try-catch 防异常崩溃。bump versionCode 15→16 / versionName 1.0.14→1.0.15，打 tag `v1.0.15` 触发 CI 发版。CI 全绿验证通过（Build & Release #31695867505 ✓ / CI #31695865344 ✓），Release `Agora-v1.0.15-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.14 TTS 根因修复——Log 替代 DebugLog + queries + ProGuard + 显式引擎 + reinit（本次会话）：用户反馈 v1.0.13「播放还是没有声音」。全量分析 TTS 调用链发现 3 个严重根因：① **DebugLog 在 release 中完全无效**——`FLAG_DEBUGGABLE=false` 时所有 `DebugLog.d/e/w` 是 no-op，v1.0.12/v1.0.13 的诊断日志在 release APK 中根本不输出，用户无法通过 logcat 看到任何信息；② **AndroidManifest 缺少 `<queries>` 声明**——targetSdk 36（API 30+ 包可见性过滤），未声明 `android.speech.tts.TTS_SERVICE` action，`TextToSpeech` 构造时 `bindService` 可能无法发现/绑定 TTS 引擎；③ **ProGuard/R8 可能混淆 UtteranceProgressListener 回调**——`isMinifyEnabled=true` 但无 keep 规则，匿名内部类的 `onStart/onDone/onError` 可能被重命名，导致 TTS 引擎无法回调。修复：① `util/TtsManager.kt`（253→288 行）——所有 `DebugLog.d/e` 替换为 `android.util.Log.d/e`（release 有效）；新增 `lastInitStatus`/`lastSpeakResult`/`lastLanguageResult` 三个 StateFlow 供 UI 实时显示诊断状态；使用 3 参数 `TextToSpeech(ctx, callback, engineName)` 构造器，从 `Settings.Secure.tts_default_synth` 读取系统默认引擎名显式指定；新增 `reinit()` 方法强制重建 TTS 实例；提取 `onInitResult()` 方法；`setLanguage` 返回值转为可读字符串（AVAILABLE/NOT_SUPPORTED/MISSING_DATA 等）。② `AndroidManifest.xml`——`<queries>` 新增 `android.speech.tts.TTS_SERVICE` intent action。③ `proguard-rules.pro`——新增 `-keep class com.lxseek.chat.util.TtsManager { *; }` 和 `-keep class com.lxseek.chat.util.TtsManager$* { *; }`。④ `ui/settings/SettingsGenerationPage.kt`（749→758 行）——测试按钮改用 `reinit()` 强制重建；诊断信息显示新增 Init/Speak/Lang 实时状态行。bump versionCode 14→15 / versionName 1.0.13→1.0.14，打 tag `v1.0.14` 触发 CI 发版。CI 全绿验证通过（Build & Release #31683387710 ✓ / CI #31683385871 ✓），Release `Agora-v1.0.14-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.13 TTS 诊断工具 + 测试按钮 + 系统设置入口 + 简化 speak params（本次会话）：用户反馈 v1.0.12「还是没声音」且无法提供 logcat 日志。策略转向：添加诊断工具让用户自行定位和修复 TTS 问题。① `util/TtsManager.kt`（218→253 行）：新增 `TtsDiagnosticInfo` 数据类（initialized/available/engineName/availableEngines/langMissingData）；新增 `getDiagnosticInfo()` 返回引擎名+可用引擎列表+初始化状态+语言数据状态；新增 `testSpeak()` 播放中英文测试语句；新增 `systemTtsSettingsIntent()` 返回 `Settings.ACTION_ACCESSIBILITY_SETTINGS` Intent；新增 `installTtsDataIntent()` 返回 `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA` Intent；新增 `_langMissingData` StateFlow 跟踪 `LANG_MISSING_DATA`；**简化 speak 调用**——移除 `Bundle(KEY_PARAM_VOLUME=1.0f)` 改用 `null` params（最高兼容性，部分引擎不支持 KEY_PARAM_VOLUME）；移除未使用的 `import android.os.Bundle`。② `ui/settings/SettingsGenerationPage.kt`（663→749 行）：TTS 设置组新增 4 项——「测试语音」按钮（调用 testSpeak）、「系统语音设置」按钮（打开系统 TTS 配置页）、「安装语音数据」按钮（条件显示，仅 langMissingData=true 时显示，调用 ACTION_INSTALL_TTS_DATA）、引擎信息显示（引擎名+可用引擎列表，或「未找到语音引擎」）。③ strings.xml en+zh 各加 11 个字符串。bump versionCode 13→14 / versionName 1.0.12→1.0.13，打 tag `v1.0.13` 触发 CI 发版。CI 全绿验证通过（Build & Release #31668857014 ✓ / CI #31668841546 ✓），Release `Agora-v1.0.13-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.12 TTS 诊断日志 + USAGE_MEDIA 修复（本次会话）：用户反馈 v1.0.11「还是没声音」。拉取分析 3 个开源 TTS 项目（Maise/VoxSherpa-TTS/CloneTTS）——均为 TTS 引擎（实现 `TextToSpeechService`），非调用方。Maise 的 `TtsFragment.kt` 用 `USAGE_MEDIA`（非 `USAGE_ASSISTANT`）。修复 `util/TtsManager.kt`（213→218 行）：① `USAGE_ASSISTANT` → `USAGE_MEDIA`（setAudioAttributes + requestAudioFocus 两处，Maise 同样用 USAGE_MEDIA）；② `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` → `AUDIOFOCUS_GAIN`（更强焦点请求）；③ 添加 DebugLog 诊断日志——init SUCCESS/FAILED、setLanguage 结果、speak 返回值+文本前50字、onStart/onDone/onError 回调、可用引擎列表（`tts?.engines`），用户可在 logcat 过滤 `TtsManager` tag 定位根因。bump versionCode 12→13 / versionName 1.0.11→1.0.12，打 tag `v1.0.12` 触发 CI 发版。CI 全绿验证通过（#31664603832 ✓），Release `Agora-v1.0.12-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.11 TTS 播放深度修复（本次会话）：用户反馈 v1.0.10「还是没声音」。对比开源阅读项目分析，定位 4 个根因：① **未设置 AudioAttributes**——某些设备/ROM 不设置时 TTS 不播放声音；② **未请求音频焦点**——国产 ROM（MIUI/EMUI/ColorOS）常需要音频焦点才输出；③ **setLanguage 回退不彻底**——回退到 `Locale.getDefault()` 仍不支持时放弃，TTS 沉默；④ **speak 的 params 为 null**——某些引擎需要显式 `KEY_PARAM_VOLUME`。修复 `util/TtsManager.kt`（162→213 行）：init 回调 SUCCESS 后设置 `AudioAttributes(USAGE_ASSISTANT + CONTENT_TYPE_SPEECH)`；新增 `requestAudioFocus()`/`abandonAudioFocus()`（API 26+ 用 `AudioFocusRequest`，API 24-25 用旧 `requestAudioFocus`），`speakInternal` 前请求、`stop`/`shutdown` 后释放；`setLanguage` 三级回退（指定→默认→英语）；`speak` 传 `Bundle(KEY_PARAM_VOLUME=1.0f)`。bump versionCode 11→12 / versionName 1.0.10→1.0.11，打 tag `v1.0.11` 触发 CI 发版。CI 全绿验证通过（#31662487231 ✓），Release `Agora-v1.0.11-android-arm64-v8a.apk` 已发布。

- 2026-08-13 v1.0.10 发版 + TTS 喇叭点击无声音修复（本次会话）：用户反馈「点了喇叭手机没调用 TTS 播放声音」。根因——`toggleTtsForMessage`（ChatViewModel.kt:922）在 `ttsEnabled=false`（**默认值**）时**静默 return**，但喇叭按钮始终显示（AssistantMessageContent.kt:562 无 `ttsEnabled` 显示条件），导致用户点喇叭→静默返回→无声音无提示。修复：点喇叭时若 TTS 未启用则自动 `settings.setTtsEnabled(true)` 并继续播放，不再静默 return——符合用户直觉（点喇叭即意图听语音）。同时 bump versionCode 10→11 / versionName 1.0.9→1.0.10，打 tag `v1.0.10` 触发 CI Build & Release 发版。CI 全绿验证通过（#31660083640 ✓），Release `Agora-v1.0.10-android-arm64-v8a.apk` (12.41 MB) 已发布。

- 2026-08-12 Agent 安全基础设施阶段3（Plan 系统 + 反思拦截 + ask_user 工具，本次会话）：借鉴 Marcel SSH 的 plan 系统设计。① 新建 `tool/PlanModels.kt`（`PlanItemStatus` 枚举 Pending/InProgress/Completed/Failed/Skipped + `isTerminal`/`symbol` 属性、`PlanItem` 数据类 id 不复用、`AgentTaskPlan` 数据类含 `reflectionReminded` 标志 + `isComplete`/`withItemUpdated`/`withItemAdded`/`withItemRemoved`/`withItemRenamed` 方法、`PlanToolOutputResult` + `MAX_PLAN_ITEMS=20` + `PLAN_CONTEXT_PREFIX`）；② 新建 `tool/PlanStateHolder.kt`（进程级 plan 状态管理，`StateFlow<Map<String, AgentTaskPlan>>`，内存存储）；③ 新建 `tool/PlanToolProvider.kt`（三个工具：`create_plan` 创建最多 20 步计划、`update_plan_item` 更新单项状态、`edit_plan` 批量增删改结构，全部 ReadOnly 风险，返回结构化 metadata）；④ 新建 `tool/PlanHandler.kt`（反思拦截核心：`handleToolOutput` 检测所有 item 终态且未提醒过时回滚最后一次状态变更并注入反思提醒要求可验证证据、`buildPlanContext` 构建带状态符号的计划上下文文本、`normalizeOnExit` 任务终止时 in_progress 降级为 pending）；⑤ 新建 `tool/AskUserToolProvider.kt`（`ask_user` 工具 + `AskUserController` 用 `CompletableDeferred` + `Mutex` + 120s 超时等待用户回答，支持 choices/multiple 选项）；⑥ `viewmodel/GenerationToolExecutor.kt` 集成：`createDefault()` 增加 `planToolProvider`/`askUserToolProvider`/`planStateHolder` 参数，`execute()` 方法工具执行后调用 `applyPlanReflection()` 处理 plan 工具输出和反思拦截；⑦ `viewmodel/GenerationManager.kt` 创建 `planStateHolder`/`askUserController` 进程级单例 + `planToolProvider`/`askUserToolProvider` 实例并传入 `createDefault()`。所有文件 ≤ 999 行（最大 GenerationManager 773 行）。CI 全绿验证通过（#31649227304 ✓）。
- 2026-08-12 Agent 安全基础设施阶段2（三层审批编排，本次会话）：在阶段1 基础上创建统一审批编排层。① 新建 `tool/ToolApproval.kt`（`ToolApprovalRequest` 数据类 + `ToolApprovalResult` 密封接口 Approved/Denied + `TOOLS_WITH_INTERNAL_CONFIRM` 集合 + `needsOuterApproval()` 策略函数——跳过有内部 confirm 的工具如 file_write/file_edit 避免双重提示，Auto 模式跳过非强制审批，Plan 模式跳过所有审批）；② `viewmodel/GenerationToolExecutor.kt` 在 `execute()` 方法中 provider 查找后、工具执行前插入审批检查：调用 `needsOuterApproval()` 判断是否需要外层审批，需要则构建 `ToolApprovalRequest` 调用 `onToolApproval` 回调，Denied 则返回错误结果不执行工具；`createDefault()` 和构造函数增加 `onToolApproval` 参数（默认 `{ null }` 即无审批门控）；`forTest()` 适配；新增 `buildApprovalSummary()` 辅助方法；③ `viewmodel/GenerationManager.kt` 增加 `onToolApproval` 回调属性并传递给 `createDefault()`；④ `viewmodel/ChatViewModel.kt` 连接 `onToolApproval` 到 `ShellConfirmationController.confirm()`——复用现有确认 UI，工具名作为 "server" 参数，返回 `ToolApprovalResult.Approved/Denied`；⑤ `automation/TaskExecutionEngine.kt` 同样连接 `onToolApproval`。设计决策：模型审批（独立 LLM 判断）留接口待阶段3 实现；现有 `ShellToolProvider.confirm` 保留不变（file_write/file_edit 的细粒度审批），ToolDispatcher 只处理其他需要审批的工具（stop_shell_job/delete_memory_file/delete_task 等 HighRisk 工具 + MCP 工具），避免重复审批。所有文件 ≤ 999 行（最大 ChatViewModel 984 行）。CI 全绿验证通过（#31640478338 ✓）。
- 2026-08-12 Agent 安全基础设施阶段1（RiskLevel + AgentMode + 模式感知注册，本次会话）：借鉴 Marcel SSH 的 agent 设计，为后续三层审批体系铺路。① 新建 `tool/RiskLevel.kt`（五级风险枚举：ReadOnly/LowRisk/Moderate/HighRisk/Destructive，含 `isDestructive()`/`isWritable()` 辅助方法）；② 新建 `tool/AgentMode.kt`（三模式枚举：Plan/Agent/Auto，含 `allowsRisk()`/`requiresConfirmation()` 策略方法——Plan 模式只允许 ReadOnly+LowRisk，Agent 模式全部允许但破坏性工具需确认，Auto 模式全部免确认）；③ `tool/ToolProvider.kt` 接口增加 `riskLevel(name): RiskLevel`（默认 ReadOnly）和 `requiresApprovalByDefault(name): Boolean`（默认 false）两个带默认实现的方法；④ `viewmodel/GenerationContracts.kt` 的 `GenerationContext` 增加 `agentMode: AgentMode = AgentMode.Agent` 字段（默认 Agent 模式，现有调用点无需修改）；⑤ 为 5 个 provider 添加 `riskLevel()` override：ShellToolProvider（list_shells/file_read/file_glob/file_grep/view_image/list_shell_jobs/get_shell_job/wait_for_job=ReadOnly，execute_shell_command=Moderate，stop_shell_job/file_write/file_edit=HighRisk）+ `requiresApprovalByDefault()`（file_write/file_edit/stop_shell_job=true）、MemoryToolProvider（list/read=ReadOnly，create/edit/update=LowRisk，delete=HighRisk）、AutomationToolProvider（list_tasks=ReadOnly，stop_loop=LowRisk，create_task/start_loop=Moderate，delete_task=HighRisk）、ImageGenToolProvider（LowRisk）、McpToolProvider（Moderate + requiresApprovalByDefault=true）；RagToolProvider 和 WebSearchToolProvider 全部 ReadOnly 使用接口默认实现；⑥ `viewmodel/GenerationToolExecutor.kt` 增加 `filterByAgentMode()` 扩展方法，在 `definitions()` 及所有分组方法（memory/webSearch/rag/shell/file Definitions）中按 agentMode 过滤不允许的风险等级——Plan 模式自动排除 Moderate/HighRisk/Destructive 工具。所有文件 ≤ 999 行（最大 ShellToolProvider 661 行）。CI 全绿验证通过（#31606555987 ✓）。
- 2026-08-12 连续语音对话（P2）实现 + 全量复查修复（本次会话）：用户要求"全部修复，修复后再检查"。① **P2 连续语音对话**（commit `437f831d`）：新建 `SttManager.kt`（SpeechRecognizer 封装，retryable init + `initializing` flag 防重复创建 + 可配置静音阈值 + partial results）、`VoiceConversationController.kt`（状态机 IDLE→LISTENING→PROCESSING→SPEAKING→LISTENING，TTS start grace window 5s，observeLlmAndTts 用 collectLatest，sendJob/ttsObserverJob 跟踪取消）、`TtsPlaybackHelper.kt`（从 ChatViewModel 提取 `playTtsForMessage` 30→3 行委托，ChatViewModel 993→977 行）、`VoiceMicButton.kt`（脉冲动画 mic FAB，state-colored container）。`ChatBottomBar`（985 行）加 mic 按钮，`ChatApp`（811 行）加 RECORD_AUDIO 权限 launcher + `isSupported` 检查 + 权限拒绝/不支持 Snackbar。设置四层（Schema/Manager/Repository/SettingsGenerationPage）+ strings en/zh（10 个 key）。AndroidManifest 加 RECORD_AUDIO 权限。② **全量复查**（10 个问题，3 严重/4 中等/3 轻微）：VoiceMicButton 条件调用 @Composable（slot table 崩溃）→ 改为始终调用 rememberInfiniteTransition + targetValue 控制；SttManager 重复创建 SpeechRecognizer → 加 `initializing` flag；ChatViewModel.onCleared 未清理 → 加 `voiceConversation.stop()`；sendMessage 协程未跟踪 → 加 sendJob cancel；init 块协程 → 改为 lazy 启动 ttsObserverJob；未检查 isRecognitionAvailable → 加检查 + Snackbar；权限拒绝无反馈 → 加 Snackbar；MessageLongImageRenderer 无尺寸上限 → 加 20000px cap + try-catch。③ **P1c brace 修复**：P1c commit `5f603166` 的 `if (!shareSelectionActive) {} else {}` 有额外 `}` 致 syntax error，修复 brace nesting。CI 全绿验证通过（#31568711157 ✓）。
- 2026-08-12 全量功能检查 + i18n/dead-code/fastlane 修复（本次会话）：用户要求"全量检查所有功能"。全量检查 TTS/分享/连续语音/设置页/字符串/编译/CI 共 7 大类 30+ 子项。发现并修复：① hardcoded "Conch"/"SSH" → `shell_type_ssh`/`shell_type_conch` 字符串资源（en+zh）；② fastlane changelog 缺 versionCode 10 → 添加 `10.txt`（en-US+zh-CN）；③ `SettingsClaudeImportPage.kt` 死代码删除 → `ImportStrategy` enum 迁移到 `ImportExportManager.kt`（经历 2 次 CI 修复：enum 误插 import 中间致 syntax error → 移到 import 后）。CI 全绿验证通过（#31561179441 ✓）。
- 2026-08-12 增加 CI 失败预防措施（本次会话）：用户反馈"github编译报错了…为什么这种问题容易出现，修复要看看以后怎么避免"。分析本次会话所有 CI 失败根因：① P1a 编译错误（`emitSnackbar` suspend 类型不匹配 + `String?` vs `String`）——本地无法编译、无 IDE 即时反馈、Kotlin 类型系统严格；② Build & Release 版本一致性失败——build.yml 已修改但**忘记 commit**，CI 用旧版本。修复：在 §R2.4 增加 push 前静态检查清单（`git status` 确认无残留 + suspend/nullable 类型检查 + 字符串/设置四层完整性检查）；在 §0 标准流程增加步骤 1b（会话开始前 `git status` 检查残留修改）。未改代码，仅回写 AGENTS.md。
- 2026-08-12 v1.0.9 版本 bump + Build & Release workflow 改进 + TTS/分享全量检查补缺（本次会话）：用户反馈 Build & Release 报错 + 要求全量检查 TTS/分享。① Build & Release 版本一致性失败根因：用户手动 `workflow_dispatch` 触发 v1.0.9 发版，但 `build.gradle.kts` 仍为 1.0.8/9，"Verify version consistency" 步骤失败。修复：bump versionCode 9→10 / versionName 1.0.8→1.0.9。② **改进 build.yml 避免未来此类问题**：`workflow_dispatch` 不再要求手动输入版本号——`get-version` job 始终从 `build.gradle.kts` 读取 `versionName` 作为唯一事实源（`VERSION=$(grep 'versionName' ...)`），tag push 时验证 tag 与 build.gradle.kts 一致，`workflow_dispatch` 时自动派生 tag。移除 `build-android` 中冗余的 "Verify version consistency" 步骤（get-version 已覆盖）。这消除了"手动输入版本 ≠ 代码版本"的整类失败。③ 全量检查 TTS/分享发现 3 个缺失并修复（commit `1aad5000`）：TTS「正在朗读」文字指示（AssistantMessageContent 加 Text label）、TTS 引擎不可用 Snackbar 提示（`playTtsForMessage` 加 `showFailureSnackbar` 参数，`tts_not_available` 字符串启用）、长按消息进入多选模式（MessageItem `pointerInput` + `detectTapGestures(onLongPress)`，经 MessageList→ChatApp 接线 `activateShareSelection` + `haptics.longPress()`）。CI 全绿验证通过（#31554958375 ✓）。
- 2026-08-12 TTS 自动播放修复 + P1a 编译错误修复 + P1b 分享导出过滤设置（本次会话）：用户反馈「编译报错了」+「TTS也没成功」。① P1a 编译错误（commit `ed3d5cab`）：P1a（复制纯文本）引入两个编译错误——`MessageExportController.emitSnackbar` 类型为 `(SnackbarEvent) -> Unit` 但 `_snackbarMessage.emit()` 是 suspend（`MutableSharedFlow.emit`），`currentConversationId.value` 是 `String?` 但参数要求 `String`。修复：`emitSnackbar` 改为 `suspend (SnackbarEvent) -> Unit`，`copyMessagesAsPlainText` 第一参数改为 `String?`（null 时 return）。② TTS 自动播放不工作（commit `b8dcb339`）：根因——`onStreamCommit`（自动播放路径）缺少 `toggleTtsForMessage`（手动播放路径）有的 init 重试和 5s grace window。当 TTS 引擎不可用时（init 失败或未初始化），自动播放设了 `_ttsPlayingMessageId` 但 `speak()` 缓冲到死引擎，UI 卡 Pause 图标无声音。修复：提取 `playTtsForMessage(messageId, text)` 共用方法（含 stripMarkdown + init 重试 + speak + grace window），`onStreamCommit` 和 `toggleTtsForMessage` 都调用它。ChatViewModel 997→985 行。③ P1b 分享导出过滤设置（commit `f6fd830e`）：新增 `SHARE_INCLUDE_THINKING`/`SHARE_INCLUDE_TOOLS` 设置（Schema/Manager/Repository 三层），`ConversationForkShareService.renderShare` 读设置传 `formatShareText` 过滤参数，`SettingsGenerationPage` 加 Export 设置组（两个 Switch），strings.xml en/zh 各加 5 个字符串。**CI 全绿验证通过（#31550658202 ✓）**。
- 2026-08-12 TTS 运行时调用失败全量修复（本次会话）：用户反馈「调用系统 TTS 没成功」。全量分析 TtsManager.kt + ChatViewModel.kt 调用链，定位 6 个根因：① `TtsManager.init` 的 `if (tts != null) return` 阻止重试——首次 init 回调 ERROR 后 `tts!=null && initialized=false`，后续 init 永远 return，TTS 永久不可用且无法恢复；② `toggleTtsForMessage` 在引擎不可用时仍设 `_ttsPlayingMessageId`，speak 走 pending 缓冲但 init 已失败，`isPlaying` 永不变 true，StateFlow 不再发射，UI 卡 Pause 图标无声音不恢复（用户看到的「卡住没声」直接原因）；③ `setOnUtteranceProgressListener` 在构造回调前设置，部分 vendor ROM 不生效致 isPlaying 永不更新；④ `setLanguage` 返回值未检查，`LANG_NOT_SUPPORTED`/`LANG_MISSING_DATA` 时 TTS 沉默；⑤ `speak` 返回值未检查，ERROR 时未通知 UI 清 playing；⑥ pendingText 在 init ERROR 时不清理（缓冲泄漏）。修复：`util/TtsManager.kt`（109→162 行）— init 改为可重试（`tts!=null && !initialized` 时 shutdown 重建）+ `initGeneration` token 防 stale 回调 clobber 新实例状态 + listener 移到 SUCCESS 回调内 + setLanguage 检查 `LANG_NOT_SUPPORTED`/`LANG_MISSING_DATA` 回退 `Locale.getDefault()` + `speak` 返回 Boolean（ERROR 返回 false）+ ERROR 分支清理 pendingText；`viewmodel/ChatViewModel.kt`（942→975 行）— `toggleTtsForMessage` 引擎不可用时先 (re)init + speak 返回 false 立即清 playing + 5s grace 窗口（`withTimeoutOrNull(TTS_START_GRACE_MS) { TtsManager.isPlaying.first { it } }`）防 init 最终失败时 UI 卡死 + init 块新增 `isAvailable` 订阅（引擎卸载/失败时 stop+清 playing）+ 顶层 `private const val TTS_START_GRACE_MS = 5_000L` + `import kotlinx.coroutines.withTimeoutOrNull`。静态自检通过（无 R.font、语言仅 en/zh、两文件 ≤999 行、import/类型正确、边角场景覆盖：多消息快速点击/引擎中途卸载/init 失败后重试/stale 回调）。**CI 全绿验证通过（#31543876365 ✓）**。
- 2026-08-12 v1.0.8 发版成功（回写漂移）：commit `a8924284`（2026-08-12 05:27），versionCode 8→9 / versionName 1.0.7→1.0.8，修复 Build & Release CI 版本一致性校验。CI 全绿（Build & Release #31541408295 ✓ / CI #31539617357 ✓ / Fastlane #31539617199 ✓），Release `Agora-v1.0.8-android-arm64-v8a.apk` 已发布。本次会话发现 AGENTS.md 未记录 v1.0.8（§2 仍写 1.0.7/8、§4 无 v1.0.8 条目、§9 无 v1.0.8 日志），回写消除漂移（§R0.3）。
- 2026-08-11 AGENTS.md 漂移修复（本次会话）：进入项目后发现 AGENTS.md 与代码严重不一致——① §2 硬约束版本号写 `1.0.5/6`，实际 build.gradle.kts 为 `1.0.7/8`；② §4 当前进度只记录到 v1.0.5，未记录 v1.0.6/v1.0.7 发版；③ TTS 功能变更日志标注"待 push CI 验证"，实际 CI #31489167800 已全绿（6m16s）；④ MarkdownDimens 崩溃仍在"已知问题"区，实际 commit `4ac51844` 重新应用的修复已通过 v1.0.6/v1.0.7 CI 验证。本次回写：§2 版本号更新为 1.0.7/8；§4 已完成区前置 v1.0.7 发版、TTS CI 全绿、MarkdownDimens 修复验证通过三条；§4 已知问题区移除 MarkdownDimens 崩溃条目；§6 TTS 勾选状态更新为"CI 全绿验证通过"并新增 MarkdownDimens/v1.0.6/v1.0.7 勾选项。未改代码，仅回写 AGENTS.md 消除漂移（§R0.3 单一事实源要求）。
- 2026-08-11 TTS 语音播报功能实现 + MarkdownDimens 崩溃修复确认：① 确认 `ChatMarkdownCodeBlock`（MessageBubbleAssets.kt:397-430）已含 `CompositionLocalProvider` 自给自足修复，`MainActivity.kt:447` 的 Dialog 调用安全；第 526/803 行裸读 `LocalMarkdownDimens.current` 在 `Markdown` 组件上下文内安全。② TTS 实现：新建 `util/TtsManager.kt`（109 行，object 单例封装 Android TextToSpeech，含 init/speak/stop/shutdown/stripMarkdown，暴露 isAvailable/isPlaying StateFlow）；`data/SettingsPreferenceSchema.kt` 加 3 key（TTS_ENABLED/TTS_LANGUAGE/TTS_SPEECH_RATE）；`data/SettingsManager.kt` 加 3 Flow + 3 setter + reset（ttsEnabled/ttsLanguage/ttsSpeechRate）；`data/repository/SettingsRepository.kt` 加 3 StateFlow + 3 setter；`viewmodel/ChatViewModel.kt` 加 ttsPlayingMessageId StateFlow + toggleTtsForMessage/stopTts 方法 + init 中 TtsManager.init + isPlaying 订阅 + onCleared 清理；`ui/chat/message/AssistantMessageContent.kt` 加喇叭按钮（VolumeUp/Pause 图标，在 ContentCopy 后）；`ui/chat/message/MessageItem.kt` + `ui/chat/MessageList.kt` + `ui/chat/ChatApp.kt` 参数传递链；`ui/settings/SettingsGenerationPage.kt` 加 TTS 设置组（启用开关 + 语言下拉 + 语速滑块）；`res/values/strings.xml` + `res/values-zh/strings.xml` 各加 12 个 TTS 字符串。所有文件 ≤ 999 行（最大 ChatViewModel.kt 942 行）。无 R.font 引用、语言仅 en/zh。CI 全绿验证通过（#31489167800 ✓）。
- 2026-08-11 ChatApp.kt 拆分完成（938→757 行）：三步提取 ① 底部栏区（166行）→ `ChatAppBottomBarSection.kt`（241行），含 Surface 渐变背景 + ChatBottomBar 调用 + LoopStatusBackdrop；② 欢迎页（35行）→ `ChatAppWelcomeContent` in `ChatAppOverlays.kt`（141→192行），含 TypewriterText 打字机动画；③ showButton 计算（38行）→ `rememberChatAppScrollToBottomButtonVisible` in `ChatAppInteractionEffects.kt`（401→457行），含 derivedStateOf + shouldShowAbsoluteBottomButton。清理 ChatApp.kt 13 个未使用 import（rememberScrollState/verticalScroll/CircleShape/stringResource/FontWeight/R/TypewriterMode/TypewriterText/CHAT_BOTTOM_BAR_OUTER_SHAPE/ChatBottomBar/LoopStatusBackdrop/Brush/drawBehind）。CI 全绿验证通过（commit `bcb73f5d` ✓）。
- 2026-08-11 ChatAppBottomBarSection.kt 提取 + 类型修复：从 ChatApp.kt 提取底部栏区（166行）→ 新文件 `ChatAppBottomBarSection.kt`（240行）。经历 4 次 CI 迭代修复类型错误：① `currentLoop: String?` → `LoopEntity?`；② `enabledModels: List<String>` → `Set<String>`；③ `selectedModel: String?` → `String`（非空，来自 `StateFlow<String>`）；④ `compactModel: String` → `String?`（可空，来自 `Flow<String?>`）+ 修复多余 `}`。CI 全绿验证通过（commit `cbb2df62` ✓）。
- 2026-08-11 包名重命名 com.newoether.agora → com.lxseek.chat + 开发者名字 → ojbkxc + CI 版本一致性校验：① 包名全量替换（627 文件）：578+ Kotlin 文件 package/import、198 测试文件、C++ JNI 函数名 `Java_com_newoether_agora_*` → `Java_com_lxseek_chat_*`、proguard-rules.pro、build.gradle.kts namespace/applicationId、fastlane Appfile、build-logic KotlinSourceSizePolicy.kt；目录重命名 `com/newoether/agora/` → `com/lxseek/chat/`（main + test + testFdroid 三处）。② 开发者名字 "Newo Ether" → "ojbkxc"（strings.xml about_developer_name en/zh、docs about.md/memory.md en/zh、LICENSE、server/conch/LICENSE）。③ Go 模块路径 `github.com/newo-ether/conch` → `github.com/ojbkxc/conch`（go.mod + Makefile + 所有 .go 文件 import）。④ README/docs 中 `com.newoether.agora` → `com.lxseek.chat`、`newo-ether/conch` → `ojbkxc/conch`。⑤ build.yml 添加 "Verify version consistency" 步骤（从 build.gradle.kts 提取 versionName 与 git tag 版本比较，不匹配则 fail）+ 去除 workflow_dispatch 硬编码默认值 `v1.0.0`。⑥ build-proot.sh NDK 路径 `/home/newoether/` → `/home/runner/`；server/crash 脚本 `newoether.space` → `example.com`。CI 全绿验证通过（CI #31445772623 ✓ / Fastlane #31445772575 ✓）。
- 2026-08-11 关于页面死代码清理：分析 `SettingsAboutPage.kt`（239 行），确认 4 个 URL 均指向 `ojbkxc/Agora`（✓）、版本号动态读取（✓）、字符串 en+zh 完整（✓）。删除 7 个未使用 import（`MutableInteractionSource`/`rememberScrollState`/`verticalScroll`/`ArrowBack`/`LocalFocusManager`/`FontWeight`/`UpdateInfo`）+ 1 个未使用变量（`focusManager`）+ 2 个未使用字符串资源（`about_source_code`/`about_rating`，en+zh 各删 2 行）。文件 239→230 行。同时修复 `MessageBubbleAssets.kt:402` 的 `markdownDimens()` @Composable 调用错误（从 `remember { markdownDimens() }` 改为直接调用，因 `markdownDimens()` 是 @Composable 函数不能在 `remember {}` lambda 中调用）。待 CI 全绿确认。
- 2026-08-11 修复 `IllegalStateException: No local MarkdownDimens` 崩溃（重新应用被回退的修复）：排查发现提交 `5ed80cec`("fix: revert unintended change to MessageBubbleAssets.kt") 错误回退了原修复 `5d15e79d`，使 v1.0.4/v1.0.5 仍含此崩溃。根因：`ChatMarkdownCodeBlock`（MessageBubbleAssets.kt:397）被 `MainActivity.kt:447` 的 `AlertDialog` 直接调用，Dialog 拥有独立 CompositionLocal 上下文，父级 `Markdown` 提供的 `LocalMarkdownDimens` 等不透传进 Dialog，裸读 `LocalMarkdownDimens.current` 抛 `IllegalStateException: No local MarkdownDimens`。CI 仅 `assembleFdroidRelease` 编译验证不捕获运行时崩溃，故回退后 CI 仍全绿但 APK 含 bug。修复：`ChatMarkdownCodeBlock` 内用 `CompositionLocalProvider` 自给自足提供 `LocalMarkdownDimens`（`markdownDimens()`）/`LocalMarkdownColors`/`LocalMarkdownTypography`/`LocalMarkdownPadding`（后三者取自 `assets.renderContext`），`shape` 改用局部 `dimens.codeBackgroundCornerSize`。新增 3 import（`CompositionLocalProvider`/`LocalMarkdownTypography`/`markdownDimens`）。文件 849→860 行（≤999 ✓），静态自检通过（无 R.font、语言仅 en/zh、无残留裸读）。待 push CI 编译验证。**更正历史漂移**：旧 §9 称修复在 commit `01f862ea` 有误——`01f862ea` 实为 "pass modifier param to overlay composables" 提交；真正修复提交为 `5d15e79d`，但随后被 `5ed80cec` 回退，本次重新应用。
- 2026-08-11 fastlane 自动化：创建 `fastlane/Fastfile`（lane: build_fdroid/build_play/github_release/validate_metadata/generate_changelog/release）、`Appfile`（package_name = com.newoether.agora）、`Gemfile`（fastlane ~2.225）；新增 `.github/workflows/fastlane.yml`（PR/push 触发，Ruby 3.3 + bundler-cache，运行 `validate_metadata` 验证 en-US + zh-CN 元数据完整性）；添加 versionCode 6 changelog（en-US + zh-CN，对应 v1.0.5 变更内容）。CI 全绿验证通过（Fastlane #31409966996 ✓ / CI #31409967059 ✓）。
- 2026-08-11 v1.0.5 发版成功：修复 `DebugLog.i()` 编译错误（`AppContainer.kt:89` 调用了不存在的 `DebugLog.i()`，DebugLog 类只有 `d`/`e`/`w` 方法，改为 `DebugLog.d()`）+ `showSnackbar()` 未包在协程中的编译错误（`MainActivity.kt:584` 在 `TextButton.onClick` lambda 中直接调用 suspend 函数 `showSnackbar()`，改为包在 `ratingScope.launch {}` 中）。bump versionCode 5→6 / versionName 1.0.4→1.0.5。删除有问题的 v1.0.4 tag（指向 commit `82d50451` 含编译错误）和误标的 v1.0.3 tag（指向修复 commit `4627e4c4` 但版本号倒退）。打 tag `v1.0.5` push 触发 CI，首次因 tag 创建在协程修复 commit 之前而失败，移动 tag 到 HEAD 后 force-push 重触发。CI 全绿验证通过（Build & Release #31408098890 ✓ / CI #31407729636 ✓），Release `Agora-v1.0.5-android-arm64-v8a.apk` 已发布。
- 2026-08-10 v1.0.3 发版：修复崩溃日志上传 / 评分提交 / 更新检查指向原仓库 `newo-ether/Agora` 而非本仓库 `ojbkxc/Agora` 的问题。① 更新检查 `UpdateChecker.kt` GitHub API URL 改为 `ojbkxc/Agora`；② 崩溃日志 `CrashReporter.kt` 不再 POST 到 `newoether.space/crash`，改为构建预填 GitHub Issue URL（`ojbkxc/Agora/issues/new`），`MainActivity.kt` 用 `Intent(ACTION_VIEW)` 打开浏览器让用户审阅提交；③ 评分提交 `RatingForm.kt` 同理改为打开浏览器预填 GitHub Issue，移除 email 输入框与 HTTP/okhttp 依赖；④ 关于页 4 链接 / 文档站点 / OpenRouter Referer / mkdocs.yml / docs/ / README / PRIVACY.md 全部 `newo-ether/Agora`→`ojbkxc/Agora`；⑤ strings.xml 隐私文案更新（en+zh）；⑥ 测试 `HttpClientLocalHostTest.kt` 中 `newoether.space`→`example.com`。bump versionCode 3→4 / versionName 1.0.2→1.0.3，打 tag `v1.0.4` push 触发 CI。CI 全绿验证通过（CI #12 ✓ / Build & Release #10 ✓ / Deploy MkDocs #2 ✓），Release `Agora-v1.0.4-android-arm64-v8a.apk` 已发布。
- 2026-08-10 内嵌 Conch shell 服务器集成：将 `github.com/ojbkxc/conch` Go 源码融入 `server/conch/`，通过 gomobile bind 编译为 .aar 内嵌到 Android 进程。新增文件：`server/conch/`（conch 全部源码 + `mobile/mobile.go` gomobile 绑定包 + `build-android.sh` 构建脚本）、`app/src/main/java/com/newoether/agora/shell/ConchServiceManager.kt`（反射调用 mobile.Mobile，无 .aar 时安全降级）。修改：`shell/executor_unix.go` 添加 `SetShellPath()` 支持 Android `/system/bin/sh`；`app/build.gradle.kts` 添加 `fileTree("libs/*.aar")` 依赖；`di/AppContainer.kt` 在 `startProcessServices()` 中启动内嵌 conch；`.gitignore` 添加 `/app/libs/`；`.github/workflows/build.yml` 添加 Go setup + gomobile bind 步骤。API key 自动生成并持久化到 SharedPreferences。待 push CI 验证。
- 2026-08-10 v1.0.2 发版：bump versionCode 2→3 / versionName 1.0.1→1.0.2，打 tag `v1.0.2` push 触发 CI。修复 `IllegalStateException: No local MarkdownDimens` 崩溃（修复已在 master commit `01f862ea`，但 v1.0.1 tag 在其之前，故 v1.0.1 APK 含此 bug）。待 CI 全绿确认发版成功。
- 2026-08-10 修复 `IllegalStateException: No local MarkdownDimens` 崩溃：`ChatMarkdownCodeBlock`（MessageBubbleAssets.kt:397）被 `MainActivity.kt:447` 的 `AlertDialog` 直接调用，而 Dialog 拥有独立 CompositionLocal 上下文，父级 `Markdown` 组件提供的 `LocalMarkdownDimens`/`LocalMarkdownColors`/`LocalMarkdownPadding` 不透传进 Dialog，导致 `compositionLocalOf` 抛错。修复：在 `ChatMarkdownCodeBlock` 内部用 `CompositionLocalProvider` 自给自足提供 `LocalMarkdownDimens`（默认 `markdownDimens()`）+ `LocalMarkdownColors`/`LocalMarkdownTypography`/`LocalMarkdownPadding`（取自 `assets.renderContext`，样式与 chat 代码块一致）。新增 3 个 import。文件 849→860 行（≤999 ✓）。修复已在 commit `01f862ea`。
- 2026-08-10 v1.0.1 发版成功：CI 全绿（get-version ✓ / build-android ✓ / release ✓），产物 `Agora-v1.0.1-android-arm64-v8a.apk` (27.56 MB) 已发布到 GitHub Release。回写 §4/§6/§9。
- 2026-08-10 修复 CI release job 403 权限错误：build.yml 添加 `permissions: contents: write`，使 `GITHUB_TOKEN` 有权创建 Release。重新打 tag v1.0.1 触发 CI，全绿。
- 2026-08-10 修复 values-zh 资源重复键编译错误：删除多余的 `automation_strings.xml`（88 键已在 `strings.xml` 中），消除 Android duplicate resource 错误。
- 2026-08-10 重写 AGENTS.md 参照 AXON 严谨流程：新增 §R0 强制规则（10 条，含 i18n/字体/CI 验证/auto-continue）、§R2 GitHub CI 编译验证策略、§0 标准流程、§1 项目定位、§2 硬约束、§3 仓库结构、§4 当前进度、§5 关键接口契约、§6 下一步任务、§7 编码约定、§8 常用命令、§10 参考索引。未改代码，仅重写 AGENTS.md。下一步：push tag v1.0.0 触发 CI 验证。
- 2026-08-10 语言精简 + 字体删除 + 流水线改造 + 首版 AGENTS.md：删除 10 个非 en/zh 语言资源目录 + 9 个文档语言目录；删除 9 个 TTF 字体文件（~23.1MB）改用系统字体；版本号改为 1.0.0/1；重写 `.github/workflows/build.yml`（tag 触发 → APK → GitHub Release，产物 `Agora-v{VERSION}-android-arm64-v8a.apk`）；创建 AGENTS.md；从 `.gitignore` 移除 AGENTS.md 忽略规则。已推送 commit `5f0de741` → `origin/master`（308 文件，+361/-35086 行）。未通过 CI 编译验证。

## 10. 参考索引

- 架构文档：`ARCHITECTURE.md`（490 行，详细架构说明）。
- 版本目录：`gradle/libs.versions.toml`（AGP/Kotlin/Compose/Room 等版本统一管理）。
- 上游借鉴：`/opt/github/RustSync`（编译流水线参照：tag 触发 → 产物命名 → GitHub Release 模式）。
- 关键文件速查：
  - 应用入口：`app/src/main/java/com/lxseek/chat/MainActivity.kt`
  - Application：`app/src/main/java/com/lxseek/chat/AgoraApplication.kt`
  - DI 容器：`app/src/main/java/com/lxseek/chat/di/AppContainer.kt`
  - Provider 接口：`app/src/main/java/com/lxseek/chat/api/LlmProvider.kt`
  - HTTP 客户端：`app/src/main/java/com/lxseek/chat/api/HttpClient.kt`
  - 主题：`app/src/main/java/com/lxseek/chat/ui/theme/{Type,Theme,Color}.kt`
  - 语言选项：`app/src/main/java/com/lxseek/chat/ui/settings/SettingsLanguagePage.kt`
  - 系统提示：`app/src/main/java/com/lxseek/chat/data/DefaultSystemPrompt.kt`
  - 聊天主 Composable：`app/src/main/java/com/lxseek/chat/ui/chat/ChatApp.kt`（757 行）
  - 聊天拆分文件：`ChatAppBottomBarSection.kt`（241）/ `ChatAppOverlays.kt`（192）/ `ChatAppInteractionEffects.kt`（457）/ `ChatAppDialogHost.kt`（146）
  - 构建配置：`app/build.gradle.kts`
  - CI 流水线：`.github/workflows/build.yml`
  - PRoot 构建：`build-proot.sh`
  - 原生构建：`app/src/main/cpp/CMakeLists.txt`
