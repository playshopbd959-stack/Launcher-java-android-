#pragma once

#include <EGL/egl.h>
#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((visibility("default"))) EGLDisplay noxerGetEglDisplay();
__attribute__((visibility("default"))) EGLSurface noxerGetEglSurface();
__attribute__((visibility("default"))) EGLContext noxerGetEglContext();
__attribute__((visibility("default"))) EGLConfig noxerGetEglConfig();
__attribute__((visibility("default"))) ANativeWindow* noxerGetNativeWindow();
__attribute__((visibility("default"))) void noxerGetWindowSize(int* outW, int* outH);
__attribute__((visibility("default"))) void noxerSetGameOwnsEgl(int owns);
__attribute__((visibility("default"))) int noxerIsGameOwnsEgl();
__attribute__((visibility("default"))) int noxerMakeEglCurrentOnCallingThread();
__attribute__((visibility("default"))) int noxerReleaseEglOnCallingThread();
__attribute__((visibility("default"))) int noxerSwapBuffers();
__attribute__((visibility("default"))) int noxerWaitForWindowReady(int timeoutMs);
__attribute__((visibility("default"))) int noxerPopInputEvent(
    int* outType,
    int* outCode,
    int* outAction,
    float* outX,
    float* outY,
    int* outModifiers);

#ifdef __cplusplus
}
#endif
