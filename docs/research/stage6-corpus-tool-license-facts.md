# Stage 6 corpus, tooling, licensing, and offline-package facts

Status: research input for Stage 6 planning (verified 2026-08-02)

Scope: immutable evaluation inputs, reproducible run metadata, SBOM/license and
container-scanning tools, Docker Compose image identity, and air-gapped delivery.
This note records upstream facts and GraphCRUD implications; the resulting Community
Edition aggregate delivery position is approved separately by ADR-0007. Open-source
corpus output remains unsuitable as a correctness oracle.

## Conclusions

Stage 6 should use four immutable inputs: the official MyBatis Spring Boot samples
as the small control corpus, JPetStore as a traditional MyBatis application control,
RuoYi-Vue-Plus as the PostgreSQL-scale complexity smoke corpus, and MyBatis-Plus as
the Gradle sentinel. The release tags and peeled commit SHAs below existed on the
verification date. The harness must clone or unpack exactly those commits and verify
the resulting Git object/content digest before analysis; branch names are descriptive
metadata only.

These projects have no GraphCRUD-maintained expected call/CRUD annotations. A
successful run proves only non-crashing compatibility and records observed coverage,
counts, reasons, timings, and deterministic output hashes. The hand-authored fixtures
in this repository remain the sole strict accuracy oracle. This follows the project's
evidence contract rather than any property claimed by the upstream projects.

An offline release is a closed, checksummed set of artifacts, not a Compose file that
still pulls from registries. It must contain the GraphCRUD distribution, every required
OCI image addressed by digest and exported for transfer, SBOM and license inventory,
scanner plus a timestamped offline vulnerability database, installation/upgrade/purge
instructions, and a manifest of SHA-256 checksums. Installation acceptance must run
with external network access disabled.

## 1. Immutable corpus selection

