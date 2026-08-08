$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $repo
try {
    & .\gradlew.bat :launcher:clean :launcher:distZip --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "first distribution build failed" }
    $first = (Get-FileHash launcher/build/distributions/launcher-0.1.0-SNAPSHOT.zip -Algorithm SHA256).Hash
    & .\gradlew.bat :launcher:clean :launcher:distZip --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "second distribution build failed" }
    $second = (Get-FileHash launcher/build/distributions/launcher-0.1.0-SNAPSHOT.zip -Algorithm SHA256).Hash
    if ($first -ne $second) { throw "distribution is not reproducible: $first != $second" }
    Write-Output "launcher-0.1.0-SNAPSHOT.zip sha256=$($second.ToLower())"
} finally { Pop-Location }
