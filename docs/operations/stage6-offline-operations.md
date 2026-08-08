# Stage 6 offline installation and operations

## Supported installation shape

The package targets Linux x86_64 containers, including Docker Desktop with WSL2. It contains the GraphCRUD image, a native Java 21 ZIP, and an optional Neo4j Community image. From the extracted bundle root, use `-f compose/compose.yaml` for the bundled graph database or `-f compose/compose-external.yaml` with `GRAPHCRUD_BOLT_URI`, user, and password for a customer-managed compatible graph database.

Verify `SHA256SUMS` before loading images. Run `docker load -i images/graphcrud-0.1.0.tar` and, when using the bundled database, `docker load -i images/neo4j-5.26.29-community-sanitized.tar`. Set `NEO4J_AUTH=neo4j/<strong-password>`, `GRAPHCRUD_BOLT_PASSWORD`, and an absolute read-only `GRAPHCRUD_SOURCE_ROOT`, then run `docker compose -f compose/compose.yaml up -d --pull never --wait neo4j`. No source directory is mounted writable and no telemetry is enabled by the supplied configuration.

For a customer-managed graph database, do not load or start the bundled Neo4j image. Set `GRAPHCRUD_BOLT_URI`, `GRAPHCRUD_BOLT_USER`, `GRAPHCRUD_BOLT_PASSWORD`, and `GRAPHCRUD_SOURCE_ROOT`, then use `docker compose -f compose/compose-external.yaml --profile cli run --rm --pull never graphcrud <command> ...`. The customer endpoint must satisfy the documented `GraphStore` capabilities; packaging it is outside this bundle.

For the native JVM path, extract `application/graphcrud-0.1.0-SNAPSHOT.zip`, install Java 21, set `GRAPHCRUD_BOLT_URI`, `GRAPHCRUD_BOLT_USER`, `GRAPHCRUD_BOLT_PASSWORD`, and `GRAPHCRUD_SOURCE_ROOT`, then invoke `launcher-0.1.0-SNAPSHOT/bin/launcher <command> ...` (`launcher.bat` on Windows). This path never starts or installs a graph database; it connects only to the configured Bolt endpoint.

On a Windows host whose execution policy requires signed scripts, invoke the bundle-root verifier explicitly with `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./verify-offline-bundle.ps1 -BundleDirectory <extracted-bundle-root>` after reviewing its contents. This is a per-process policy choice and does not change the machine policy.

## Operator workflow

Set `GRAPHCRUD_BOLT_PASSWORD` and `GRAPHCRUD_SOURCE_ROOT` for every CLI invocation. In the following bundled-database commands, `docker compose` means `docker compose -f compose/compose.yaml`; for a customer-managed database substitute `-f compose/compose-external.yaml`:

1. `docker compose --profile cli run --rm graphcrud analyze <project> <snapshot> /workspace`
2. `docker compose --profile cli run --rm graphcrud status <project> [snapshot]`
3. `docker compose --profile cli run --rm graphcrud table-impact <project> <database-source> <schema> <table> [depth paths] [snapshot]`
4. If and only if a reviewed partial snapshot should become active, `docker compose --profile cli run --rm graphcrud promote-partial <project> <snapshot>`.
5. `docker compose --profile cli run --rm graphcrud export <project> <snapshot>` emits JSONL to stdout; redirect stdout to an operator-approved local file. `report <project> [html]` emits a status report to stdout, while `report <project> table-impact <database-source> <schema> <table> [html]` emits a query report.
6. `support-bundle <project> <approved-redacted-diagnostic>` emits the Stage 5 support ZIP bytes to stdout; redirect it to a local ZIP and review it before transfer. The argument is diagnostic text, not an output path.
7. `docker compose --profile cli run --rm graphcrud purge-project <project>` removes that project's graph records.
8. Stop without deleting data using `docker compose down`. After backup/retention approval, uninstall the bundled database and delete its test/customer data using `docker compose down --volumes --remove-orphans`; verify `docker compose ps --all` is empty. The supplied verifier performs this destructive teardown only for its isolated `graphcrud-offline-verify` project.

The HTTP API and CLI call the same Stage 5 application use cases. Use status/progress events for automation; do not start an unbounded number of analyses. The supplied Compose limit is one operator-triggered CLI container at a time, and callers should queue additional projects. Logs are available through `docker compose logs neo4j` and the CLI stderr stream. Metrics are limited to container CPU/memory/disk/network plus structured job status; no remote metrics exporter or default egress is configured.

Neo4j reads `NEO4J_AUTH` only when initializing an empty data volume. Changing the environment variable does not rotate the password in an existing volume. Use Neo4j's password administration procedure for an existing database; only remove the data volume when its contents are intentionally disposable and backed up.

## Capacity, backup, upgrade, and rollback

The default limits are 2 CPU/2 GiB for GraphCRUD and 4 CPU/4 GiB for Neo4j. Corpus timings are comparable only with the environment ID in `corpus-results.json`. Back up the Neo4j data volume and export the active snapshot before upgrades. Verify checksums, SBOM, scan policy, image architecture, and a restore in a disposable environment. Rollback means restoring the prior image set and its compatible data backup; never assume a database store upgraded in place can be opened by an older Neo4j image.

Disaster recovery requires a tested Neo4j backup/restore procedure appropriate to the Community edition and filesystem in use, the matching image digest, GraphCRUD export, configuration with secrets restored from the customer's secret manager, and a post-restore active-snapshot/status check. Disk exhaustion, unhealthy Bolt, authentication failure, stale passwords in persistent volumes, digest mismatch, unsupported architecture, and `PARTIAL` coverage are distinct conditions; do not erase a volume to troubleshoot them.

## Privacy and support

Analysis is local. Source roots are read-only and are not placed in support bundles. Support bundles must remain redacted according to Stage 5 tests and should contain versions, checksums, bounded status, and logs only. Treat exported facts, file paths, identifiers, SQL, logs, and graph backups as customer-confidential. Purge with the CLI, then apply the customer's backup-retention and media-destruction policy.

For incidents, capture the checksum manifest, image IDs, Compose configuration with secrets removed, snapshot status, available disk/memory, and redacted logs. Do not send credentials, source files, raw database volumes, or unreviewed exports.

Security preparation is connected and release verification is offline: run pinned Trivy once with network access to refresh both databases, preserve their `metadata.json` provenance, then scan both exact local image IDs. The final `verify-security.ps1` uses only the cached metadata, JSON reports, severity policy, and time-bounded exceptions. Databases older than 72 hours, a new unmatched HIGH/CRITICAL finding, or an expired exception fails the release. Copy the refreshed cache through controlled media when the build workstation itself is offline; never silently reuse an old database.

Only the bundled sanitized Neo4j Community 5.26.29 image and a customer-supplied backend that satisfies the vendor-neutral `GraphStore` capabilities are approved. No other backend is claimed supported in Stage 6. The bundled graph database is optional and replaceable; GraphCRUD and Neo4j remain separate processes connected only by Bolt. Customer contracts must preserve all GPL rights for the corresponding GPL component and must not apply GraphCRUD commercial restrictions to it.