| Role | Immutable selection | What primary source establishes | License and bound |
|---|---|---|---|
| Official MyBatis Spring control | `mybatis/spring-boot-starter`, release `mybatis-spring-boot-4.1.0`, peeled commit [`5f1d7e3e01054a663cd1ae8b37141fe88c015f58`](https://github.com/mybatis/spring-boot-starter/tree/5f1d7e3e01054a663cd1ae8b37141fe88c015f58) | The immutable tree contains Maven sample modules for [annotation Mapper SQL](https://github.com/mybatis/spring-boot-starter/blob/5f1d7e3e01054a663cd1ae8b37141fe88c015f58/mybatis-spring-boot-samples/mybatis-spring-boot-sample-annotation/src/main/java/sample/mybatis/annotation/mapper/CityMapper.java) and [XML Mapper SQL](https://github.com/mybatis/spring-boot-starter/blob/5f1d7e3e01054a663cd1ae8b37141fe88c015f58/mybatis-spring-boot-samples/mybatis-spring-boot-sample-xml/src/main/resources/sample/mybatis/xml/mapper/CityMapper.xml). | [Apache-2.0](https://github.com/mybatis/spring-boot-starter/blob/5f1d7e3e01054a663cd1ae8b37141fe88c015f58/LICENSE). This is framework-control input, not PostgreSQL or application-accuracy truth. Select explicit sample subdirectories so future unrelated samples cannot silently enter the run. |
| Traditional application control | `mybatis/jpetstore-6`, release `jpetstore-6.3.0`, peeled commit [`ce8015be2c2658d0b657be20328e53045f3a9641`](https://github.com/mybatis/jpetstore-6/tree/ce8015be2c2658d0b657be20328e53045f3a9641) | The immutable [Maven POM](https://github.com/mybatis/jpetstore-6/blob/ce8015be2c2658d0b657be20328e53045f3a9641/pom.xml) declares MyBatis/MyBatis-Spring and packages a web application; its [database scripts](https://github.com/mybatis/jpetstore-6/tree/ce8015be2c2658d0b657be20328e53045f3a9641/src/main/resources/database) are HSQLDB-oriented. | [Apache-2.0](https://github.com/mybatis/jpetstore-6/blob/ce8015be2c2658d0b657be20328e53045f3a9641/LICENSE). Do not score its DDL/SQL as PostgreSQL support. |
| PostgreSQL-scale complexity smoke | `dromara/RuoYi-Vue-Plus`, release `v5.6.1`, peeled commit [`6bfdcae06eaf218c4204382de277499be6c88c1b`](https://github.com/dromara/RuoYi-Vue-Plus/tree/6bfdcae06eaf218c4204382de277499be6c88c1b) | This preserves the already-reviewed `5.X` corpus line. The immutable root [Maven POM](https://github.com/dromara/RuoYi-Vue-Plus/blob/6bfdcae06eaf218c4204382de277499be6c88c1b/pom.xml) defines the multi-module build and the tree contains PostgreSQL SQL under [`script/sql/postgres`](https://github.com/dromara/RuoYi-Vue-Plus/tree/6bfdcae06eaf218c4204382de277499be6c88c1b/script/sql/postgres), including `postgres_ry_vue_5.X.sql`. | [MIT](https://github.com/dromara/RuoYi-Vue-Plus/blob/6bfdcae06eaf218c4204382de277499be6c88c1b/LICENSE). MyBatis-Plus generated/wrapper SQL is outside Phase 1 native-MyBatis support, so preserve it as possible/unresolved coverage rather than confirmed accuracy. Start with an explicitly listed backend module set. |
| Gradle/build compatibility sentinel | `baomidou/mybatis-plus`, release `v3.5.17`, commit [`db0b3c4bb58a38bad9c3d78b7269d8a477cc6a63`](https://github.com/baomidou/mybatis-plus/tree/db0b3c4bb58a38bad9c3d78b7269d8a477cc6a63) | The immutable tree has [`settings.gradle`](https://github.com/baomidou/mybatis-plus/blob/db0b3c4bb58a38bad9c3d78b7269d8a477cc6a63/settings.gradle) and a checked-in [Gradle wrapper](https://github.com/baomidou/mybatis-plus/tree/db0b3c4bb58a38bad9c3d78b7269d8a477cc6a63/gradle/wrapper). | [Apache-2.0](https://github.com/baomidou/mybatis-plus/blob/db0b3c4bb58a38bad9c3d78b7269d8a477cc6a63/LICENSE). It tests static Gradle metadata/source discovery only. GraphCRUD must not execute its wrapper or treat framework source as an end-to-end application benchmark. |

The official [`spring-projects/spring-petclinic`](https://github.com/spring-projects/spring-petclinic)
must not replace the MyBatis control corpus: at verified commit
[`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`](https://github.com/spring-projects/spring-petclinic/tree/88e37c15cf6fc8490b01bc3e8e2c800cec1ac272),
its [POM uses Spring Data JPA](https://github.com/spring-projects/spring-petclinic/blob/88e37c15cf6fc8490b01bc3e8e2c800cec1ac272/pom.xml),
which Phase 1 explicitly excludes. JPetStore is the relevant pet-store-shaped official
MyBatis application.

### Retrieval and cache contract

For each entry, store repository URL, release tag, full commit SHA, selected paths,
license SPDX identifier, acquisition timestamp, and SHA-256 of the transferred source
carrier. GitHub warns that branch/tag archives can change when the reference moves and
that the outer bytes of commit archives may change while extracted contents remain the
same. Therefore do not publish GitHub-generated tarball bytes as a permanent upstream
identity. Preserve the exact acquired carrier internally and verify the checked-out
commit independently. [GitHub source-code archive stability](https://docs.github.com/en/repositories/working-with-files/using-files/downloading-source-code-archives)

A self-contained `git bundle` is a stronger offline carrier: Git documents that bundles
support offline object transfer, clone/fetch, and prerequisite/validity checking with
`git bundle verify`. Create the bundle from a locally controlled immutable ref to the
approved commit, checksum the bundle, then verify both bundle and checkout SHA offline.
[Git `bundle`](https://git-scm.com/docs/git-bundle) Offline evaluation consumes only
that verified bundle/cache; it never resolves a branch, contacts GitHub, runs the
corpus Maven/Gradle wrapper, or downloads project dependencies. Prepared classpaths
must separately record locked coordinates, origin, and checksums, matching
[ADR-0002](../adr/0002-customer-side-offline-deployment.md).

Whether corpus source bundles are shipped to customers or only a preparation manifest
is shipped is a packaging/legal decision. If source is redistributed, include it in
the artifact/SBOM inventory and preserve each pinned license and NOTICE. Apache-2.0
section 4 requires a license copy, modified-file notices, and retention/reproduction of
applicable NOTICE attribution; MIT requires its copyright and permission notice to be
included. [Apache-2.0 in the pinned JPetStore tree](https://github.com/mybatis/jpetstore-6/blob/ce8015be2c2658d0b657be20328e53045f3a9641/LICENSE),
[MIT in the pinned RuoYi tree](https://github.com/dromara/RuoYi-Vue-Plus/blob/6bfdcae06eaf218c4204382de277499be6c88c1b/LICENSE)

The reproducibility record for every run should additionally contain GraphCRUD version
and commit, distribution/image digest, corpus commit and source-archive digest, selected
paths, JDK vendor/version, OS/kernel/architecture, CPU count/model, memory limit, exact
command/config/ignore rules, prepared-classpath manifest digest, start/end timestamps,
completion/coverage/reason counts, stage timings and peak memory, exported-facts hash,
report hash, and harness schema version. A performance comparison is valid only on the
same declared environment/corpus/config; after baseline acceptance, the repository's
specified greater-than-20-percent regression gate applies.

## 2. Compose and offline image identity

- Compose permits an image reference in the form `name:tag` or `name@digest`; an
  implementation may pull when the image is absent according to `pull_policy`.
  Therefore release Compose must use `image: repository@sha256:...` and a policy that
  does not silently refresh it, and acceptance must inspect the local image identity.
  [Docker Compose `image` and `pull_policy`](https://docs.docker.com/reference/compose-file/services/#image)
- `docker image save` writes one or more images (including parent layers/tags) to a tar
  archive, and `docker image load` restores images and tags from such an archive.
  These are the documented primitives for preparing a connected bundle and importing
  it offline. [Docker `image save`](https://docs.docker.com/reference/cli/docker/image/save/),
  [Docker `image load`](https://docs.docker.com/reference/cli/docker/image/load/)
- A digest pin fixes image content but is not itself proof that the tar received by the
  customer is intact. Record both the OCI manifest digest and the bundle file's SHA-256,
  verify checksums before loading, then run Compose with registry egress blocked.

GraphCRUD inference: a human-readable image tag may be retained as metadata but cannot
be the release identity. The package must also pin the Compose implementation/version
used for acceptance, because the YAML alone does not package the runtime or images.

## 3. SBOM, license inventory, and vulnerability scan

- Syft scans container images, filesystems, and archives and can emit SPDX JSON and
  CycloneDX JSON. Its official repository is Apache-2.0 licensed. Pin an immutable Syft
  release/binary checksum and generate SBOMs for both the application distribution and
  every shipped image. [Syft official repository](https://github.com/anchore/syft)
- SPDX distinguishes a package's upstream-declared license from the license concluded
  by the document creator. Thus an automatically discovered license is evidence for an
  inventory, not legal approval. Preserve `NOASSERTION`/unknowns and require review;
  do not rewrite them into guessed SPDX expressions. [SPDX package licensing fields](https://spdx.github.io/spdx-spec/v2.3/package-information/)
- Trivy documents an air-gapped flow: download its vulnerability database in a
  connected environment, transfer it into the offline cache, and scan with database
  updates disabled. Java database handling is separate where applicable. Pin the Trivy
  binary/image and database artifacts and record their checksums and update timestamp;
  a stale database must be visible in the report, not presented as a current clean
  scan. [Trivy air-gapped environment](https://trivy.dev/latest/docs/advanced/air-gap/)

The release gate should retain machine-readable SBOM, human-reviewable license
inventory/notices, and vulnerability results as distinct artifacts. Include tool
versions/config, target image digests, database version/time, severity policy,
allowlist entries with owner/expiry/reason, and raw report hashes. SBOM completeness and
license approval cannot be inferred merely from a scanner exit code.

## 4. Graph-storage license boundary

Neo4j's source repository identifies the Community Edition as GPLv3, while Neo4j also
offers separately licensed commercial editions. [Neo4j source licensing notice](https://github.com/neo4j/neo4j/blob/dev/LICENSE.txt)
Consequently a technically working Community image must not automatically be included
in a commercial customer bundle. Stage 6 can produce and test two packaging profiles:

1. a GraphCRUD JVM/application distribution connecting to a customer-provided,
   capability-approved GraphStore; and
2. a Compose profile containing the specifically approved graph image only after the
   owner/legal decision records edition, exact digest, redistribution terms, notices,
   and support position.

ADR-0007 approves the first profile using an unmodified Community Edition image as an
independent GPLv3 aggregate, with exact Corresponding Source and installation
information conveyed in the same offline bundle. An SBOM or license scanner identifies
materials; it does not grant redistribution rights or replace release compliance review.

## 5. Acceptance implications for Stage 6 tickets

1. **Corpus manifest and cache:** check immutable SHAs, source/archive checksums,
   allowlisted subtrees, licenses, and offline prepared classpath inputs.
2. **Harness and report:** run without executing customer/corpus builds; record the
   complete reproducibility schema; distinguish non-crash/coverage/determinism from
   fixture accuracy; establish the fixed-environment baseline.
3. **Supply-chain artifacts:** pin the SBOM/scanner toolchain, emit SPDX/CycloneDX plus
   reviewed license inventory, use an explicitly timestamped offline vulnerability DB,
   and checksum every delivered artifact.
4. **Offline package:** use digest-pinned images, exported OCI/Docker archives, no-pull
   Compose behavior, verified native Linux x86_64/WSL2 install/upgrade/query/purge, and
   a test with outbound network disabled.
5. **Commercial and operational closure:** record the graph-storage approval, residual
   vulnerabilities/license exceptions, backup/restore and purge procedures, resource
   assumptions, support-bundle redaction, and rollback instructions before declaring
   Pilot-ready.
