param([string]$KeyStore = "$env:USERPROFILE\.android\debug.keystore")
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $taskRoot
try {
    if ((git status --porcelain).Length) { throw 'Commit your changes before publishing.' }
    if ((git branch --show-current) -ne 'main') { throw 'Publish from main.' }
    $config = Get-Content app/build.gradle -Raw
    $version = [regex]::Match($config, "versionName '([0-9]+\.[0-9]+\.[0-9]+)'").Groups[1].Value
    if (!$version) { throw 'Could not read versionName.' }
    $tag = "v$version"
    $notes = "docs/releases/$tag.md"
    if (!(Test-Path -LiteralPath $notes)) { throw "Missing release notes: $notes" }
    if (!(Test-Path -LiteralPath $KeyStore)) { throw 'The original signing key is missing; a new key cannot update existing installations.' }
    $sdkLocation = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (!$sdkLocation) { throw 'Set ANDROID_HOME.' }
    $toolsPath = Join-Path $sdkLocation 'build-tools/36.0.0'
    & ./gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:lintDebug --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
    $output = Join-Path $taskRoot ".local/releases/$tag"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $assets = @(); $checksums = @()
    $previousPassword = $env:COMICREADER_SIGN_PASSWORD
    if (!$env:COMICREADER_SIGN_PASSWORD) { $env:COMICREADER_SIGN_PASSWORD = 'android' }
    try {
        foreach ($abi in @('arm64-v8a', 'x86_64', 'universal')) {
            $apk = Join-Path $output "ComicReader-$abi.apk"
            & "$toolsPath/apksigner.bat" sign --ks $KeyStore --ks-pass env:COMICREADER_SIGN_PASSWORD --key-pass env:COMICREADER_SIGN_PASSWORD --out $apk "app/build/outputs/apk/release/app-$abi-release-unsigned.apk"
            if ($LASTEXITCODE -ne 0) { throw "Could not sign $abi" }
            $certificate = & "$toolsPath/apksigner.bat" verify --print-certs $apk
            if ($LASTEXITCODE -ne 0 -or ($certificate -join "`n") -notmatch 'd3eac55f8d81cfd53aff43230f454c126e19099507ce64920dd093c653b984e1') {
                throw 'The certificate does not match existing installations; publication stopped.'
            }
            & "$toolsPath/zipalign.exe" -c -P 16 4 $apk
            if ($LASTEXITCODE -ne 0) { throw "Invalid APK alignment: $abi" }
            $assets += $apk
            $checksums += (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() + "  ComicReader-$abi.apk"
        }
    } finally { $env:COMICREADER_SIGN_PASSWORD = $previousPassword }
    $checksumFile = Join-Path $output 'SHA256SUMS'
    $checksums | Set-Content -LiteralPath $checksumFile -Encoding ascii
    $assets += $checksumFile
    if (!(git tag --list $tag)) { git tag $tag; if ($LASTEXITCODE -ne 0) { throw 'Could not create the tag.' } }
    if ((git rev-parse $tag) -ne (git rev-parse HEAD)) { throw 'The tag already points to a different commit.' }
    git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push origin main $tag
    if ($LASTEXITCODE -ne 0) { throw 'Push failed; the release was not published.' }
    gh release create $tag @assets --draft --verify-tag --title "ComicReader $version" --notes-file $notes --repo Poiki/ComicReader
    if ($LASTEXITCODE -ne 0) { throw 'Release creation failed; nothing was marked as published.' }
    gh release edit $tag --draft=false --latest --repo Poiki/ComicReader
    if ($LASTEXITCODE -ne 0) { throw 'The release is still a draft; check GitHub.' }
} finally { Pop-Location }
