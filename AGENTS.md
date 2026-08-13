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
| 版本 | versionName `1.0.10` / versionCode `11` | `defaultConfig` |
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
