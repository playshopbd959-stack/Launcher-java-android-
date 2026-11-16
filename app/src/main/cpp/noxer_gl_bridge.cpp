#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#define GL_LOG_TAG "NoxerGLBridge"
#define GLLOGI(...) __android_log_print(ANDROID_LOG_INFO, GL_LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_glResolveMutex;
void* g_glesHandle = nullptr;
void* g_gl4esHandle = nullptr;

void ensureGlBackendsLoaded() {
    std::lock_guard<std::mutex> lock(g_glResolveMutex);
    if (!g_gl4esHandle) {
        const char* overrideLib = getenv("NOXER_EXTERNAL_GL");
        if (overrideLib && overrideLib[0] != '\0') {
            g_gl4esHandle = dlopen(overrideLib, RTLD_LAZY | RTLD_GLOBAL);
        }
        if (!g_gl4esHandle) {
            g_gl4esHandle = dlopen("libgl4es_114.so", RTLD_LAZY | RTLD_GLOBAL);
        }
    }
    if (!g_glesHandle) {
        g_glesHandle = dlopen("libGLESv3.so", RTLD_LAZY | RTLD_GLOBAL);
        if (!g_glesHandle) {
            g_glesHandle = dlopen("libGLESv2.so", RTLD_LAZY | RTLD_GLOBAL);
        }
    }
}

void* resolveRawGl(const char* name) {
    if (!name || name[0] == '\0') return nullptr;
    ensureGlBackendsLoaded();

    if (g_gl4esHandle) {
        void* sym = dlsym(g_gl4esHandle, name);
        if (sym) return sym;
    }
    if (g_glesHandle) {
        void* sym = dlsym(g_glesHandle, name);
        if (sym) return sym;
    }
    void* eglProc = reinterpret_cast<void*>(eglGetProcAddress(name));
    if (eglProc) return eglProc;

    return dlsym(RTLD_NEXT, name);
}

#ifndef GL_SHADING_LANGUAGE_VERSION
#define GL_SHADING_LANGUAGE_VERSION 0x8B8C
#endif

// Compat signatures for desktop GL entry points not always present as static symbols in NDK GLES3
typedef const GLubyte* (*GlGetStringFn)(GLenum);
typedef const GLubyte* (*GlGetStringiFn)(GLenum, GLuint);
typedef void (*GlGetIntegervFn)(GLenum, GLint*);
typedef GLenum (*GlGetErrorFn)();
typedef void (*GlVoidUIntIntPtrFn)(GLenum, GLsizei, GLenum, const void*, GLint);
typedef void (*GlMultiDrawArraysFn)(GLenum, const GLint*, const GLsizei*, GLsizei);
typedef void (*GlPolygonModeFn)(GLenum, GLenum);
typedef void (*GlClearDepthFn)(double);
typedef void (*GlDepthRangeFn)(double, double);

std::string g_versionOverrideCache;
std::string g_glslOverrideCache;

} // namespace

