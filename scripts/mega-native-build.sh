#!/usr/bin/env bash
set -euo pipefail

sdk_commit=d1c0cb76cb98598d93b9fb0bb89612daeb0fb39c
vcpkg_commit=ef7dbf94b9198bc58f45951adcf1f041fcbc5ea0
if [[ ${1:-} == --check ]]; then
    [[ ${#sdk_commit} == 40 && ${#vcpkg_commit} == 40 ]]
    exit 0
fi
[[ -d /output && -f $NDK_ROOT/build/cmake/android.toolchain.cmake ]]
if [[ ! -d /work/sdk/.git ]]; then
    git clone --depth 1 --branch v10.21.0 https://github.com/meganz/sdk.git /work/sdk
fi
[[ $(git -C /work/sdk rev-parse HEAD) == "$sdk_commit" ]]
if [[ ! -d $VCPKG_ROOT/.git ]]; then
    git init "$VCPKG_ROOT"
    git -C "$VCPKG_ROOT" remote add origin https://github.com/microsoft/vcpkg.git
    git -C "$VCPKG_ROOT" fetch --depth 1 origin "$vcpkg_commit"
    git -C "$VCPKG_ROOT" checkout --detach FETCH_HEAD
fi
[[ $(git -C "$VCPKG_ROOT" rev-parse HEAD) == "$vcpkg_commit" ]]

for abi in ${BUILD_ARCHS:-arm64-v8a x86_64}; do
    [[ $abi == arm64-v8a || $abi == x86_64 ]]
    build=/work/build/$abi
    cmake -S /work/sdk --preset mega-android -B "$build" \
        -DVCPKG_ROOT="$VCPKG_ROOT" -DANDROID_PLATFORM=28 -DANDROID_ABI="$abi" \
        -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
        -DENABLE_MEDIA_FILE_METADATA=OFF -DUSE_FREEIMAGE=OFF -DUSE_FFMPEG=OFF \
        -DUSE_PDFIUM=OFF -DENABLE_SDKLIB_TESTS=OFF -DENABLE_SDKLIB_EXAMPLES=OFF
    cmake --build "$build" --parallel "${BUILD_JOBS:-3}"
    mkdir -p "/output/jniLibs/$abi" /output/java/nz/mega/sdk /output/kotlin/nz/mega/sdk /output/licenses
    "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$build/bindings/java/libmega.so"
    cp "$build/bindings/java/libmega.so" "/output/jniLibs/$abi/"
    cp "$build/bindings/java/nz/mega/sdk/"*.java /output/java/nz/mega/sdk/
    "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -lW "$build/bindings/java/libmega.so" > "/output/$abi-program-headers.txt"
    "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -dW "$build/bindings/java/libmega.so" > "/output/$abi-dynamic.txt"
done
cp /work/sdk/bindings/java/nz/mega/sdk/*.java /output/java/nz/mega/sdk/
cp /work/sdk/bindings/java/nz/mega/sdk/*.kt /output/kotlin/nz/mega/sdk/
cp /work/sdk/LICENSE /output/licenses/MEGA-SDK-BSD-2-Clause.txt
while IFS= read -r -d '' notice; do
    relative=${notice#/work/build/}
    mkdir -p "/output/licenses/third-party/$(dirname -- "$relative")"
    cp "$notice" "/output/licenses/third-party/$relative"
done < <(find /work/build -type f -path '*/share/*/copyright' -print0)
{
    printf 'SDK_COMMIT=%s\nVCPKG_COMMIT=%s\n' "$sdk_commit" "$vcpkg_commit"
    cat "$NDK_ROOT/source.properties"
    sha256sum /output/jniLibs/*/libmega.so
} > /output/BUILD-MANIFEST.txt
