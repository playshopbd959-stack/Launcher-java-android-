#include "noxer_egl_shared.h"
#include <EGL/egl.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <unordered_map>

#define GLFW_LOG_TAG "NoxerGLFW"
#define GLFWLOGI(...) __android_log_print(ANDROID_LOG_INFO, GLFW_LOG_TAG, __VA_ARGS__)
#define GLFWLOGE(...) __android_log_print(ANDROID_LOG_ERROR, GLFW_LOG_TAG, __VA_ARGS__)

namespace {

constexpr int GLFW_TRUE_VAL = 1;
constexpr int GLFW_FALSE_VAL = 0;

constexpr int GLFW_VERSION_MAJOR_VAL = 3;
constexpr int GLFW_VERSION_MINOR_VAL = 3;
constexpr int GLFW_VERSION_REVISION_VAL = 9;

constexpr int GLFW_FOCUSED = 0x00020001;
constexpr int GLFW_ICONIFIED = 0x00020002;
constexpr int GLFW_RESIZABLE = 0x00020003;
constexpr int GLFW_VISIBLE = 0x00020004;
constexpr int GLFW_DECORATED = 0x00020005;
constexpr int GLFW_AUTO_ICONIFY = 0x00020006;
constexpr int GLFW_FLOATING = 0x00020007;
constexpr int GLFW_MAXIMIZED = 0x00020008;
constexpr int GLFW_HOVERED = 0x0002000B;

constexpr int GLFW_CLIENT_API = 0x00022001;
constexpr int GLFW_CONTEXT_VERSION_MAJOR = 0x00022002;
constexpr int GLFW_CONTEXT_VERSION_MINOR = 0x00022003;
constexpr int GLFW_CONTEXT_REVISION = 0x00022004;
constexpr int GLFW_OPENGL_PROFILE = 0x00022008;
constexpr int GLFW_OPENGL_FORWARD_COMPAT = 0x00022006;
constexpr int GLFW_CONTEXT_CREATION_API = 0x0002200B;

constexpr int GLFW_OPENGL_API = 0x00030001;
constexpr int GLFW_EGL_CONTEXT_API = 0x00036002;
constexpr int GLFW_CURSOR_MODE = 0x00033001;
constexpr int GLFW_CURSOR_NORMAL = 0x00034001;
constexpr int GLFW_CURSOR_DISABLED = 0x00034003;
constexpr int GLFW_PLATFORM_X11 = 0x00060004;

struct GLFWvidmode {
    int width;
    int height;
    int redBits;
    int greenBits;
    int blueBits;
    int refreshRate;
};

struct GLFWgammaramp {
    unsigned short* red;
    unsigned short* green;
    unsigned short* blue;
    unsigned int size;
};

struct GLFWimage {
    int width;
    int height;
    unsigned char* pixels;
};

struct GLFWgamepadstate {
    unsigned char buttons[15];
    float axes[6];
};

struct GLFWmonitor {
    int id = 1;
    void* userPointer = nullptr;
    std::string name = "Noxer Android Display";
    GLFWvidmode mode{1280, 720, 8, 8, 8, 60};
};

struct GLFWwindow {
    int width = 1280;
    int height = 720;
    int shouldClose = 0;
    int focused = 1;
    int visible = 1;
    int iconified = 0;
    int maximized = 1;
    int cursorMode = GLFW_CURSOR_NORMAL;
    int clientApi = GLFW_OPENGL_API;
    int ctxMajor = 3;
    int ctxMinor = 2;
    void* userPointer = nullptr;
    std::string title = "Minecraft";
    std::string clipboard;
    double cursorX = 640.0;
    double cursorY = 360.0;
    uint8_t keyStates[512]{};
    uint8_t mouseStates[16]{};

