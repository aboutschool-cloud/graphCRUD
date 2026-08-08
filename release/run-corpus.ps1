[CmdletBinding()]
param(
    [string]$CorpusRoot = "build/stage6/corpora",
    [string]$OutputDirectory = "build/stage6/corpus-runs/final",
    [int]$TimeoutSeconds = 600
)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$manifest = Get-Content -Raw -LiteralPath (Join-Path $repo "release/corpora.json") | ConvertFrom-Json
$output = Join-Path $repo $OutputDirectory
New-Item -ItemType Directory -Force -Path $output | Out-Null
$dockerInfo = docker info --format '{{json .}}' | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $dockerInfo.ServerVersion) { throw "cannot fingerprint Docker environment" }
$environmentFacts = [ordered]@{
    osType=$dockerInfo.OSType; architecture=$dockerInfo.Architecture; cpuCount=[int]$dockerInfo.NCPU;
    memoryBytes=[long]$dockerInfo.MemTotal; serverVersion=$dockerInfo.ServerVersion;
    operatingSystem=$dockerInfo.OperatingSystem; graphcrudCpuLimit=2; graphcrudMemoryBytes=2147483648
}
$factsJson = $environmentFacts | ConvertTo-Json -Compress
$sha = [Security.Cryptography.SHA256]::Create()
try { $fingerprint = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($factsJson)))).Replace('-','').ToLower() }
finally { $sha.Dispose() }
$environmentId = "docker-$($dockerInfo.OSType)-$($dockerInfo.Architecture)-$($fingerprint.Substring(0,16))"

