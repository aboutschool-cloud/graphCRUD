[CmdletBinding()]
param(
    [string]$OutputDirectory = "build/stage6/offline/graphcrud-0.1.0-linux-x86_64",
    [string]$Neo4jSourceCommit = "e1d373ea096b22961d1b944d9aaa85f999764c02"
)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$output = Join-Path $repo $OutputDirectory
if (Test-Path -LiteralPath $output) { throw "output already exists: $output" }
$directories = @("images", "application", "compose", "compliance", "evidence", "sources")
$directories | ForEach-Object { New-Item -ItemType Directory -Force -Path (Join-Path $output $_) | Out-Null }

Push-Location $repo
try {
    & .\gradlew.bat :launcher:installDist :launcher:distZip --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle distribution failed" }
    docker build --provenance=false -f release/Dockerfile -t graphcrud:0.1.0 .
    if ($LASTEXITCODE -ne 0) { throw "GraphCRUD image build failed" }
    $graphcrudId = docker image inspect graphcrud:0.1.0 --format '{{.Id}}'
    if ($graphcrudId -ne "sha256:f9040ea692b1d0f3896d02aa04729fa4693d7627aba8816a2696bc54bfe0f604") {
        throw "GraphCRUD image is not reproducible: $graphcrudId"
    }
    docker pull --platform linux/amd64 neo4j:5.26.29-community-ubi10@sha256:1a04b5441194aef79413ba0baad4a8ff9365f5cdd8ab77be40ec364e6c6f1aeb
    if ($LASTEXITCODE -ne 0) { throw "Neo4j image preparation failed" }
    & release/prepare-neo4j-rootfs.ps1
    if ($LASTEXITCODE -ne 0) { throw "sanitized Neo4j rootfs preparation failed" }
    docker build --provenance=false -f release/Dockerfile.neo4j-community -t graphcrud/neo4j-community:5.26.29-sanitized .
    if ($LASTEXITCODE -ne 0) { throw "sanitized Neo4j image build failed" }
    $neo4jId = docker image inspect graphcrud/neo4j-community:5.26.29-sanitized --format '{{.Id}}'
    if ($neo4jId -ne "sha256:61715f2b21db10790eaa0cad61470a5c44001907fe2c86ff6deec5997db51edf") {
        throw "sanitized Neo4j image is not reproducible: $neo4jId"
    }
    & release/prepare-supply-chain.ps1
    if ($LASTEXITCODE -ne 0) { throw "supply-chain evidence preparation failed" }
    & release/verify-license-inventory.ps1
    if ($LASTEXITCODE -ne 0) { throw "license inventory verification failed" }
    & release/verify-security.ps1
    if ($LASTEXITCODE -ne 0) { throw "security verification failed" }
    docker save --output (Join-Path $output "images/graphcrud-0.1.0.tar") graphcrud:0.1.0
    docker save --output (Join-Path $output "images/neo4j-5.26.29-community-sanitized.tar") graphcrud/neo4j-community:5.26.29-sanitized

    Copy-Item launcher/build/distributions/launcher-0.1.0-SNAPSHOT.zip (Join-Path $output "application/graphcrud-0.1.0-SNAPSHOT.zip")
    Copy-Item release/compose.yaml,release/compose-external.yaml (Join-Path $output "compose")
    Copy-Item release/verify-offline-bundle.ps1 (Join-Path $output "verify-offline-bundle.ps1")
    Copy-Item release/fixtures/offline-smoke (Join-Path $output "smoke-source") -Recurse
    Copy-Item release/Dockerfile.neo4j-community (Join-Path $output "compliance")
    Copy-Item release/corpora.json,release/corpus-results.json,release/toolchain.json,release/security-exceptions.json,release/neo4j-source.json (Join-Path $output "evidence")
    Copy-Item docs/adr/0007-distribute-neo4j-community-as-separate-gpl-aggregate.md (Join-Path $output "compliance")
    Copy-Item docs/operations/stage6-offline-operations.md,docs/operations/gplv3-aggregate-compliance.md (Join-Path $output "compliance")
    Copy-Item docs/stage6-technical-mvp-report.md,docs/stage6-pilot-ready-evidence.md (Join-Path $output "evidence")
    Copy-Item build/stage6/sbom/*.spdx.json (Join-Path $output "evidence")
    Copy-Item build/stage6/security/graphcrud-trivy.json,build/stage6/security/neo4j-trivy.json (Join-Path $output "evidence")
    Copy-Item .tools/stage6/trivy-cache/db/metadata.json (Join-Path $output "evidence/trivy-vulnerability-db-metadata.json")
    Copy-Item .tools/stage6/trivy-cache/java-db/metadata.json (Join-Path $output "evidence/trivy-java-db-metadata.json")
    Copy-Item build/stage6/licenses/THIRD-PARTY-LICENSES.md (Join-Path $output "compliance")
    Copy-Item release/license-overrides.json (Join-Path $output "compliance")

    $sourceCheckout = Join-Path $repo "build/stage6/neo4j-source"
    if (-not (Test-Path (Join-Path $sourceCheckout ".git"))) {
        git clone --filter=blob:none https://github.com/neo4j/neo4j.git $sourceCheckout
    }
    git -C $sourceCheckout fetch origin tag 5.26.29
    if ($LASTEXITCODE -ne 0) { throw "Neo4j source fetch failed" }
    if ((git -C $sourceCheckout rev-parse $Neo4jSourceCommit) -ne $Neo4jSourceCommit) { throw "Neo4j source commit mismatch" }
    if ((git -C $sourceCheckout rev-parse '5.26.29^{}') -ne $Neo4jSourceCommit) { throw "Neo4j source tag mismatch" }
    $sourceArchive = Join-Path $output "sources/neo4j-5.26.29-corresponding-source.zip"
    git -C $sourceCheckout archive --format=zip "--output=$sourceArchive" 5.26.29
    if ($LASTEXITCODE -ne 0) { throw "Neo4j corresponding-source archive failed" }
    tar -tf $sourceArchive | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Neo4j corresponding-source archive verification failed" }
    Copy-Item (Join-Path $sourceCheckout "LICENSE.txt") (Join-Path $output "sources/NEO4J-GPL-LICENSE.txt")

    Get-ChildItem -LiteralPath $output -File -Recurse | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($output.Length).TrimStart('\').Replace('\','/')
        "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower())  $relative"
    } | Set-Content -LiteralPath (Join-Path $output "SHA256SUMS") -Encoding ascii
} finally { Pop-Location }
Write-Output $output
