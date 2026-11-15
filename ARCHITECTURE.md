# NOXER LAUNCHER Architecture

## Module Breakdown

| Module | Package / Path | Responsibility |
|---|---|---|
| Main Application | `com.example.NoxerApplication` | Initializes `AppContainer` and native bridge diagnostics |
| Launcher Core | `com.example.noxer.core` | `AppContainer`, `NoxerPaths` canonical directory layout |
| Version Manager | `com.example.noxer.version` | `MojangManifestApi`, `MojangModels`, `VersionCenterRepository` |
| Download Engine | `com.example.noxer.download` | `DownloadEngine` (OkHttp + Semaphore + Range + SHA-1), `MojangRuleEvaluator` |
| Installation Manager | `com.example.noxer.installation` | `InstallationManager`, isolated `.minecraft` directories, repair & duplicate |
| Database | `com.example.noxer.data.db` | Room `NoxerDatabase` (`InstallationEntity`, `AccountEntity`, `RuntimeEntity`, `ControlProfileEntity`, `CachedVersionEntity`, `ConsoleLogEntity`, `CrashReportEntity`) |
| Account Manager | `com.example.noxer.auth` | `MicrosoftAuthManager`, `AccountCrypto` (Android Keystore AES-256-GCM) |
| Java Runtime Engine | `com.example.noxer.runtime` | `JavaRuntimeManager` (Temurin v3 API, SHA-256, `tar.gz` extraction, ELF header check) |
| Native Compatibility | `app/src/main/cpp/noxer_native.cpp` | C++17 JNI: EGL/GLES3 probe, `ANativeWindow`, `dlopen` `libjvm.so` + `JNI_CreateJavaVM`, pipe redirect, input ring buffer |
| Rendering Engine | `com.example.noxer.graphics` | `RendererManager`, real Choreographer FPS & memory telemetry |
| Input Manager | `com.example.noxer.input` | `InputManager`, Touch Layout Editor, GLFW keycode translation, mouse pointer capture, gamepad scan |
| Mod Manager | `com.example.noxer.mods` | `ModLoaderManager` (Fabric/NeoForge/Forge APIs, `.jar` inspection, `.mrpack` importer) |
| Game Launcher & Crash | `com.example.noxer.launch` | `GameLauncher` (16-step pipeline), `GameViewportActivity`, `CrashHandler` |
| Storage & Settings | `com.example.noxer.storage`, `.settings` | `StorageManager` (SAF ZIP backup/restore), `SettingsManager` (DataStore) |
