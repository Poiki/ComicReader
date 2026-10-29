param([switch]$Check)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Check) {
    @('mega-native.Dockerfile', 'mega-native-build.sh') | ForEach-Object {
        if (!(Test-Path -LiteralPath (Join-Path $PSScriptRoot $_))) { throw "Falta $_" }
    }
    return
}
Push-Location -LiteralPath $taskRoot
try {
    $taskOutput = Join-Path $taskRoot '.local\mega-sdk'
    New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
    docker info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0) { throw 'Inicia Docker Desktop con contenedores Linux.' }
    docker build --progress plain -f scripts/mega-native.Dockerfile -t comicreader-mega-toolchain:ndk28c scripts
    if ($LASTEXITCODE -ne 0) { throw 'Falló la preparación del toolchain.' }
    docker volume create comicreader-mega-work-v10 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo preparar la caché de construcción.' }
    docker run --rm --cpus 3 --memory 5g --mount 'type=volume,source=comicreader-mega-work-v10,target=/work' --mount "type=bind,source=$taskOutput,target=/output" comicreader-mega-toolchain:ndk28c
    if ($LASTEXITCODE -ne 0) { throw 'Falló el SDK nativo. La caché se conserva para reintentar.' }
    foreach ($taskAbi in @('arm64-v8a', 'x86_64')) {
        $taskLibrary = Join-Path $taskOutput "jniLibs\$taskAbi\libmega.so"
        if (!(Test-Path -LiteralPath $taskLibrary)) { throw "Falta JNI para $taskAbi" }
    }
    Write-Output 'SDK MEGA nativo preparado. Ejecuta .\gradlew.bat assembleDebug.'
} finally { Pop-Location }
