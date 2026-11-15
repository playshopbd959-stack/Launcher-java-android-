# NOXER LAUNCHER

**NOXER LAUNCHER** is a modular Minecraft: Java Edition launcher for Android built with **Kotlin, Jetpack Compose, C++17 (Android NDK / JNI), OpenGL ES 3.0 / EGL, Room, DataStore, and OkHttp**.

## Implemented Features

1. **Universal Version Center (`VersionCenterRepository`, `MojangManifestApi`)**:
   - Connects directly to Mojang's official `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`.
   - Dynamically caches and filters all official Releases, Snapshots, Old Betas/Alphas, Historical builds, Experimental builds, and Installed versions.
   - Fetches full per-version JSONs (`mainClass`, `javaVersion`, `assetIndex`, `libraries`, `downloads.client`, `logging`).
2. **Independent Installations (`InstallationManager`, `NoxerDatabase`)**:
   - Supports multiple isolated instances per Minecraft version (`<filesDir>/noxer/instances/<slug>/.minecraft/`).
   - Provides Play, Edit, Duplicate, Repair, Delete, and in-app Game Directory browser + SAF ZIP export.
3. **Real Parallel Download Engine (`DownloadEngine`, `MojangRuleEvaluator`)**:
   - Evaluates OS/arch rules, resolves Maven library paths, downloads client JARs, libraries, logging configs, asset indexes, and asset objects with configurable concurrency, HTTP `Range` resume, retry backoff, cancellation, and SHA-1 verification.
4. **Microsoft OAuth2 Authentication (`MicrosoftAuthManager`, `AccountCrypto`)**:
   - Implements OAuth2 Device Code Flow -> Xbox Live -> XSTS -> Minecraft Services -> `entitlements/mcstore` ownership verification -> Profile lookup.
   - Encrypts tokens via Android Keystore AES-GCM.
5. **Java Runtime Engine (`JavaRuntimeManager`)**:
   - Queries Eclipse Temurin API v3 for Linux JRE 8, 17, and 21 matching device ABI (`arm64-v8a` / `x86_64`), verifies SHA-256 checksums, extracts `.tar.gz` safely (with Zip-Slip guard), validates ELF `e_machine`, and probes `libjvm.so` via JNI `dlopen`.
6. **Native Compatibility & Rendering (`noxer_native.cpp`, `NoxerNativeBridge`, `GameViewportActivity`)**:
   - Real C++17 JNI shared library (`libnoxer_native.so`) compiled with CMake & NDK r27d.
   - Probes EGL/OpenGL ES 3.0 capabilities, binds `ANativeWindow` surface, provides `dlopen` + `JNI_CreateJavaVM` launch bridge with stdout/stderr pipe forwarding, and maintains a thread-safe JNI input event ring buffer.
7. **Mod Loaders & Controls (`ModLoaderManager`, `InputManager`)**:
   - Connects to Fabric Meta API (`meta.fabricmc.net`), NeoForge Maven metadata, and Forge Promotions JSON.
   - Parses mod `.jar` manifests (`fabric.mod.json`, `mods.toml`, `neoforge.mods.toml`), supports `.mrpack` import, conflict warnings, drag-and-drop Touch Layout Editor, physical keyboard/mouse pointer capture, and gamepad detection.

## Build Instructions

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

## Hardware & Runtime Notes

- Standard upstream Eclipse Temurin Linux builds link against GNU `glibc` (`ld-linux-aarch64.so.1`) rather than Android `Bionic` (`libc.so`). Full desktop Minecraft frame rendering on-device additionally requires an Android-bionic OpenJDK build, Android-patched LWJGL3-GLFW, and GL4ES/Zink native translation libraries.
- See `ARCHITECTURE.md` and `LICENSES.md` for full architecture and third-party license details.
