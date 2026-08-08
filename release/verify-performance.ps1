[CmdletBinding()]
param(
    [string]$Baseline = "release/corpus-results.json",
    [string]$Candidate = "build/stage6/corpus-runs/final/corpus-results.json"
)
$ErrorActionPreference = "Stop"
$baselineData = Get-Content -Raw -LiteralPath $Baseline | ConvertFrom-Json
$candidateData = Get-Content -Raw -LiteralPath $Candidate | ConvertFrom-Json
$stageNames = @("discovery", "parsing", "resolution", "graphWrite", "query")
function Encode-Stages($data) {
    $encoded = @()
    foreach ($run in @($data.runs)) {
        foreach ($stage in $stageNames) {
            $property = $run.stageTimingsMs.PSObject.Properties[$stage]
            if ($null -ne $property) { $encoded += "$($run.role)/$stage=$($property.Value)" }
        }
    }
    if ($encoded.Count -eq 0) { return "-" }
    return $encoded -join ','
}
$javaCandidate = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java.exe" } else { "java" }
$java = if ($javaCandidate -ne "java" -and -not (Test-Path $javaCandidate)) { Join-Path $env:JAVA_HOME "bin/java" } else { $javaCandidate }
$classpath = Join-Path (Resolve-Path "launcher/build/install/launcher").Path "lib/*"
& $java -cp $classpath io.graphcrud.launcher.ReleasePolicyMain performance `
    $baselineData.environment.id $candidateData.environment.id (Encode-Stages $baselineData) (Encode-Stages $candidateData)
if ($LASTEXITCODE -ne 0) { throw "authoritative performance policy failed with exit $LASTEXITCODE" }
