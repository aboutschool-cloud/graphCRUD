[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$tools = Join-Path $repo ".tools/stage6"
$downloads = Join-Path $tools "downloads"
New-Item -ItemType Directory -Force -Path $downloads,(Join-Path $repo "build/stage6/sbom"),(Join-Path $repo "build/stage6/security") | Out-Null

function Install-PinnedTool([string]$Name, [string]$Url, [string]$ArchiveName, [string]$Sha256) {
    $executable = Join-Path $tools "$Name.exe"
    $archive = Join-Path $downloads $ArchiveName
    if (-not (Test-Path -LiteralPath $archive)) {
        curl.exe -fL --retry 3 -o $archive $Url
        if ($LASTEXITCODE -ne 0) { throw "cannot download $Name" }
    }
    $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $Sha256) { throw "$Name archive checksum mismatch: $actual" }
    $extract = Join-Path $downloads "$Name-extracted"
    Expand-Archive -LiteralPath $archive -DestinationPath $extract -Force
    Copy-Item -LiteralPath (Join-Path $extract "$Name.exe") -Destination $executable -Force
    return $executable
}

$syft = Install-PinnedTool "syft" "https://github.com/anchore/syft/releases/download/v1.50.0/syft_1.50.0_windows_amd64.zip" `
    "syft_1.50.0_windows_amd64.zip" "815ee6973ec5dff6a671d7f41b0e78835a8c45b91d5a39f4743ea1cee833d3be"
$trivy = Install-PinnedTool "trivy" "https://github.com/aquasecurity/trivy/releases/download/v0.72.0/trivy_0.72.0_windows-64bit.zip" `
    "trivy_0.72.0_windows-64bit.zip" "ed3cf122060f61818fe1f735fd97557954e16e10bc8b058af9852271cf2e91b3"
$syftVersion = (& $syft version) -join "`n"
$trivyVersion = (& $trivy --version) -join "`n"
if ($syftVersion -notmatch '(?m)^Version:\s+1\.50\.0$') { throw "unexpected Syft executable version" }
if ($trivyVersion -notmatch '(?m)^Version:\s+0\.72\.0$') { throw "unexpected Trivy executable version" }

function Normalize-Sbom([string]$Path, [string]$Image) {
    $document = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    $digest = (docker image inspect $Image --format '{{.Id}}').Replace('sha256:','')
    $created = [datetime]::Parse((docker image inspect $Image --format '{{.Created}}')).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $document.documentNamespace = "https://graphcrud.local/spdx/image/$digest"
    $document.creationInfo.created = $created
    $document | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $Path -Encoding utf8
}
function Generate-Deterministic-Sbom([string]$Image, [string]$Output) {
    $comparison = "$Output.comparison"
    foreach ($path in @($Output, $comparison)) {
        & $syft $Image -o "spdx-json=$path"
        if ($LASTEXITCODE -ne 0) { throw "$Image SBOM generation failed" }
        Normalize-Sbom $path $Image
    }
    if ((Get-FileHash $Output -Algorithm SHA256).Hash -ne (Get-FileHash $comparison -Algorithm SHA256).Hash) {
        throw "$Image normalized SBOM is not reproducible"
    }
    Remove-Item -LiteralPath $comparison -Force
}
Generate-Deterministic-Sbom "graphcrud:0.1.0" (Join-Path $repo 'build/stage6/sbom/graphcrud-image.spdx.json')
Generate-Deterministic-Sbom "graphcrud/neo4j-community:5.26.29-sanitized" (Join-Path $repo 'build/stage6/sbom/neo4j-image.spdx.json')
$cache = Join-Path $tools "trivy-cache"
& $trivy image --cache-dir $cache --format json --output (Join-Path $repo "build/stage6/security/graphcrud-trivy.json") graphcrud:0.1.0
if ($LASTEXITCODE -ne 0) { throw "GraphCRUD security scan failed" }
& $trivy image --cache-dir $cache --format json --output (Join-Path $repo "build/stage6/security/neo4j-trivy.json") graphcrud/neo4j-community:5.26.29-sanitized
if ($LASTEXITCODE -ne 0) { throw "Neo4j security scan failed" }
Write-Output "supply-chain evidence prepared with pinned Syft and Trivy"
