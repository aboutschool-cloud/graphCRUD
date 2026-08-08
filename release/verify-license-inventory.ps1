[CmdletBinding()]
param(
    [string]$Output = "build/stage6/licenses/THIRD-PARTY-LICENSES.md"
)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$policy = Get-Content -Raw -LiteralPath (Join-Path $repo "release/license-overrides.json") | ConvertFrom-Json
$rows = @()
foreach ($artifact in @("graphcrud-image", "neo4j-image")) {
    $sbomPath = Join-Path $repo "build/stage6/sbom/$artifact.spdx.json"
    $sbom = Get-Content -Raw -LiteralPath $sbomPath | ConvertFrom-Json
    foreach ($package in @($sbom.packages)) {
        $license = $package.licenseDeclared
        $purl = (@($package.externalRefs | Where-Object { $_.referenceType -eq "purl" }) | Select-Object -First 1).referenceLocator
        if ($license -eq "NOASSERTION") {
            if ($purl -match '^pkg:maven/([^/]+)/') { $group = $Matches[1] }
            elseif ($purl -match '^pkg:([^/]+)/') { $group = "type:$($Matches[1])" }
            else { throw "unresolved license without purl: $artifact/$($package.name)" }
            $property = $policy.noAssertionGroupOverrides.PSObject.Properties[$group]
            if ($null -eq $property) { throw "unresolved NOASSERTION group: $group ($artifact/$($package.name))" }
            $license = $property.Value
        }
        foreach ($pattern in @($policy.disallowedPatterns)) {
            if ($license -match [regex]::Escape($pattern)) { throw "disallowed license ${license}: $artifact/$($package.name)" }
        }
        $rows += [pscustomobject]@{ Artifact=$artifact; Package=$package.name; Version=$package.versionInfo; License=$license; Purl=$purl }
    }
}
$destination = Join-Path $repo $Output
New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
$lines = @(
    "# Third-party license inventory",
    "",
    "Generated from the exact SPDX image SBOMs with Syft 1.50.0. Scanner `NOASSERTION` entries are accepted only when covered by the reviewed mappings in `release/license-overrides.json`; an unmapped or disallowed license fails this script. License texts and notices remain embedded in their RPM/JAR artifacts, and the Neo4j GPLv3 text plus corresponding source are separately included in the offline package.",
    "",
    "| Artifact | Package | Version | Resolved license | PURL |",
    "|---|---|---|---|---|"
)
$rows | Sort-Object Artifact,Package,Version,Purl | ForEach-Object {
    $lines += "| $($_.Artifact) | $($_.Package -replace '\|','\|') | $($_.Version) | $($_.License -replace '\|','\|') | $($_.Purl -replace '\|','\|') |"
}
$lines | Set-Content -LiteralPath $destination -Encoding utf8
Write-Output "license inventory verified: packages=$($rows.Count) output=$destination"