extern "C" {

__attribute__((visibility("default"))) void* noxerResolveGlSymbol(const char* name);

__attribute__((visibility("default"))) const GLubyte* glGetString(GLenum name) {
    auto realFn = reinterpret_cast<GlGetStringFn>(resolveRawGl("glGetString"));
    const GLubyte* raw = realFn ? realFn(name) : nullptr;

    const char* verOverride = getenv("MESA_GL_VERSION_OVERRIDE");
    if (name == GL_VERSION && verOverride && verOverride[0] != '\0') {
        g_versionOverrideCache = std::string(verOverride) + ".0 NOXER GLES3";
        return reinterpret_cast<const GLubyte*>(g_versionOverrideCache.c_str());
    }
    if (name == GL_SHADING_LANGUAGE_VERSION && verOverride && verOverride[0] != '\0') {
        float v = static_cast<float>(atof(verOverride));
        if (v >= 3.2f) {
            g_glslOverrideCache = "3.30 NOXER ES";
            return reinterpret_cast<const GLubyte*>(g_glslOverrideCache.c_str());
        }
    }
    return raw;
}

__attribute__((visibility("default"))) const GLubyte* glGetStringi(GLenum name, GLuint index) {
    auto fn = reinterpret_cast<GlGetStringiFn>(resolveRawGl("glGetStringi"));
    return fn ? fn(name, index) : nullptr;
}

__attribute__((visibility("default"))) void glPolygonMode(GLenum, GLenum) {
    // No-op compat shim on OpenGL ES 3.x where wireframe polygon mode isn't supported directly
}

__attribute__((visibility("default"))) void glClearDepth(double depth) {
    glClearDepthf(static_cast<GLfloat>(depth));
}

__attribute__((visibility("default"))) void glDepthRange(double nearVal, double farVal) {
    glDepthRangef(static_cast<GLfloat>(nearVal), static_cast<GLfloat>(farVal));
}

__attribute__((visibility("default"))) void glDrawElementsBaseVertex(
    GLenum mode, GLsizei count, GLenum type, const void* indices, GLint basevertex) {
    auto fn = reinterpret_cast<GlVoidUIntIntPtrFn>(resolveRawGl("glDrawElementsBaseVertex"));
    if (fn) {
        fn(mode, count, type, indices, basevertex);
        return;
    }
    auto extFn = reinterpret_cast<GlVoidUIntIntPtrFn>(
        reinterpret_cast<void*>(eglGetProcAddress("glDrawElementsBaseVertexEXT")));
    if (extFn) {
        extFn(mode, count, type, indices, basevertex);
        return;
    }
    glDrawElements(mode, count, type, indices);
}

__attribute__((visibility("default"))) void glMultiDrawArrays(
    GLenum mode, const GLint* first, const GLsizei* count, GLsizei drawcount) {
    auto fn = reinterpret_cast<GlMultiDrawArraysFn>(resolveRawGl("glMultiDrawArrays"));
    if (fn) {
        fn(mode, first, count, drawcount);
        return;
    }
    for (GLsizei i = 0; i < drawcount; ++i) {
        if (count[i] > 0) glDrawArrays(mode, first[i], count[i]);
    }
}

// GLX compatibility entry points expected by LWJGL / Linux OpenGL loaders
__attribute__((visibility("default"))) void* glXGetProcAddress(const GLubyte* procName) {
    return noxerResolveGlSymbol(reinterpret_cast<const char*>(procName));
}

__attribute__((visibility("default"))) void* glXGetProcAddressARB(const GLubyte* procName) {
    return noxerResolveGlSymbol(reinterpret_cast<const char*>(procName));
}

__attribute__((visibility("default"))) void* glXGetCurrentContext() {
    return reinterpret_cast<void*>(eglGetCurrentContext());
}

__attribute__((visibility("default"))) void* glXGetCurrentDisplay() {
    return reinterpret_cast<void*>(eglGetCurrentDisplay());
}

__attribute__((visibility("default"))) uintptr_t glXGetCurrentDrawable() {
    return reinterpret_cast<uintptr_t>(eglGetCurrentSurface(EGL_DRAW));
}

__attribute__((visibility("default"))) int glXQueryExtension(void*, int* errorBase, int* eventBase) {
    if (errorBase) *errorBase = 0;
    if (eventBase) *eventBase = 0;
    return 1;
}

__attribute__((visibility("default"))) int glXQueryVersion(void*, int* major, int* minor) {
    if (major) *major = 1;
    if (minor) *minor = 4;
    return 1;
}

__attribute__((visibility("default"))) void glXSwapIntervalEXT(void*, uintptr_t, int interval) {
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy != EGL_NO_DISPLAY) eglSwapInterval(dpy, interval);
}

__attribute__((visibility("default"))) int glXSwapIntervalMESA(unsigned int interval) {
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy != EGL_NO_DISPLAY) return eglSwapInterval(dpy, static_cast<EGLint>(interval)) ? 0 : -1;
    return 0;
}

__attribute__((visibility("default"))) void* noxerResolveGlSymbol(const char* name) {
    if (!name || name[0] == '\0') return nullptr;

    // Intercept our wrapped desktop-compat functions first
    if (strcmp(name, "glGetString") == 0) return reinterpret_cast<void*>(&glGetString);
    if (strcmp(name, "glGetStringi") == 0) return reinterpret_cast<void*>(&glGetStringi);
    if (strcmp(name, "glPolygonMode") == 0) return reinterpret_cast<void*>(&glPolygonMode);
    if (strcmp(name, "glClearDepth") == 0) return reinterpret_cast<void*>(&glClearDepth);
    if (strcmp(name, "glDepthRange") == 0) return reinterpret_cast<void*>(&glDepthRange);
    if (strcmp(name, "glDrawElementsBaseVertex") == 0) return reinterpret_cast<void*>(&glDrawElementsBaseVertex);
    if (strcmp(name, "glMultiDrawArrays") == 0) return reinterpret_cast<void*>(&glMultiDrawArrays);
    if (strcmp(name, "glXGetProcAddress") == 0) return reinterpret_cast<void*>(&glXGetProcAddress);
    if (strcmp(name, "glXGetProcAddressARB") == 0) return reinterpret_cast<void*>(&glXGetProcAddressARB);
    if (strcmp(name, "glXGetCurrentContext") == 0) return reinterpret_cast<void*>(&glXGetCurrentContext);
    if (strcmp(name, "glXGetCurrentDisplay") == 0) return reinterpret_cast<void*>(&glXGetCurrentDisplay);
    if (strcmp(name, "glXGetCurrentDrawable") == 0) return reinterpret_cast<void*>(&glXGetCurrentDrawable);
    if (strcmp(name, "glXQueryExtension") == 0) return reinterpret_cast<void*>(&glXQueryExtension);
    if (strcmp(name, "glXQueryVersion") == 0) return reinterpret_cast<void*>(&glXQueryVersion);
    if (strcmp(name, "glXSwapIntervalEXT") == 0) return reinterpret_cast<void*>(&glXSwapIntervalEXT);
    if (strcmp(name, "glXSwapIntervalMESA") == 0) return reinterpret_cast<void*>(&glXSwapIntervalMESA);

    return resolveRawGl(name);
}

} // extern "C"
