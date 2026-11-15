#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <pthread.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>
#include <deque>

#define LOG_TAG "NoxerNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct EglContextState {
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    int width = 0;
    int height = 0;
    std::string vendor;
    std::string renderer;
    std::string version;
    std::string extensions;
};

struct InputEventItem {
    int type;     // 1=key, 2=mouse_btn, 3=mouse_move, 4=scroll, 5=touch
    int code;
    int action;   // 0=up, 1=down, 2=move
    float x;
    float y;
    int modifiers;
};

std::mutex g_eglMutex;
EglContextState g_egl;

std::mutex g_inputMutex;
std::deque<InputEventItem> g_inputQueue;
constexpr size_t MAX_INPUT_QUEUE = 512;

JavaVM* g_androidVm = nullptr;

// Standard JNI Invocation API types for dynamically loaded libjvm.so
typedef jint (JNICALL *CreateJavaVMFn)(JavaVM**, void**, void*);

struct JavaVMOptionCompat {
    char* optionString;
    void* extraInfo;
};

struct JavaVMInitArgsCompat {
    jint version;
    jint nOptions;
    JavaVMOptionCompat* options;
    jboolean ignoreUnrecognized;
};

struct PipeBridgeArgs {
    int readFd;
    jobject callbackGlobalRef;
    jmethodID onLineMethod;
    bool isError;
};

void* pipeReaderThread(void* arg) {
    auto* bridge = static_cast<PipeBridgeArgs*>(arg);
    if (!bridge || !g_androidVm) {
        delete bridge;
        return nullptr;
    }

    JNIEnv* env = nullptr;
    if (g_androidVm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        close(bridge->readFd);
        delete bridge;
        return nullptr;
    }

    char buffer[2048];
    std::string lineBuffer;
    ssize_t bytesRead;
    while ((bytesRead = read(bridge->readFd, buffer, sizeof(buffer) - 1)) > 0) {
        buffer[bytesRead] = '\0';
        lineBuffer.append(buffer, static_cast<size_t>(bytesRead));
        size_t pos = 0;
        while ((pos = lineBuffer.find('\n')) != std::string::npos) {
            std::string line = lineBuffer.substr(0, pos);
            lineBuffer.erase(0, pos + 1);
            jstring jLine = env->NewStringUTF(line.c_str());
            if (jLine) {
                env->CallVoidMethod(bridge->callbackGlobalRef, bridge->onLineMethod, jLine,
                                    static_cast<jboolean>(bridge->isError));
                env->DeleteLocalRef(jLine);
            }
        }
    }
    if (!lineBuffer.empty()) {
        jstring jLine = env->NewStringUTF(lineBuffer.c_str());
        if (jLine) {
            env->CallVoidMethod(bridge->callbackGlobalRef, bridge->onLineMethod, jLine,
                                static_cast<jboolean>(bridge->isError));
            env->DeleteLocalRef(jLine);
        }
    }

    close(bridge->readFd);
    env->DeleteGlobalRef(bridge->callbackGlobalRef);
    g_androidVm->DetachCurrentThread();
    delete bridge;
    return nullptr;
}

