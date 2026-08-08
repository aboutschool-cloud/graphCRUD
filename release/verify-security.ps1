[CmdletBinding()]
param([datetime]$EvaluatedAt = [datetime]::UtcNow)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$policy = Get-Content -Raw -LiteralPath (Join-Path $repo "release/toolchain.json") | ConvertFrom-Json
$exceptions = Get-Content -Raw -LiteralPath (Join-Path $repo "release/security-exceptions.json") | ConvertFrom-Json
$dbMetadata = Get-Content -Raw -LiteralPath (Join-Path $repo ".tools/stage6/trivy-cache/db/metadata.json") | ConvertFrom-Json
$javaMetadata = Get-Content -Raw -LiteralPath (Join-Path $repo ".tools/stage6/trivy-cache/java-db/metadata.json") | ConvertFrom-Json
$javaCandidate = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$java = if ($javaCandidate -ne "java" -and -not (Test-Path $javaCandidate)) { Join-Path $env:JAVA_HOME "bin/java" } else { $javaCandidate }
$classpath = Join-Path (Resolve-Path (Join-Path $repo "launcher/build/install/launcher")).Path "lib/*"
foreach ($database in @($dbMetadata, $javaMetadata)) {
    $updated = [datetime]::Parse($database.UpdatedAt).ToUniversalTime()
    & $java -cp $classpath io.graphcrud.launcher.ReleasePolicyMain security-db `
        $EvaluatedAt.ToUniversalTime().ToString("o") $updated.ToString("o")
    if ($LASTEXITCODE -ne 0) { throw "authoritative scanner database freshness policy failed" }
}
$findings = @()
foreach ($reportPath in @("graphcrud-trivy.json", "neo4j-trivy.json")) {
    $report = Get-Content -Raw -LiteralPath (Join-Path $repo "build/stage6/security/$reportPath") | ConvertFrom-Json
    $findings += @($report.Results | ForEach-Object { @($_.Vulnerabilities) } | Where-Object { $_.Severity -in @($policy.policy.failSeverities) })
}
foreach ($id in @($findings | Select-Object -ExpandProperty VulnerabilityID -Unique | Sort-Object)) {
    $exception = @($exceptions.exceptions | Where-Object { $_.id -eq $id }) | Select-Object -First 1
    if ($null -eq $exception) { throw "missing security exception: $id" }
    & $java -cp $classpath io.graphcrud.launcher.ReleasePolicyMain security-exception `
        $EvaluatedAt.ToUniversalTime().ToString("o") $exception.id $exception.owner $exception.reason $exception.expiresAt
    if ($LASTEXITCODE -ne 0) { throw "authoritative security exception policy failed: $id" }
}
Write-Output "security gate verified: highOrCriticalOccurrences=$($findings.Count) uniqueExceptions=$(@($findings | Select-Object -ExpandProperty VulnerabilityID -Unique).Count) dbUpdatedAt=$($dbMetadata.UpdatedAt)"
