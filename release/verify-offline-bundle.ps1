[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BundleDirectory,
    [string]$Password = "graphcrud-offline-verification"
)
$ErrorActionPreference = "Stop"
$bundle = (Resolve-Path $BundleDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $bundle "SHA256SUMS")
foreach ($line in $manifest) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "invalid checksum line: $line" }
    $file = Join-Path $bundle $Matches[2]
    $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $Matches[1]) { throw "checksum mismatch: $($Matches[2])" }
}
$neo4jArchive = Join-Path $bundle "images/neo4j-5.26.29-community-sanitized.tar"
$layerAudit = Join-Path $env:TEMP "graphcrud-neo4j-layer-audit-$PID"
New-Item -ItemType Directory -Force -Path $layerAudit | Out-Null
try {
    tar -xf $neo4jArchive -C $layerAudit
    if ($LASTEXITCODE -ne 0) { throw "cannot inspect Neo4j OCI archive" }
    $imageManifest = Get-Content -Raw -LiteralPath (Join-Path $layerAudit "manifest.json") | ConvertFrom-Json
    foreach ($layer in @($imageManifest[0].Layers)) {
        $matches = tar -tf (Join-Path $layerAudit $layer) | Select-String -SimpleMatch "neo4j-fleet-management-plugin"
        if ($matches) { throw "separately licensed fleet plugin bytes are present in OCI layer: $layer" }
    }
} finally {
    Remove-Item -LiteralPath $layerAudit -Recurse -Force -ErrorAction SilentlyContinue
}
docker load --input (Join-Path $bundle "images/graphcrud-0.1.0.tar") | Out-Null
if ($LASTEXITCODE -ne 0) { throw "cannot load bundled GraphCRUD image" }
docker load --input $neo4jArchive | Out-Null
if ($LASTEXITCODE -ne 0) { throw "cannot load bundled Neo4j image" }
$graphcrudId = docker image inspect graphcrud:0.1.0 --format '{{.Id}}'
$neo4jId = docker image inspect graphcrud/neo4j-community:5.26.29-sanitized --format '{{.Id}}'
if ($graphcrudId -ne "sha256:f9040ea692b1d0f3896d02aa04729fa4693d7627aba8816a2696bc54bfe0f604") {
    throw "bundled GraphCRUD image identity mismatch: $graphcrudId"
}
if ($neo4jId -ne "sha256:61715f2b21db10790eaa0cad61470a5c44001907fe2c86ff6deec5997db51edf") {
    throw "bundled Neo4j image identity mismatch: $neo4jId"
}
$neoArch = docker image inspect graphcrud/neo4j-community:5.26.29-sanitized --format '{{.Architecture}}'
if ($neoArch -ne "amd64") { throw "offline Neo4j image is not amd64: $neoArch" }
docker run --rm --entrypoint sh graphcrud/neo4j-community:5.26.29-sanitized -c "test ! -e /var/lib/neo4j/products/neo4j-fleet-management-plugin-1.2.0-v5.jar"
if ($LASTEXITCODE -ne 0) { throw "separately licensed Neo4j fleet plugin is present" }
$managedEnvironment = @("NEO4J_AUTH", "GRAPHCRUD_BOLT_PASSWORD", "GRAPHCRUD_SOURCE_ROOT", "COMPOSE_PROJECT_NAME")
$previousEnvironment = @{}
foreach ($name in $managedEnvironment) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process") }
$env:NEO4J_AUTH = "neo4j/$Password"
$env:GRAPHCRUD_BOLT_PASSWORD = $Password
$env:GRAPHCRUD_SOURCE_ROOT = Join-Path $bundle "smoke-source"
$env:COMPOSE_PROJECT_NAME = "graphcrud-offline-verify"
$compose = Join-Path $bundle "compose/compose.yaml"
$override = Join-Path $env:TEMP "graphcrud-offline-verify.override.yaml"
@"
services:
  neo4j:
    ports: !reset []
"@ | Set-Content -LiteralPath $override -Encoding utf8
$composeArgs = @('-f', $compose, '-f', $override)
function Invoke-OfflineGraphCrud([string[]]$Arguments) {
    $ErrorActionPreference = "Continue"
    docker compose @composeArgs run --rm --pull never graphcrud @Arguments 2>&1 | Out-Null
    $exit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($exit -ne 0) { throw "offline GraphCRUD command failed ($exit): $($Arguments -join ' ')" }
}
try {
    docker compose @composeArgs config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Compose configuration is invalid" }
    $ErrorActionPreference = "Continue"
    docker compose @composeArgs down --volumes --remove-orphans 2>&1 | Out-Null
    docker compose @composeArgs up -d --pull never --wait neo4j
    $composeExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($composeExit -ne 0) { throw "offline Neo4j health verification failed" }
    Invoke-OfflineGraphCrud @("analyze", "offline-verification", "offline-smoke", "/workspace")
    Invoke-OfflineGraphCrud @("status", "offline-verification", "offline-smoke")
    # The deliberately classpath-free smoke analysis is PARTIAL. Explicit promotion
    # makes it the active snapshot before exercising active-snapshot report behavior.
    Invoke-OfflineGraphCrud @("promote-partial", "offline-verification", "offline-smoke")
    Invoke-OfflineGraphCrud @("table-impact", "offline-verification", "default", "public", "smoke_table", "3", "100", "offline-smoke")
    Invoke-OfflineGraphCrud @("export", "offline-verification", "offline-smoke")
    Invoke-OfflineGraphCrud @("report", "offline-verification")
    Invoke-OfflineGraphCrud @("support-bundle", "offline-verification", "redacted-smoke-diagnostic")
    Invoke-OfflineGraphCrud @("purge-project", "offline-verification")
    Write-Output "offline bundle checksums, clean image layers, amd64, Compose health, Bolt, analyze/status/promote/query/export/report/support-bundle/purge verified"
} finally {
    $ErrorActionPreference = "Continue"
    docker compose @composeArgs down --volumes --remove-orphans 2>&1 | Out-Null
    $downExit = $LASTEXITCODE
    $remainingContainers = @(docker compose @composeArgs ps --all --quiet 2>$null)
    $psExit = $LASTEXITCODE
    $remainingVolumes = @(docker volume ls --quiet --filter "label=com.docker.compose.project=graphcrud-offline-verify" 2>$null)
    $volumeExit = $LASTEXITCODE
    $cleanupFailure = if ($downExit -ne 0 -or $psExit -ne 0 -or $volumeExit -ne 0 `
            -or $remainingContainers.Count -gt 0 -or $remainingVolumes.Count -gt 0) {
        "offline uninstall verification left containers or volumes behind"
    } else { $null }
    Remove-Item -LiteralPath $override -Force -ErrorAction SilentlyContinue
    foreach ($name in $managedEnvironment) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], "Process")
    }
    $ErrorActionPreference = "Stop"
    if ($cleanupFailure) { throw $cleanupFailure }
}