    void (*windowPosCb)(GLFWwindow*, int, int) = nullptr;
    void (*windowSizeCb)(GLFWwindow*, int, int) = nullptr;
    void (*windowCloseCb)(GLFWwindow*) = nullptr;
    void (*windowRefreshCb)(GLFWwindow*) = nullptr;
    void (*windowFocusCb)(GLFWwindow*, int) = nullptr;
    void (*windowIconifyCb)(GLFWwindow*, int) = nullptr;
    void (*windowMaximizeCb)(GLFWwindow*, int) = nullptr;
    void (*framebufferSizeCb)(GLFWwindow*, int, int) = nullptr;
    void (*windowContentScaleCb)(GLFWwindow*, float, float) = nullptr;
    void (*keyCb)(GLFWwindow*, int, int, int, int) = nullptr;
    void (*charCb)(GLFWwindow*, unsigned int) = nullptr;
    void (*charModsCb)(GLFWwindow*, unsigned int, int) = nullptr;
    void (*mouseButtonCb)(GLFWwindow*, int, int, int) = nullptr;
    void (*cursorPosCb)(GLFWwindow*, double, double) = nullptr;
    void (*cursorEnterCb)(GLFWwindow*, int) = nullptr;
    void (*scrollCb)(GLFWwindow*, double, double) = nullptr;
    void (*dropCb)(GLFWwindow*, int, const char**) = nullptr;
};

struct GLFWcursor {
    int shape = 0;
};

using GlfwErrorFun = void (*)(int, const char*);
using GlfwMonitorFun = void (*)(GLFWmonitor*, int);
using GlfwJoystickFun = void (*)(int, int);
using ResolveSymFn = void* (*)(const char*);

std::mutex g_glfwMutex;
bool g_initialized = false;
int g_lastErrorCode = 0;
std::string g_lastErrorDesc;
GlfwErrorFun g_errorCb = nullptr;
GlfwMonitorFun g_monitorCb = nullptr;
GlfwJoystickFun g_joystickCb = nullptr;
GLFWmonitor g_primaryMonitor;
GLFWwindow* g_activeWindow = nullptr;
GLFWwindow* g_currentContextWindow = nullptr;
std::unordered_map<int, int> g_windowHints;
int64_t g_startTimeNs = 0;
double g_timeOffsetSec = 0.0;
void* g_libGlHandle = nullptr;
ResolveSymFn g_resolveGlSymbolFn = nullptr;
std::string g_clipboardGlobal;
unsigned short g_gammaR[256];
unsigned short g_gammaG[256];
unsigned short g_gammaB[256];
GLFWgammaramp g_gammaRamp{g_gammaR, g_gammaG, g_gammaB, 256};

int64_t monotonicNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

unsigned int glfwKeyToUnicode(int glfwKey, int mods) {
    const bool shift = (mods & 0x0001) != 0;
    if (glfwKey >= 65 && glfwKey <= 90) { // A..Z
        return shift ? static_cast<unsigned int>(glfwKey) : static_cast<unsigned int>(glfwKey + 32);
    }
    if (glfwKey >= 48 && glfwKey <= 57) { // 0..9
        return static_cast<unsigned int>(glfwKey);
    }
    if (glfwKey == 32) return ' ';
    if (glfwKey == 46) return '.';
    if (glfwKey == 58) return ':';
    return 0;
}

void ensureGlBridgeLoaded() {
    if (!g_libGlHandle) {
        g_libGlHandle = dlopen("libGL.so", RTLD_LAZY | RTLD_GLOBAL);
        if (g_libGlHandle) {
            g_resolveGlSymbolFn = reinterpret_cast<ResolveSymFn>(dlsym(g_libGlHandle, "noxerResolveGlSymbol"));
        }
    }
}

void processPendingEventsLocked() {
    GLFWwindow* win = g_activeWindow;
    if (!win) return;

    // Update framebuffer/window size dynamically if surface resized
    int w = 0, h = 0;
    noxerGetWindowSize(&w, &h);
    if (w > 0 && h > 0 && (w != win->width || h != win->height)) {
        win->width = w;
        win->height = h;
        g_primaryMonitor.mode.width = w;
        g_primaryMonitor.mode.height = h;
        if (win->windowSizeCb) win->windowSizeCb(win, w, h);
        if (win->framebufferSizeCb) win->framebufferSizeCb(win, w, h);
    }

    int type = 0, code = 0, action = 0, modifiers = 0;
    float x = 0.0f, y = 0.0f;
    while (noxerPopInputEvent(&type, &code, &action, &x, &y, &modifiers)) {
        if (type == 1) { // Key
            if (code >= 0 && code < 512) {
                win->keyStates[code] = (action != 0) ? 1 : 0;
            }
            if (win->keyCb) {
                win->keyCb(win, code, code, action, modifiers);
            }
            if (action == 1 && win->charCb) {
                unsigned int cp = glfwKeyToUnicode(code, modifiers);
                if (cp != 0) win->charCb(win, cp);
            }
        } else if (type == 2) { // Mouse button
            if (code >= 0 && code < 16) {
                win->mouseStates[code] = (action != 0) ? 1 : 0;
            }
            if (win->mouseButtonCb) {
                win->mouseButtonCb(win, code, action, modifiers);
            }
        } else if (type == 3) { // Mouse move / delta
            if (win->cursorMode == GLFW_CURSOR_DISABLED) {
                win->cursorX += static_cast<double>(x);
                win->cursorY += static_cast<double>(y);
            } else {
                win->cursorX = static_cast<double>(x);
                win->cursorY = static_cast<double>(y);
            }
            if (win->cursorPosCb) {
                win->cursorPosCb(win, win->cursorX, win->cursorY);
            }
        } else if (type == 4) { // Scroll
            if (win->scrollCb) {
                win->scrollCb(win, static_cast<double>(x), static_cast<double>(y));
            }
        }
    }
}

} // namespace

