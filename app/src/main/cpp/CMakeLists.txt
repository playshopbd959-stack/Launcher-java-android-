cmake_minimum_required(VERSION 3.22.1)
project("noxer_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)

add_library(
    noxer_native
    SHARED
    noxer_native.cpp
)

target_link_libraries(
    noxer_native
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv3-lib}
    dl
)

add_library(
    glfw
    SHARED
    noxer_glfw_bridge.cpp
)

target_link_libraries(
    glfw
    noxer_native
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv3-lib}
    dl
)

add_library(
    GL
    SHARED
    noxer_gl_bridge.cpp
)

target_link_libraries(
    GL
    ${log-lib}
    ${egl-lib}
    ${glesv3-lib}
    dl
)
