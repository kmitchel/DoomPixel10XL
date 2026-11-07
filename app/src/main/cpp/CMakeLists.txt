cmake_minimum_required(VERSION 3.22.1)

project("doom" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Preprocessor definitions for DOOM
add_definitions(
    -DDOOMGENERIC_RESX=640
    -DDOOMGENERIC_RESY=400
    -D__ANDROID__
    -DFEATURE_SOUND
)

# Collect doomgeneric engine source files
file(GLOB DOOM_ENGINE_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/doomgeneric/*.c"
)

# Platform JNI sources
set(DOOM_PLATFORM_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/doomgeneric_android.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/doom_jni.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni/doom_audio_android.cpp"
)

# Create shared library libdoom.so
add_library(doom SHARED
    ${DOOM_ENGINE_SOURCES}
    ${DOOM_PLATFORM_SOURCES}
)

target_include_directories(doom PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/doomgeneric"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni"
)

target_compile_options(doom PRIVATE -O3)

# Link native Android and system libraries
target_link_libraries(doom
    android
    aaudio
    log
    m
)
