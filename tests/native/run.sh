#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
build_dir=$(mktemp -d)
trap 'rm -rf -- "$build_dir"' EXIT
"${CXX:-g++}" -std=c++17 -g -O1 -ffunction-sections -fdata-sections \
    -fsanitize=undefined -fno-sanitize-recover=all \
    -Itests/native/include -Iapp/src/main/cpp/doomgeneric \
    tests/native/mus_parser_test.cpp -Wl,--gc-sections -pthread \
    -o "$build_dir/mus-parser-test"
"$build_dir/mus-parser-test"
"${CC:-gcc}" -std=gnu11 -g -O1 -ffunction-sections -fdata-sections \
    -fsanitize=undefined -fno-sanitize-recover=all -D__ANDROID__ -D_DEFAULT_SOURCE \
    -DDOOMGENERIC_RESX=640 -DDOOMGENERIC_RESY=400 \
    -Itests/native/include -Iapp/src/main/cpp/doomgeneric -Iapp/src/main/cpp/jni \
    app/src/main/cpp/doomgeneric/*.c app/src/main/cpp/jni/doomgeneric_android.c \
    tests/native/movement_test.c -Wl,--gc-sections -lm -pthread \
    -o "$build_dir/movement-test"
"$build_dir/movement-test"
"${CC:-gcc}" -std=gnu11 -g -O1 -ffunction-sections -fdata-sections \
    -fsanitize=undefined -fno-sanitize-recover=all -D__ANDROID__ -D_DEFAULT_SOURCE \
    -DDOOMGENERIC_RESX=640 -DDOOMGENERIC_RESY=400 \
    -Itests/native/include -Iapp/src/main/cpp/doomgeneric -Iapp/src/main/cpp/jni \
    app/src/main/cpp/doomgeneric/*.c app/src/main/cpp/jni/doomgeneric_android.c \
    tests/native/menu_shortcuts_test.c -Wl,--gc-sections -lm -pthread \
    -o "$build_dir/menu-shortcuts-test"
"$build_dir/menu-shortcuts-test" "$PWD/app/src/main/assets/wads/doom1.wad" "$build_dir/"
