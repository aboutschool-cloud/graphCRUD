# Stage 0 Gradle module design

Stage 0 exposes one build interface, `./gradlew ciFast`, and four deliberately empty
module interfaces. No parser behavior or parser-facing types exist yet.

| Module | Interface in Stage 0 | Allowed project dependencies |
| --- | --- | --- |
| `model` | Future home of immutable normalized domain facts | none |
| `application` | Future home of use cases and the GraphStore capability seam | `model` |
| `infrastructure` | Future home of parsing and persistence adapters | `application` |
| `launcher` | Future composition root for CLI and HTTP delivery | `application`, `infrastructure` |

This is the smallest graph that makes the infrastructure-leakage rule observable.
Splitting parser or storage adapters now would create hypothetical seams: Stage 0 has
no behavior and therefore no second adapter. Later stages should split an adapter
only when its interface earns leverage through multiple implementations or contract
tests.

The module graph is itself the Stage 0 public interface. `ciFast` compiles and tests
all modules, resolves locked dependencies under strict verification, checks the real
module graph, and runs a known-invalid dependency fixture. CI calls the same task.
