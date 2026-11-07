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