void destroyEglLocked() {
    if (g_egl.display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_egl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_egl.context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_egl.display, g_egl.context);
        }
        if (g_egl.surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_egl.display, g_egl.surface);
        }
        eglTerminate(g_egl.display);
    }
    if (g_egl.window != nullptr) {
        ANativeWindow_release(g_egl.window);
    }
    g_egl.display = EGL_NO_DISPLAY;
    g_egl.surface = EGL_NO_SURFACE;
    g_egl.context = EGL_NO_CONTEXT;
    g_egl.window = nullptr;
    g_egl.width = 0;
    g_egl.height = 0;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_androidVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeGetArchitecture(
        JNIEnv* env, jobject) {
#if defined(__aarch64__)
    const char* abi = "arm64-v8a";
#elif defined(__arm__)
    const char* abi = "armeabi-v7a";
#elif defined(__x86_64__)
    const char* abi = "x86_64";
#elif defined(__i386__)
    const char* abi = "x86";
#else
    const char* abi = "unknown";
#endif
    return env->NewStringUTF(abi);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeProbeEglCapabilities(
        JNIEnv* env, jobject) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        return env->NewStringUTF("EGL_ERROR: No default EGL display available");
    }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(display, &major, &minor)) {
        return env->NewStringUTF("EGL_ERROR: eglInitialize failed");
    }

    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint numConfigs = 0;
    bool gles3 = eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) && numConfigs > 0;

    std::string report;
    report += "EGL_VERSION=" + std::to_string(major) + "." + std::to_string(minor) + "\n";
    report += "GLES3_SUPPORTED=" + std::string(gles3 ? "true" : "false") + "\n";

    if (gles3) {
        const EGLint pbufferAttribs[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
        EGLSurface pbuffer = eglCreatePbufferSurface(display, config, pbufferAttribs);
        const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        EGLContext ctx = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
        if (pbuffer != EGL_NO_SURFACE && ctx != EGL_NO_CONTEXT) {
            if (eglMakeCurrent(display, pbuffer, pbuffer, ctx)) {
                const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
                const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
                const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
                const char* exts = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
                report += "GL_VENDOR=" + std::string(vendor ? vendor : "unknown") + "\n";
                report += "GL_RENDERER=" + std::string(renderer ? renderer : "unknown") + "\n";
                report += "GL_VERSION=" + std::string(version ? version : "unknown") + "\n";
                if (exts) {
                    std::string extStr(exts);
                    report += "EXT_COUNT_BYTES=" + std::to_string(extStr.size()) + "\n";
                    report += "HAS_S3TC=" + std::string(extStr.find("texture_compression_s3tc") != std::string::npos ? "true" : "false") + "\n";
                    report += "HAS_ASTC=" + std::string(extStr.find("texture_compression_astc") != std::string::npos ? "true" : "false") + "\n";
                }
                eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            }
            eglDestroyContext(display, ctx);
        }
        if (pbuffer != EGL_NO_SURFACE) eglDestroySurface(display, pbuffer);
    }

    eglTerminate(display);
    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeInitWindow(
        JNIEnv* env, jobject, jobject surface) {
    std::lock_guard<std::mutex> lock(g_eglMutex);
    destroyEglLocked();

    if (!surface) return JNI_FALSE;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display, attribs, &config, 1, &numConfigs) || numConfigs == 0) {
        eglTerminate(display);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    EGLint format = 0;
    eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(window, 0, 0, format);

    EGLSurface eglSurface = eglCreateWindowSurface(display, config, window, nullptr);
    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext eglContext = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
    if (eglSurface == EGL_NO_SURFACE || eglContext == EGL_NO_CONTEXT) {
        if (eglContext != EGL_NO_CONTEXT) eglDestroyContext(display, eglContext);
        if (eglSurface != EGL_NO_SURFACE) eglDestroySurface(display, eglSurface);
        eglTerminate(display);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    if (!eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
        eglDestroyContext(display, eglContext);
        eglDestroySurface(display, eglSurface);
        eglTerminate(display);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    g_egl.window = window;
    g_egl.display = display;
    g_egl.surface = eglSurface;
    g_egl.context = eglContext;
    g_egl.width = ANativeWindow_getWidth(window);
    g_egl.height = ANativeWindow_getHeight(window);
    const char* v = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* r = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* ver = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    g_egl.vendor = v ? v : "";
    g_egl.renderer = r ? r : "";
    g_egl.version = ver ? ver : "";

    glViewport(0, 0, g_egl.width, g_egl.height);
    LOGI("EGL initialized %dx%d on %s", g_egl.width, g_egl.height, g_egl.renderer.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeResizeWindow(
        JNIEnv*, jobject, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_eglMutex);
    g_egl.width = width;
    g_egl.height = height;
    if (g_egl.display != EGL_NO_DISPLAY && g_egl.surface != EGL_NO_SURFACE) {
        eglMakeCurrent(g_egl.display, g_egl.surface, g_egl.surface, g_egl.context);
        glViewport(0, 0, width, height);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeRenderHeartbeatFrame(
        JNIEnv*, jobject, jfloat pulse) {
    std::lock_guard<std::mutex> lock(g_eglMutex);
    if (g_egl.display == EGL_NO_DISPLAY || g_egl.surface == EGL_NO_SURFACE) return;
    eglMakeCurrent(g_egl.display, g_egl.surface, g_egl.surface, g_egl.context);
    float red = 0.03f + 0.04f * pulse;
    glClearColor(red, 0.03f, 0.045f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    eglSwapBuffers(g_egl.display, g_egl.surface);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeDestroyWindow(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_eglMutex);
    destroyEglLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativePushInputEvent(
        JNIEnv*, jobject, jint type, jint code, jint action, jfloat x, jfloat y, jint modifiers) {
    std::lock_guard<std::mutex> lock(g_inputMutex);
    if (g_inputQueue.size() >= MAX_INPUT_QUEUE) {
        g_inputQueue.pop_front();
    }
    g_inputQueue.push_back({type, code, action, x, y, modifiers});
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeGetPendingInputCount(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_inputMutex);
    return static_cast<jint>(g_inputQueue.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeSetEnv(
        JNIEnv* env, jobject, jstring key, jstring value) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    if (k && v) {
        setenv(k, v, 1);
    }
    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeProbeLibJvm(
        JNIEnv* env, jobject, jstring libJvmPath) {
    const char* path = env->GetStringUTFChars(libJvmPath, nullptr);
    if (!path) return env->NewStringUTF("ERROR: Null libjvm path");

    struct stat st{};
    if (stat(path, &st) != 0) {
        std::string msg = "MISSING: File does not exist at ";
        msg += path;
        env->ReleaseStringUTFChars(libJvmPath, path);
        return env->NewStringUTF(msg.c_str());
    }

    void* handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char* err = dlerror();
        std::string msg = "DLOPEN_FAILED: ";
        msg += (err ? err : "unknown dlopen error");
        env->ReleaseStringUTFChars(libJvmPath, path);
        return env->NewStringUTF(msg.c_str());
    }

    auto createVm = reinterpret_cast<CreateJavaVMFn>(dlsym(handle, "JNI_CreateJavaVM"));
    if (!createVm) {
        std::string msg = "SYMBOL_MISSING: JNI_CreateJavaVM not exported by ";
        msg += path;
        dlclose(handle);
        env->ReleaseStringUTFChars(libJvmPath, path);
        return env->NewStringUTF(msg.c_str());
    }

    dlclose(handle);
    env->ReleaseStringUTFChars(libJvmPath, path);
    return env->NewStringUTF("OK: libjvm.so loaded and JNI_CreateJavaVM symbol verified");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_noxer_nativebridge_NoxerNativeBridge_nativeLaunchJvm(
        JNIEnv* env,
        jobject,
        jstring jLibJvmPath,
        jobjectArray jJvmOptions,
        jstring jMainClass,
        jobjectArray jGameArgs,
        jobject jLogCallback) {
    const char* libJvmPath = env->GetStringUTFChars(jLibJvmPath, nullptr);
    const char* mainClassRaw = env->GetStringUTFChars(jMainClass, nullptr);
    if (!libJvmPath || !mainClassRaw) {
        if (libJvmPath) env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        if (mainClassRaw) env->ReleaseStringUTFChars(jMainClass, mainClassRaw);
        return -101;
    }

    jclass cbClass = env->GetObjectClass(jLogCallback);
    jmethodID onLineMethod = env->GetMethodID(cbClass, "onLogLine", "(Ljava/lang/String;Z)V");

    auto emitLog = [&](const std::string& msg, bool isErr) {
        if (onLineMethod) {
            jstring s = env->NewStringUTF(msg.c_str());
            if (s) {
                env->CallVoidMethod(jLogCallback, onLineMethod, s, static_cast<jboolean>(isErr));
                env->DeleteLocalRef(s);
            }
        }
    };

    void* jvmHandle = dlopen(libJvmPath, RTLD_NOW | RTLD_GLOBAL);
    if (!jvmHandle) {
        const char* err = dlerror();
        emitLog(std::string("[NoxerNative] Failed to dlopen libjvm.so: ") + (err ? err : "unknown"), true);
        env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        env->ReleaseStringUTFChars(jMainClass, mainClassRaw);
        return -102;
    }

    auto createJavaVm = reinterpret_cast<CreateJavaVMFn>(dlsym(jvmHandle, "JNI_CreateJavaVM"));
    if (!createJavaVm) {
        emitLog("[NoxerNative] Symbol JNI_CreateJavaVM not found in libjvm.so", true);
        dlclose(jvmHandle);
        env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        env->ReleaseStringUTFChars(jMainClass, mainClassRaw);
        return -103;
    }

    // Redirect stdout/stderr into Kotlin console callback
    int stdoutPipe[2] = {-1, -1};
    int stderrPipe[2] = {-1, -1};
    int savedStdout = dup(STDOUT_FILENO);
    int savedStderr = dup(STDERR_FILENO);

    if (pipe(stdoutPipe) == 0 && pipe(stderrPipe) == 0) {
        dup2(stdoutPipe[1], STDOUT_FILENO);
        dup2(stderrPipe[1], STDERR_FILENO);
        close(stdoutPipe[1]);
        close(stderrPipe[1]);

        if (onLineMethod) {
            pthread_t outThread, errThread;
            auto* outArgs = new PipeBridgeArgs{stdoutPipe[0], env->NewGlobalRef(jLogCallback), onLineMethod, false};
            auto* errArgs = new PipeBridgeArgs{stderrPipe[0], env->NewGlobalRef(jLogCallback), onLineMethod, true};
            pthread_create(&outThread, nullptr, pipeReaderThread, outArgs);
            pthread_create(&errThread, nullptr, pipeReaderThread, errArgs);
            pthread_detach(outThread);
            pthread_detach(errThread);
        }
    }

    jsize optCount = env->GetArrayLength(jJvmOptions);
    std::vector<std::string> optStrings;
    std::vector<JavaVMOptionCompat> options(static_cast<size_t>(optCount));
    optStrings.reserve(static_cast<size_t>(optCount));

    for (jsize i = 0; i < optCount; ++i) {
        auto jOpt = static_cast<jstring>(env->GetObjectArrayElement(jJvmOptions, i));
        const char* cOpt = env->GetStringUTFChars(jOpt, nullptr);
        optStrings.emplace_back(cOpt ? cOpt : "");
        if (cOpt) env->ReleaseStringUTFChars(jOpt, cOpt);
        env->DeleteLocalRef(jOpt);
        options[static_cast<size_t>(i)].optionString = const_cast<char*>(optStrings.back().c_str());
        options[static_cast<size_t>(i)].extraInfo = nullptr;
    }

    JavaVMInitArgsCompat vmArgs{};
    vmArgs.version = JNI_VERSION_1_6;
    vmArgs.nOptions = optCount;
    vmArgs.options = options.data();
    vmArgs.ignoreUnrecognized = JNI_TRUE;

    JavaVM* targetVm = nullptr;
    JNIEnv* targetEnv = nullptr;
    jint createRes = createJavaVm(&targetVm, reinterpret_cast<void**>(&targetEnv), &vmArgs);
    if (createRes != JNI_OK || !targetVm || !targetEnv) {
        emitLog("[NoxerNative] JNI_CreateJavaVM failed with code " + std::to_string(createRes), true);
        if (savedStdout >= 0) { dup2(savedStdout, STDOUT_FILENO); close(savedStdout); }
        if (savedStderr >= 0) { dup2(savedStderr, STDERR_FILENO); close(savedStderr); }
        env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        env->ReleaseStringUTFChars(jMainClass, mainClassRaw);
        return createRes != 0 ? createRes : -104;
    }

    std::string classSlash(mainClassRaw);
    for (char& ch : classSlash) {
        if (ch == '.') ch = '/';
    }

    jclass mainCls = targetEnv->FindClass(classSlash.c_str());
    if (!mainCls) {
        if (targetEnv->ExceptionCheck()) targetEnv->ExceptionDescribe();
        emitLog("[NoxerNative] Could not find main class: " + classSlash, true);
        targetVm->DestroyJavaVM();
        if (savedStdout >= 0) { dup2(savedStdout, STDOUT_FILENO); close(savedStdout); }
        if (savedStderr >= 0) { dup2(savedStderr, STDERR_FILENO); close(savedStderr); }
        env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        env->ReleaseStringUTFChars(jMainClass, mainClassRaw);
        return -105;
    }

    jmethodID mainMethod = targetEnv->GetStaticMethodID(mainCls, "main", "([Ljava/lang/String;)V");
    if (!mainMethod) {
        emitLog("[NoxerNative] main(String[]) not found in " + classSlash, true);
        targetVm->DestroyJavaVM();
        if (savedStdout >= 0) { dup2(savedStdout, STDOUT_FILENO); close(savedStdout); }
        if (savedStderr >= 0) { dup2(savedStderr, STDERR_FILENO); close(savedStderr); }
        env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
        env->ReleaseStringUTFChars(jMainClass, mainClassRaw);
        return -106;
    }

    jsize argCount = env->GetArrayLength(jGameArgs);
    jclass stringCls = targetEnv->FindClass("java/lang/String");
    jobjectArray targetArgs = targetEnv->NewObjectArray(argCount, stringCls, nullptr);
    for (jsize i = 0; i < argCount; ++i) {
        auto jArg = static_cast<jstring>(env->GetObjectArrayElement(jGameArgs, i));
        const char* cArg = env->GetStringUTFChars(jArg, nullptr);
        jstring tArg = targetEnv->NewStringUTF(cArg ? cArg : "");
        targetEnv->SetObjectArrayElement(targetArgs, i, tArg);
        targetEnv->DeleteLocalRef(tArg);
        if (cArg) env->ReleaseStringUTFChars(jArg, cArg);
        env->DeleteLocalRef(jArg);
    }

    targetEnv->CallStaticVoidMethod(mainCls, mainMethod, targetArgs);
    jint exitCode = 0;
    if (targetEnv->ExceptionCheck()) {
        targetEnv->ExceptionDescribe();
        targetEnv->ExceptionClear();
        exitCode = -107;
    }

    targetVm->DestroyJavaVM();
    if (savedStdout >= 0) { dup2(savedStdout, STDOUT_FILENO); close(savedStdout); }
    if (savedStderr >= 0) { dup2(savedStderr, STDERR_FILENO); close(savedStderr); }
    env->ReleaseStringUTFChars(jLibJvmPath, libJvmPath);
    env->ReleaseStringUTFChars(jMainClass, mainClassRaw);
    return exitCode;
}
