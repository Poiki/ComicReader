param([switch]$Check)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Check) {
    @('archive-native.Dockerfile', 'archive-native-build.sh', 'libarchive-memory.patch') | ForEach-Object {
        if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot $_))) { throw "Missing $_" }
    }
    return
}
$taskJava = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { throw 'Set JAVA_HOME to a Windows JDK.' }
$taskHeaders = Join-Path $taskJava 'include'
if (!(Test-Path -LiteralPath (Join-Path $taskHeaders 'win32/jni_md.h'))) { throw 'Windows JDK JNI headers are missing.' }
Push-Location -LiteralPath $taskRoot
try {
    docker info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop with the Linux container engine.' }
    docker image inspect comicreader-mega-toolchain:ndk28c --format '{{.Id}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        docker build --progress plain -f scripts/mega-native.Dockerfile -t comicreader-mega-toolchain:ndk28c scripts
        if ($LASTEXITCODE -ne 0) { throw 'Android native toolchain setup failed.' }
    }
    docker build --progress plain -f scripts/archive-native.Dockerfile -t comicreader-archive-toolchain:ndk28c scripts
    if ($LASTEXITCODE -ne 0) { throw 'Archive toolchain setup failed.' }
    docker run --rm --cpus 3 --memory 5g --mount "type=bind,source=$taskRoot,target=/repo" --mount "type=bind,source=$taskHeaders,target=/jdk-include,readonly" --env BUILD_WINDOWS_HOST=1 comicreader-archive-toolchain:ndk28c
    if ($LASTEXITCODE -ne 0) { throw 'Native archive build failed; the source/build cache is retained.' }
    foreach ($taskFile in @('jniLibs/arm64-v8a/libcomicarchive.so', 'jniLibs/x86_64/libcomicarchive.so', 'host/comicarchive.dll')) {
        if (!(Test-Path -LiteralPath (Join-Path $taskRoot ".local/archive-native/$taskFile"))) { throw "Missing $taskFile" }
    }
    Write-Output 'Native archive libraries ready.'
} finally { Pop-Location }
