[CmdletBinding()]
param(
    [string]$UpstreamImage = "neo4j:5.26.29-community-ubi10@sha256:1a04b5441194aef79413ba0baad4a8ff9365f5cdd8ab77be40ec364e6c6f1aeb"
)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$root = Join-Path $repo "build/stage6/neo4j-rootfs"
$work = Join-Path $root "work-$PID"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$raw = Join-Path $work "upstream-merged.tar"
$normalizedTemporary = Join-Path $work "normalized.tar"
$container = docker create --entrypoint sh $UpstreamImage -c "rm /var/lib/neo4j/products/neo4j-fleet-management-plugin-1.2.0-v5.jar"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($container)) { throw "cannot create upstream Neo4j filesystem container" }
try {
    docker start -a $container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "cannot remove fleet plugin from merged filesystem" }
    docker export --output $raw $container
    if ($LASTEXITCODE -ne 0) { throw "cannot export merged Neo4j filesystem" }
} finally {
    docker rm -f $container 2>&1 | Out-Null
}
$mount = $work.Replace('\','/')
$normalize = "mkdir /tmp/rootfs; tar -xf /io/upstream-merged.tar -C /tmp/rootfs; " +
    "rm -f /tmp/rootfs/etc/hostname /tmp/rootfs/etc/hosts /tmp/rootfs/etc/resolv.conf; " +
    "test ! -e /tmp/rootfs/var/lib/neo4j/products/neo4j-fleet-management-plugin-1.2.0-v5.jar; " +
    "tar --sort=name --mtime='UTC 2026-08-03' --owner=0 --group=0 --numeric-owner --format=posix " +
    "--pax-option=delete=atime,delete=ctime -cf /io/normalized.tar -C /tmp/rootfs ."
docker run --rm --entrypoint sh -v "${mount}:/io" $UpstreamImage -c $normalize
if ($LASTEXITCODE -ne 0) { throw "cannot normalize Neo4j filesystem" }
if ((tar -tf $normalizedTemporary | Select-String -SimpleMatch "neo4j-fleet-management-plugin")) {
    throw "commercial fleet plugin remains in normalized filesystem"
}
New-Item -ItemType Directory -Force -Path $root | Out-Null
Move-Item -LiteralPath $normalizedTemporary -Destination (Join-Path $root "normalized.tar") -Force
Write-Output "normalizedRootfsSha256=$((Get-FileHash (Join-Path $root 'normalized.tar') -Algorithm SHA256).Hash.ToLower())"