function Invoke-GraphCrud([string[]]$Arguments, [string]$Stdout, [string]$Stderr, [string]$ContainerName) {
    $dockerArguments = @("compose", "-f", (Join-Path $repo "release/compose.yaml"), "run", "--rm", "--name", $ContainerName, "graphcrud") + $Arguments
    $process = Start-Process -FilePath "docker" -ArgumentList $dockerArguments -NoNewWindow -PassThru `
        -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr
    # Force PowerShell to retain the native process handle so ExitCode remains
    # available after the short-lived docker client exits.
    [void]$process.Handle
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        docker rm -f $ContainerName 2>&1 | Out-Null
        return 124
    }
    # Complete asynchronous redirected-stream handling before reading ExitCode.
    $process.WaitForExit()
    $process.Refresh()
    return $process.ExitCode
}

$results = @()
foreach ($corpus in @($manifest.corpora)) {
    $checkout = Join-Path (Join-Path $repo $CorpusRoot) $corpus.role
    if (-not (Test-Path (Join-Path $checkout ".git"))) {
        git clone --filter=blob:none --no-checkout $corpus.repository $checkout
        if ($LASTEXITCODE -ne 0) { throw "clone failed: $($corpus.role)" }
    }
    git -C $checkout fetch origin $corpus.commit
    if ($LASTEXITCODE -ne 0) { throw "fetch failed: $($corpus.role)" }
    git -C $checkout checkout --detach $corpus.commit
    if ((git -C $checkout rev-parse HEAD) -ne $corpus.commit) { throw "commit mismatch: $($corpus.role)" }
    $analysisRoot = (Resolve-Path (Join-Path $checkout $corpus.analysisPath)).Path
    $env:GRAPHCRUD_SOURCE_ROOT = $analysisRoot
    $project = "$($corpus.role)-release"
    $snapshot = "stage6-$($corpus.role)-release"
    $purgeOut = Join-Path $output "$($corpus.role)-purge.json"
    $purgeErr = Join-Path $output "$($corpus.role)-purge.stderr.log"
    if ((Invoke-GraphCrud -Arguments @("purge-project", $project) -Stdout $purgeOut -Stderr $purgeErr -ContainerName "graphcrud-corpus-$($corpus.role)-purge") -ne 0) {
        throw "cannot reset corpus project: $($corpus.role)"
    }
    $analyzeOut = Join-Path $output "$($corpus.role)-analyze.json"
    $analyzeErr = Join-Path $output "$($corpus.role)-analyze.stderr.log"
    $startedAt = [datetime]::UtcNow.ToString("o")
    $started = [Diagnostics.Stopwatch]::StartNew()
    $exit = Invoke-GraphCrud -Arguments @("analyze", $project, $snapshot, "/workspace") -Stdout $analyzeOut -Stderr $analyzeErr -ContainerName "graphcrud-corpus-$($corpus.role)-analyze"
    $started.Stop()
    if ($exit -ne 0) { throw "analysis failed: $($corpus.role), exit=$exit" }
    $analysisText = Get-Content -Raw -LiteralPath $analyzeOut
    if ($analysisText -notmatch '"state":"([A-Z]+)"') { throw "missing snapshot state: $($corpus.role)" }
    $analysisState = $Matches[1]
    $timingLine = Get-Content -LiteralPath $analyzeErr | Where-Object { $_ -match '^\{"event":"analysis-stage-timing"' } | Select-Object -Last 1
    if (-not $timingLine) { throw "missing stage timing: $($corpus.role)" }
    $timing = $timingLine | ConvertFrom-Json
    $graphTimingLine = Get-Content -LiteralPath $analyzeErr | Where-Object { $_ -match '^\{"event":"graph-write-stage-timing"' } | Select-Object -Last 1
    if (-not $graphTimingLine) { throw "missing graph-write timing: $($corpus.role)" }
    $graphTiming = $graphTimingLine | ConvertFrom-Json
    $export1 = Join-Path $output "$($corpus.role)-export-1.jsonl"
    $export2 = Join-Path $output "$($corpus.role)-export-2.jsonl"
    $queryTimer = [Diagnostics.Stopwatch]::StartNew()
    if ((Invoke-GraphCrud -Arguments @("export", $project, $snapshot) -Stdout $export1 -Stderr (Join-Path $output "$($corpus.role)-export-1.stderr.log") -ContainerName "graphcrud-corpus-$($corpus.role)-export-1") -ne 0) { throw "first export failed" }
    $queryTimer.Stop()
    if ((Invoke-GraphCrud -Arguments @("export", $project, $snapshot) -Stdout $export2 -Stderr (Join-Path $output "$($corpus.role)-export-2.stderr.log") -ContainerName "graphcrud-corpus-$($corpus.role)-export-2") -ne 0) { throw "second export failed" }
    $hash1 = (Get-FileHash -LiteralPath $export1 -Algorithm SHA256).Hash.ToLower()
    $hash2 = (Get-FileHash -LiteralPath $export2 -Algorithm SHA256).Hash.ToLower()
    if ($hash1 -ne $hash2) { throw "semantic export is not deterministic: $($corpus.role)" }
    $results += [pscustomobject]@{
        role=$corpus.role; commit=$corpus.commit; analysisPath=$corpus.analysisPath; exitCode=$exit;
        command="graphcrud analyze $project $snapshot /workspace"; analyzerVersion="graphcrud-0.1.0";
        jdk="Eclipse Temurin 21"; startedAt=$startedAt; maximumDurationSeconds=$TimeoutSeconds;
        state=$analysisState; coverageComplete=($analysisState -eq "COMPLETE"); elapsedMs=$started.ElapsedMilliseconds;
        stageTimingsMs=[ordered]@{
            discovery=$timing.discoveryMs; parsing=$timing.parsingMs; resolution=$timing.resolutionMs;
            graphWrite=$graphTiming.graphWriteMs; query=$queryTimer.ElapsedMilliseconds
        };
        semanticSha256=$hash1; accuracyOracle=$false
    }
}
[pscustomobject]@{
    schemaVersion="1"; evaluatedAt=[datetime]::UtcNow.ToString("o");
    environment=[ordered]@{
        id=$environmentId; hostCpuCount=[int]$dockerInfo.NCPU;
        hostMemoryGiB=[math]::Round(([double]$dockerInfo.MemTotal / 1GB), 2);
        dockerServerVersion=$dockerInfo.ServerVersion; dockerOperatingSystem=$dockerInfo.OperatingSystem;
        graphcrudCpuLimit=2; graphcrudMemoryGiB=2; jdk="Eclipse Temurin 21";
        graphcrudImage=(docker image inspect graphcrud:0.1.0 --format '{{.Id}}')
    };
    policy=[ordered]@{
        purpose="compatibility-and-non-crash-only"; accuracyOracle=$false;
        warmup="one successful purge-project Bolt invocation before each timed analysis"; sampleCount=1;
        regressionThresholdPercent=20; comparisonRequiresSameEnvironmentId=$true;
        maximumRunDurationSeconds=$TimeoutSeconds
    };
    runs=$results;
    interpretation="PARTIAL records missing build classpaths as bounded coverage degradation. Each semantic hash was verified by two consecutive exports of the same sealed snapshot."
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output "corpus-results.json") -Encoding utf8
Write-Output (Join-Path $output "corpus-results.json")
