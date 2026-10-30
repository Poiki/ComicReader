param([switch]$Check)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$version = '1.37.0'
$expectedHash = '834b2a0c38cb774953322a84b5ca3f2f40dd3156650b3cd44d3b744345962f7a'
$formatter = Join-Path $taskRoot ".local/tools/google-java-format-$version.jar"
if (!(Test-Path -LiteralPath $formatter)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $formatter) | Out-Null
    Invoke-WebRequest -Uri "https://github.com/google/google-java-format/releases/download/v$version/google-java-format-$version-all-deps.jar" -OutFile $formatter
}
if ((Get-FileHash -LiteralPath $formatter -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Formatter checksum mismatch. No source files were changed.'
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' }) } else { 'java' }
Push-Location -LiteralPath $taskRoot
try {
    $sources = @(git ls-files '*.java')
    $options = @('--aosp', '--skip-reflowing-long-strings')
    if ($Check) { $options += @('--dry-run', '--set-exit-if-changed') } else { $options += '--replace' }
    & $java -jar $formatter @options @sources
    if ($LASTEXITCODE -ne 0) { throw 'Java formatting check failed.' }
} finally { Pop-Location }
