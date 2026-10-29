param([string]$KeyStore = "$env:USERPROFILE\.android\debug.keystore")
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $taskRoot
try {
    if ((git status --porcelain).Length) { throw 'Haz commit de los cambios antes de publicar.' }
    if ((git branch --show-current) -ne 'main') { throw 'Publica desde main.' }
    $config = Get-Content app/build.gradle -Raw
    $version = [regex]::Match($config, "versionName '([0-9]+\.[0-9]+\.[0-9]+)'").Groups[1].Value
    if (!$version) { throw 'No se pudo leer versionName.' }
    $tag = "v$version"
    $notes = "docs/releases/$tag.md"
    if (!(Test-Path -LiteralPath $notes)) { throw "Faltan las notas: $notes" }
    if (!(Test-Path -LiteralPath $KeyStore)) { throw 'Falta la clave de firma original; no generes otra para actualizar instalaciones existentes.' }
    $sdkLocation = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (!$sdkLocation) { throw 'Configura ANDROID_HOME.' }
    $toolsPath = Join-Path $sdkLocation 'build-tools/36.0.0'
    & ./gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintDebug --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación o las pruebas.' }
    $output = Join-Path $taskRoot ".local/releases/$tag"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $assets = @(); $checksums = @()
    $previousPassword = $env:COMICREADER_SIGN_PASSWORD
    if (!$env:COMICREADER_SIGN_PASSWORD) { $env:COMICREADER_SIGN_PASSWORD = 'android' }
    try {
        foreach ($abi in @('arm64-v8a', 'x86_64', 'universal')) {
            $apk = Join-Path $output "ComicReader-$abi.apk"
            & "$toolsPath/apksigner.bat" sign --ks $KeyStore --ks-pass env:COMICREADER_SIGN_PASSWORD --key-pass env:COMICREADER_SIGN_PASSWORD --out $apk "app/build/outputs/apk/release/app-$abi-release-unsigned.apk"
            if ($LASTEXITCODE -ne 0) { throw "No se pudo firmar $abi" }
            $certificate = & "$toolsPath/apksigner.bat" verify --print-certs $apk
            if ($LASTEXITCODE -ne 0 -or ($certificate -join "`n") -notmatch 'd3eac55f8d81cfd53aff43230f454c126e19099507ce64920dd093c653b984e1') {
                throw 'Firma distinta de las instalaciones existentes; no se publicará.'
            }
            & "$toolsPath/zipalign.exe" -c -P 16 4 $apk
            if ($LASTEXITCODE -ne 0) { throw "Alineación incorrecta: $abi" }
            $assets += $apk
            $checksums += (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() + "  ComicReader-$abi.apk"
        }
    } finally { $env:COMICREADER_SIGN_PASSWORD = $previousPassword }
    $checksumFile = Join-Path $output 'SHA256SUMS'
    $checksums | Set-Content -LiteralPath $checksumFile -Encoding ascii
    $assets += $checksumFile
    if (!(git tag --list $tag)) { git tag $tag; if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el tag.' } }
    if ((git rev-parse $tag) -ne (git rev-parse HEAD)) { throw 'El tag ya apunta a otro commit.' }
    git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push origin main $tag
    if ($LASTEXITCODE -ne 0) { throw 'Falló el push; no se publica la release.' }
    gh release create $tag @assets --draft --verify-tag --title "ComicReader $version" --notes-file $notes --repo Poiki/ComicReader
    if ($LASTEXITCODE -ne 0) { throw 'Falló la creación de la release; no se marca como publicada.' }
    gh release edit $tag --draft=false --latest --repo Poiki/ComicReader
    if ($LASTEXITCODE -ne 0) { throw 'La release sigue como borrador; revisa GitHub.' }
} finally { Pop-Location }