extern "C" {

__attribute__((visibility("default"))) int glfwInit() {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    g_initialized = true;
    g_startTimeNs = monotonicNs();
    g_timeOffsetSec = 0.0;
    g_lastErrorCode = 0;
    for (unsigned int i = 0; i < 256; ++i) {
        unsigned short v = static_cast<unsigned short>((i * 65535u) / 255u);
        g_gammaR[i] = v;
        g_gammaG[i] = v;
        g_gammaB[i] = v;
    }
    int w = 0, h = 0;
    noxerGetWindowSize(&w, &h);
    if (w > 0 && h > 0) {
        g_primaryMonitor.mode.width = w;
        g_primaryMonitor.mode.height = h;
    }
    ensureGlBridgeLoaded();
    GLFWLOGI("glfwInit completed (display=%dx%d)", g_primaryMonitor.mode.width, g_primaryMonitor.mode.height);
    return GLFW_TRUE_VAL;
}

__attribute__((visibility("default"))) void glfwTerminate() {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    noxerReleaseEglOnCallingThread();
    noxerSetGameOwnsEgl(0);
    g_currentContextWindow = nullptr;
    delete g_activeWindow;
    g_activeWindow = nullptr;
    g_initialized = false;
    GLFWLOGI("glfwTerminate completed");
}

__attribute__((visibility("default"))) void glfwInitHint(int, int) {}

__attribute__((visibility("default"))) void glfwGetVersion(int* major, int* minor, int* rev) {
    if (major) *major = GLFW_VERSION_MAJOR_VAL;
    if (minor) *minor = GLFW_VERSION_MINOR_VAL;
    if (rev) *rev = GLFW_VERSION_REVISION_VAL;
}

__attribute__((visibility("default"))) const char* glfwGetVersionString() {
    return "3.3.9 NOXER Android EGL GLES3";
}

__attribute__((visibility("default"))) int glfwGetError(const char** description) {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    int code = g_lastErrorCode;
    if (description) {
        *description = code == 0 ? nullptr : g_lastErrorDesc.c_str();
    }
    g_lastErrorCode = 0;
    return code;
}

__attribute__((visibility("default"))) GlfwErrorFun glfwSetErrorCallback(GlfwErrorFun cb) {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    GlfwErrorFun prev = g_errorCb;
    g_errorCb = cb;
    return prev;
}

__attribute__((visibility("default"))) int glfwPlatformSupported(int platform) {
    return (platform == GLFW_PLATFORM_X11 || platform == 0x00060000) ? GLFW_TRUE_VAL : GLFW_FALSE_VAL;
}

__attribute__((visibility("default"))) int glfwGetPlatform() {
    return GLFW_PLATFORM_X11;
}

__attribute__((visibility("default"))) GLFWmonitor** glfwGetMonitors(int* count) {
    static GLFWmonitor* monitors[] = {&g_primaryMonitor};
    if (count) *count = 1;
    return monitors;
}

__attribute__((visibility("default"))) GLFWmonitor* glfwGetPrimaryMonitor() {
    return &g_primaryMonitor;
}

__attribute__((visibility("default"))) void glfwGetMonitorPos(GLFWmonitor*, int* xpos, int* ypos) {
    if (xpos) *xpos = 0;
    if (ypos) *ypos = 0;
}

__attribute__((visibility("default"))) void glfwGetMonitorWorkarea(GLFWmonitor*, int* xpos, int* ypos, int* width, int* height) {
    if (xpos) *xpos = 0;
    if (ypos) *ypos = 0;
    if (width) *width = g_primaryMonitor.mode.width;
    if (height) *height = g_primaryMonitor.mode.height;
}

__attribute__((visibility("default"))) void glfwGetMonitorPhysicalSize(GLFWmonitor*, int* widthMM, int* heightMM) {
    if (widthMM) *widthMM = 140;
    if (heightMM) *heightMM = 78;
}

__attribute__((visibility("default"))) void glfwGetMonitorContentScale(GLFWmonitor*, float* xscale, float* yscale) {
    if (xscale) *xscale = 1.0f;
    if (yscale) *yscale = 1.0f;
}

__attribute__((visibility("default"))) const char* glfwGetMonitorName(GLFWmonitor* monitor) {
    return monitor ? monitor->name.c_str() : "Noxer Display";
}

__attribute__((visibility("default"))) void glfwSetMonitorUserPointer(GLFWmonitor* monitor, void* ptr) {
    if (monitor) monitor->userPointer = ptr;
}

__attribute__((visibility("default"))) void* glfwGetMonitorUserPointer(GLFWmonitor* monitor) {
    return monitor ? monitor->userPointer : nullptr;
}

__attribute__((visibility("default"))) GlfwMonitorFun glfwSetMonitorCallback(GlfwMonitorFun cb) {
    GlfwMonitorFun prev = g_monitorCb;
    g_monitorCb = cb;
    return prev;
}

__attribute__((visibility("default"))) const GLFWvidmode* glfwGetVideoModes(GLFWmonitor*, int* count) {
    if (count) *count = 1;
    return &g_primaryMonitor.mode;
}

__attribute__((visibility("default"))) const GLFWvidmode* glfwGetVideoMode(GLFWmonitor*) {
    int w = 0, h = 0;
    noxerGetWindowSize(&w, &h);
    if (w > 0 && h > 0) {
        g_primaryMonitor.mode.width = w;
        g_primaryMonitor.mode.height = h;
    }
    return &g_primaryMonitor.mode;
}

__attribute__((visibility("default"))) void glfwSetGamma(GLFWmonitor*, float) {}
__attribute__((visibility("default"))) const GLFWgammaramp* glfwGetGammaRamp(GLFWmonitor*) { return &g_gammaRamp; }
__attribute__((visibility("default"))) void glfwSetGammaRamp(GLFWmonitor*, const GLFWgammaramp*) {}

__attribute__((visibility("default"))) void glfwDefaultWindowHints() {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    g_windowHints.clear();
}

__attribute__((visibility("default"))) void glfwWindowHint(int hint, int value) {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    g_windowHints[hint] = value;
}

__attribute__((visibility("default"))) void glfwWindowHintString(int, const char*) {}

__attribute__((visibility("default"))) GLFWwindow* glfwCreateWindow(
    int width, int height, const char* title, GLFWmonitor*, GLFWwindow*) {
    if (!noxerWaitForWindowReady(15000)) {
        GLFWLOGE("glfwCreateWindow timed out waiting for Android SurfaceView / EGL window!");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    int realW = 0, realH = 0;
    noxerGetWindowSize(&realW, &realH);

    auto* win = new GLFWwindow();
    win->width = realW > 0 ? realW : (width > 0 ? width : 1280);
    win->height = realH > 0 ? realH : (height > 0 ? height : 720);
    win->title = title ? title : "Minecraft";
    win->cursorX = win->width * 0.5;
    win->cursorY = win->height * 0.5;
    if (auto it = g_windowHints.find(GLFW_CONTEXT_VERSION_MAJOR); it != g_windowHints.end()) {
        win->ctxMajor = it->second;
    }
    if (auto it = g_windowHints.find(GLFW_CONTEXT_VERSION_MINOR); it != g_windowHints.end()) {
        win->ctxMinor = it->second;
    }

    g_primaryMonitor.mode.width = win->width;
    g_primaryMonitor.mode.height = win->height;
    g_activeWindow = win;
    GLFWLOGI("glfwCreateWindow created %dx%d '%s'", win->width, win->height, win->title.c_str());
    return win;
}

__attribute__((visibility("default"))) void glfwDestroyWindow(GLFWwindow* window) {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    if (!window) return;
    if (g_currentContextWindow == window) {
        noxerReleaseEglOnCallingThread();
        noxerSetGameOwnsEgl(0);
        g_currentContextWindow = nullptr;
    }
    if (g_activeWindow == window) {
        g_activeWindow = nullptr;
    }
    delete window;
}

__attribute__((visibility("default"))) int glfwWindowShouldClose(GLFWwindow* window) {
    return window ? window->shouldClose : GLFW_TRUE_VAL;
}

__attribute__((visibility("default"))) void glfwSetWindowShouldClose(GLFWwindow* window, int value) {
    if (window) window->shouldClose = value;
}

__attribute__((visibility("default"))) void glfwSetWindowTitle(GLFWwindow* window, const char* title) {
    if (window && title) window->title = title;
}

__attribute__((visibility("default"))) void glfwSetWindowIcon(GLFWwindow*, int, const GLFWimage*) {}

__attribute__((visibility("default"))) void glfwGetWindowPos(GLFWwindow*, int* xpos, int* ypos) {
    if (xpos) *xpos = 0;
    if (ypos) *ypos = 0;
}

__attribute__((visibility("default"))) void glfwSetWindowPos(GLFWwindow*, int, int) {}

__attribute__((visibility("default"))) void glfwGetWindowSize(GLFWwindow* window, int* width, int* height) {
    int w = 0, h = 0;
    noxerGetWindowSize(&w, &h);
    if (w > 0 && h > 0 && window) {
        window->width = w;
        window->height = h;
    }
    if (width) *width = window ? window->width : w;
    if (height) *height = window ? window->height : h;
}

__attribute__((visibility("default"))) void glfwSetWindowSize(GLFWwindow*, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowSizeLimits(GLFWwindow*, int, int, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowAspectRatio(GLFWwindow*, int, int) {}

__attribute__((visibility("default"))) void glfwGetFramebufferSize(GLFWwindow* window, int* width, int* height) {
    glfwGetWindowSize(window, width, height);
}

__attribute__((visibility("default"))) void glfwGetWindowFrameSize(GLFWwindow*, int* left, int* top, int* right, int* bottom) {
    if (left) *left = 0;
    if (top) *top = 0;
    if (right) *right = 0;
    if (bottom) *bottom = 0;
}

__attribute__((visibility("default"))) void glfwGetWindowContentScale(GLFWmonitor*, float* xscale, float* yscale) {
    if (xscale) *xscale = 1.0f;
    if (yscale) *yscale = 1.0f;
}

__attribute__((visibility("default"))) float glfwGetWindowOpacity(GLFWwindow*) { return 1.0f; }
__attribute__((visibility("default"))) void glfwSetWindowOpacity(GLFWwindow*, float) {}
__attribute__((visibility("default"))) void glfwIconifyWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwRestoreWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwMaximizeWindow(GLFWwindow*) {}
__attribute__((visibility("default"))) void glfwShowWindow(GLFWwindow* window) { if (window) window->visible = 1; }
__attribute__((visibility("default"))) void glfwHideWindow(GLFWwindow* window) { if (window) window->visible = 0; }
__attribute__((visibility("default"))) void glfwFocusWindow(GLFWwindow* window) { if (window) window->focused = 1; }
__attribute__((visibility("default"))) void glfwRequestWindowAttention(GLFWwindow*) {}
__attribute__((visibility("default"))) GLFWmonitor* glfwGetWindowMonitor(GLFWwindow*) { return &g_primaryMonitor; }
__attribute__((visibility("default"))) void glfwSetWindowMonitor(GLFWwindow*, GLFWmonitor*, int, int, int, int, int) {}

__attribute__((visibility("default"))) int glfwGetWindowAttrib(GLFWwindow* window, int attrib) {
    if (!window) return 0;
    switch (attrib) {
        case GLFW_FOCUSED: return window->focused;
        case GLFW_ICONIFIED: return window->iconified;
        case GLFW_VISIBLE: return window->visible;
        case GLFW_MAXIMIZED: return window->maximized;
        case GLFW_RESIZABLE: return 1;
        case GLFW_DECORATED: return 0;
        case GLFW_HOVERED: return 1;
        case GLFW_CLIENT_API: return window->clientApi;
        case GLFW_CONTEXT_VERSION_MAJOR: return window->ctxMajor;
        case GLFW_CONTEXT_VERSION_MINOR: return window->ctxMinor;
        case GLFW_CONTEXT_REVISION: return 0;
        case GLFW_CONTEXT_CREATION_API: return GLFW_EGL_CONTEXT_API;
        default: return 0;
    }
}

__attribute__((visibility("default"))) void glfwSetWindowAttrib(GLFWwindow*, int, int) {}
__attribute__((visibility("default"))) void glfwSetWindowUserPointer(GLFWwindow* window, void* ptr) { if (window) window->userPointer = ptr; }
__attribute__((visibility("default"))) void* glfwGetWindowUserPointer(GLFWwindow* window) { return window ? window->userPointer : nullptr; }

__attribute__((visibility("default"))) void glfwPollEvents() {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    processPendingEventsLocked();
}

__attribute__((visibility("default"))) void glfwWaitEvents() { glfwPollEvents(); }
__attribute__((visibility("default"))) void glfwWaitEventsTimeout(double) { glfwPollEvents(); }
__attribute__((visibility("default"))) void glfwPostEmptyEvent() {}

__attribute__((visibility("default"))) int glfwGetInputMode(GLFWwindow* window, int mode) {
    if (!window) return 0;
    if (mode == GLFW_CURSOR_MODE) return window->cursorMode;
    return 0;
}

__attribute__((visibility("default"))) void glfwSetInputMode(GLFWwindow* window, int mode, int value) {
    if (!window) return;
    if (mode == GLFW_CURSOR_MODE) {
        window->cursorMode = value;
    }
}

__attribute__((visibility("default"))) int glfwRawMouseMotionSupported() { return GLFW_TRUE_VAL; }
__attribute__((visibility("default"))) const char* glfwGetKeyName(int, int) { return nullptr; }
__attribute__((visibility("default"))) int glfwGetKeyScancode(int key) { return key; }

__attribute__((visibility("default"))) int glfwGetKey(GLFWwindow* window, int key) {
    if (!window || key < 0 || key >= 512) return 0;
    return window->keyStates[key];
}

__attribute__((visibility("default"))) int glfwGetMouseButton(GLFWwindow* window, int button) {
    if (!window || button < 0 || button >= 16) return 0;
    return window->mouseStates[button];
}

__attribute__((visibility("default"))) void glfwGetCursorPos(GLFWwindow* window, double* xpos, double* ypos) {
    if (xpos) *xpos = window ? window->cursorX : 0.0;
    if (ypos) *ypos = window ? window->cursorY : 0.0;
}

__attribute__((visibility("default"))) void glfwSetCursorPos(GLFWwindow* window, double xpos, double ypos) {
    if (window) {
        window->cursorX = xpos;
        window->cursorY = ypos;
    }
}

__attribute__((visibility("default"))) GLFWcursor* glfwCreateCursor(const GLFWimage*, int, int) { return new GLFWcursor(); }
__attribute__((visibility("default"))) GLFWcursor* glfwCreateStandardCursor(int shape) { return new GLFWcursor{shape}; }
__attribute__((visibility("default"))) void glfwDestroyCursor(GLFWcursor* cursor) { delete cursor; }
__attribute__((visibility("default"))) void glfwSetCursor(GLFWwindow*, GLFWcursor*) {}

#define DEFINE_GLFW_CB(Name, Field, ...) \
    using FnType_##Name = __VA_ARGS__; \
    __attribute__((visibility("default"))) FnType_##Name Name(GLFWwindow* window, FnType_##Name cb) { \
        if (!window) return nullptr; \
        FnType_##Name prev = window->Field; \
        window->Field = cb; \
        return prev; \
    }

DEFINE_GLFW_CB(glfwSetWindowPosCallback, windowPosCb, void (*)(GLFWwindow*, int, int))
DEFINE_GLFW_CB(glfwSetWindowSizeCallback, windowSizeCb, void (*)(GLFWwindow*, int, int))
DEFINE_GLFW_CB(glfwSetWindowCloseCallback, windowCloseCb, void (*)(GLFWwindow*))
DEFINE_GLFW_CB(glfwSetWindowRefreshCallback, windowRefreshCb, void (*)(GLFWwindow*))
DEFINE_GLFW_CB(glfwSetWindowFocusCallback, windowFocusCb, void (*)(GLFWwindow*, int))
DEFINE_GLFW_CB(glfwSetWindowIconifyCallback, windowIconifyCb, void (*)(GLFWwindow*, int))
DEFINE_GLFW_CB(glfwSetWindowMaximizeCallback, windowMaximizeCb, void (*)(GLFWwindow*, int))
DEFINE_GLFW_CB(glfwSetFramebufferSizeCallback, framebufferSizeCb, void (*)(GLFWwindow*, int, int))
DEFINE_GLFW_CB(glfwSetWindowContentScaleCallback, windowContentScaleCb, void (*)(GLFWwindow*, float, float))
DEFINE_GLFW_CB(glfwSetKeyCallback, keyCb, void (*)(GLFWwindow*, int, int, int, int))
DEFINE_GLFW_CB(glfwSetCharCallback, charCb, void (*)(GLFWwindow*, unsigned int))
DEFINE_GLFW_CB(glfwSetCharModsCallback, charModsCb, void (*)(GLFWwindow*, unsigned int, int))
DEFINE_GLFW_CB(glfwSetMouseButtonCallback, mouseButtonCb, void (*)(GLFWwindow*, int, int, int))
DEFINE_GLFW_CB(glfwSetCursorPosCallback, cursorPosCb, void (*)(GLFWwindow*, double, double))
DEFINE_GLFW_CB(glfwSetCursorEnterCallback, cursorEnterCb, void (*)(GLFWwindow*, int))
DEFINE_GLFW_CB(glfwSetScrollCallback, scrollCb, void (*)(GLFWwindow*, double, double))
DEFINE_GLFW_CB(glfwSetDropCallback, dropCb, void (*)(GLFWwindow*, int, const char**))

__attribute__((visibility("default"))) void glfwMakeContextCurrent(GLFWwindow* window) {
    if (window) {
        noxerSetGameOwnsEgl(1);
        if (noxerMakeEglCurrentOnCallingThread()) {
            g_currentContextWindow = window;
        } else {
            GLFWLOGE("glfwMakeContextCurrent failed to bind EGL context on calling thread");
        }
    } else {
        noxerReleaseEglOnCallingThread();
        noxerSetGameOwnsEgl(0);
        g_currentContextWindow = nullptr;
    }
}

__attribute__((visibility("default"))) GLFWwindow* glfwGetCurrentContext() {
    return g_currentContextWindow;
}

__attribute__((visibility("default"))) void glfwSwapBuffers(GLFWwindow*) {
    noxerSwapBuffers();
}

__attribute__((visibility("default"))) void glfwSwapInterval(int interval) {
    EGLDisplay dpy = noxerGetEglDisplay();
    if (dpy != EGL_NO_DISPLAY) {
        eglSwapInterval(dpy, interval);
    }
}

__attribute__((visibility("default"))) int glfwExtensionSupported(const char* extension) {
    if (!extension) return GLFW_FALSE_VAL;
    return GLFW_TRUE_VAL;
}

__attribute__((visibility("default"))) void* glfwGetProcAddress(const char* procname) {
    if (!procname || procname[0] == '\0') return nullptr;
    ensureGlBridgeLoaded();
    if (g_resolveGlSymbolFn) {
        void* sym = g_resolveGlSymbolFn(procname);
        if (sym) return sym;
    }
    void* sym = reinterpret_cast<void*>(eglGetProcAddress(procname));
    if (sym) return sym;
    return dlsym(RTLD_DEFAULT, procname);
}

__attribute__((visibility("default"))) void glfwSetClipboardString(GLFWwindow* window, const char* str) {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    g_clipboardGlobal = str ? str : "";
    if (window) window->clipboard = g_clipboardGlobal;
}

__attribute__((visibility("default"))) const char* glfwGetClipboardString(GLFWwindow* window) {
    std::lock_guard<std::mutex> lock(g_glfwMutex);
    if (window && !window->clipboard.empty()) return window->clipboard.c_str();
    return g_clipboardGlobal.c_str();
}

__attribute__((visibility("default"))) double glfwGetTime() {
    int64_t elapsed = monotonicNs() - g_startTimeNs;
    return g_timeOffsetSec + (static_cast<double>(elapsed) / 1000000000.0);
}

__attribute__((visibility("default"))) void glfwSetTime(double time) {
    g_startTimeNs = monotonicNs();
    g_timeOffsetSec = time;
}

__attribute__((visibility("default"))) uint64_t glfwGetTimerValue() {
    return static_cast<uint64_t>(monotonicNs());
}

__attribute__((visibility("default"))) uint64_t glfwGetTimerFrequency() {
    return 1000000000ULL;
}

__attribute__((visibility("default"))) int glfwJoystickPresent(int) { return GLFW_FALSE_VAL; }
__attribute__((visibility("default"))) const float* glfwGetJoystickAxes(int, int* count) { if (count) *count = 0; return nullptr; }
__attribute__((visibility("default"))) const unsigned char* glfwGetJoystickButtons(int, int* count) { if (count) *count = 0; return nullptr; }
__attribute__((visibility("default"))) const unsigned char* glfwGetJoystickHats(int, int* count) { if (count) *count = 0; return nullptr; }
__attribute__((visibility("default"))) const char* glfwGetJoystickName(int) { return nullptr; }
__attribute__((visibility("default"))) const char* glfwGetJoystickGUID(int) { return nullptr; }
__attribute__((visibility("default"))) void glfwSetJoystickUserPointer(int, void*) {}
__attribute__((visibility("default"))) void* glfwGetJoystickUserPointer(int) { return nullptr; }
__attribute__((visibility("default"))) int glfwJoystickIsGamepad(int) { return GLFW_FALSE_VAL; }
__attribute__((visibility("default"))) GlfwJoystickFun glfwSetJoystickCallback(GlfwJoystickFun cb) { GlfwJoystickFun p = g_joystickCb; g_joystickCb = cb; return p; }
__attribute__((visibility("default"))) int glfwUpdateGamepadMappings(const char*) { return GLFW_TRUE_VAL; }
__attribute__((visibility("default"))) const char* glfwGetGamepadName(int) { return nullptr; }
__attribute__((visibility("default"))) int glfwGetGamepadState(int, GLFWgamepadstate* state) {
    if (state) memset(state, 0, sizeof(*state));
    return GLFW_FALSE_VAL;
}

__attribute__((visibility("default"))) int glfwVulkanSupported() { return GLFW_FALSE_VAL; }
__attribute__((visibility("default"))) const char** glfwGetRequiredInstanceExtensions(uint32_t* count) {
    if (count) *count = 0;
    return nullptr;
}

} // extern "C"
