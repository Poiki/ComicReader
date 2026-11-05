#!/usr/bin/env bash
set -euo pipefail

archive_version=3.8.11
archive_sha256=900fe9fc7237c2b04f090f3c3648de8fe597525771f5035016537ce2d667d12b
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
task_output=${ARCHIVE_OUTPUT:-"$task_root/.local/archive-native"}
if [[ ${1:-} == --check ]]; then
    [[ ${#archive_sha256} == 64 && -f "$task_root/scripts/libarchive-memory.patch" ]]
    exit 0
fi
mkdir -p "$task_output/source"
if [[ -z ${ARCHIVE_SOURCE:-} ]]; then
    archive_tar="$task_output/source/libarchive-$archive_version.tar.gz"
    if [[ ! -f $archive_tar ]]; then
        curl --fail --location --retry 3 \
            "https://github.com/libarchive/libarchive/releases/download/v$archive_version/libarchive-$archive_version.tar.gz" \
            -o "$archive_tar"
    fi
    printf '%s  %s\n' "$archive_sha256" "$archive_tar" | sha256sum --check
    ARCHIVE_SOURCE="$task_output/source/libarchive-$archive_version"
    [[ -f "$ARCHIVE_SOURCE/CMakeLists.txt" ]] || tar -xzf "$archive_tar" -C "$task_output/source"
fi
if [[ $(cat "$ARCHIVE_SOURCE/build/version") != 3008011 ]]; then
    printf 'Expected libarchive 3.8.11 source.\n' >&2
    exit 1
fi
if patch --directory="$ARCHIVE_SOURCE" --strip=1 --batch --forward --dry-run < "$task_root/scripts/libarchive-memory.patch" >/dev/null 2>&1; then
    patch --directory="$ARCHIVE_SOURCE" --strip=1 --forward < "$task_root/scripts/libarchive-memory.patch"
elif ! patch --directory="$ARCHIVE_SOURCE" --strip=1 --batch --reverse --dry-run < "$task_root/scripts/libarchive-memory.patch" >/dev/null 2>&1; then
    printf 'libarchive source does not match the expected safety patch; prepare a fresh source tree.\n' >&2
    exit 1
fi

for abi in ${BUILD_ARCHS:-arm64-v8a x86_64}; do
    [[ $abi == arm64-v8a || $abi == x86_64 ]]
    build="$task_output/build/$abi"
    cmake -S "$task_root/app/src/main/cpp" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
        -DANDROID_PLATFORM=28 -DANDROID_ABI="$abi" -DCMAKE_BUILD_TYPE=Release \
        -DARCHIVE_SOURCE="$ARCHIVE_SOURCE"
    cmake --build "$build" --target comicarchive --parallel "${BUILD_JOBS:-3}"
    mkdir -p "$task_output/jniLibs/$abi"
    cp "$build/libcomicarchive.so" "$task_output/jniLibs/$abi/"
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
        --strip-unneeded "$task_output/jniLibs/$abi/libcomicarchive.so"
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -lW \
        "$task_output/jniLibs/$abi/libcomicarchive.so" > "$task_output/$abi-program-headers.txt"
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -dW \
        "$task_output/jniLibs/$abi/libcomicarchive.so" > "$task_output/$abi-dynamic.txt"
done

if [[ ${BUILD_NATIVE_HOST:-1} == 1 ]]; then
    build="$task_output/build/host"
    cmake -S "$task_root/app/src/main/cpp" -B "$build" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release -DARCHIVE_SOURCE="$ARCHIVE_SOURCE" \
        -DJNI_HEADER_ROOT="$JAVA_HOME/include"
    cmake --build "$build" --target comicarchive --parallel "${BUILD_JOBS:-3}"
    mkdir -p "$task_output/host"
    cp "$build/libcomicarchive.so" "$task_output/host/"
fi

if [[ ${BUILD_WINDOWS_HOST:-0} == 1 ]]; then
    build="$task_output/build/windows"
    cmake -S "$task_root/app/src/main/cpp" -B "$build" -G Ninja \
        -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
        -DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres -DCMAKE_BUILD_TYPE=Release \
        -DZLIB_INCLUDE_DIR=/usr/x86_64-w64-mingw32/include \
        -DZLIB_LIBRARY=/usr/x86_64-w64-mingw32/lib/libz.a \
        -DARCHIVE_SOURCE="$ARCHIVE_SOURCE" -DJNI_HEADER_ROOT=/jdk-include
    cmake --build "$build" --target comicarchive --parallel "${BUILD_JOBS:-3}"
    mkdir -p "$task_output/host"
    cp "$build/comicarchive.dll" "$task_output/host/"
fi

{
    printf 'LIBARCHIVE_VERSION=%s\nSOURCE_SHA256=%s\n' "$archive_version" "$archive_sha256"
    sha256sum "$task_root/scripts/libarchive-memory.patch" "$task_root/app/src/main/cpp/rar_jni.c" \
        "$task_root/app/src/main/cpp/CMakeLists.txt"
    sha256sum "$task_output"/jniLibs/*/libcomicarchive.so
} > "$task_output/BUILD-MANIFEST.txt"
