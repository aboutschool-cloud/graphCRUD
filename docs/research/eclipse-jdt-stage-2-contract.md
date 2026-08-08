# Eclipse JDT contract for Stage 2

Status: accepted research input for GitHub Issue #2  
Scope: Eclipse JDT parsing and binding only; no production-code decision is implemented here.

This note separates Eclipse-documented behavior from GraphCRUD project inferences. The latter are proposed implementation constraints derived from the Phase 1 evidence model; they are not claims about JDT.

## Conclusions

For a JDK 21 analysis, create the parser with the Java 21 AST API (`AST.JLS21`), and explicitly set Java 21 compiler compliance rather than relying on workspace defaults. Supply discovered source roots, encodings, and already-resolved binary inputs directly to `ASTParser`; this does not require executing Maven or Gradle. Enable both binding resolution and binding recovery, but never treat a recovered binding as a confirmed semantic result. Retain `CompilationUnit.getProblems()` and null/recovered binding outcomes as source-located evidence, then degrade only affected facts to `possible` or `unresolved`.

## 1. Java 21 parser and compiler options

### Documented facts

- `AST.JLS21` is the AST API level that handles JLS 21. `ASTParser.newParser(int)` accepts a declared `AST.JLS*` level (or the current `AST.getJLSLatest()` value). For a reproducible Java 21 contract, the fixed `AST.JLS21` level is preferable to a moving `latest` level. [AST API](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/AST.html) and [ASTParser.newParser](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#newParser(int))
- AST API level and source-language compatibility are separate settings. JDT's own `ASTParser` example calls `JavaCore.setComplianceOptions(version, options)` and then `setCompilerOptions(options)`; `setCompilerOptions` warns that source compatibility materially changes what syntax is legal and recommends explicit options when defaults are unsuitable. [ASTParser API example and compiler options](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html)
- `JavaCore` exposes `COMPILER_SOURCE`, `COMPILER_COMPLIANCE`, and `COMPILER_CODEGEN_TARGET_PLATFORM`; `JavaCore.setComplianceOptions(String, Map)` configures a map for a requested Java version, and `JavaCore.VERSION_21` identifies Java 21. [JavaCore API](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/JavaCore.html)
- Preview features are controlled separately by `COMPILER_PB_ENABLE_PREVIEW_FEATURES`; the documented default is disabled. [JavaCore preview option](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/JavaCore.html#COMPILER_PB_ENABLE_PREVIEW_FEATURES)

### GraphCRUD inference

Use this baseline configuration for a Java 21 Analysis Project:

```java
ASTParser parser = ASTParser.newParser(AST.JLS21);
Map<String, String> options = new HashMap<>(JavaCore.getDefaultOptions());
JavaCore.setComplianceOptions(JavaCore.VERSION_21, options);
parser.setCompilerOptions(options);
```

Enable preview features only when explicit static build metadata says the project uses Java 21 preview features. Record the effective AST level, source/compliance/target values, and preview flag in snapshot coverage metadata. A missing or contradictory language-level declaration is a bounded configuration problem; it must not silently select the analyzer JVM's default.

## 2. Source roots, encodings, classpath, and modules

### Documented facts

- For standalone parsing without an `IJavaProject`, `ASTParser.setEnvironment` accepts binary `classpathEntries`, source `sourcepathEntries`, per-source-root `encodings`, and a flag controlling inclusion of the running VM boot classpath. JDT requires binary and source inputs in their respective arrays and requires every path to be absolute. [ASTParser.setEnvironment](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#setEnvironment(java.lang.String%5B%5D,java.lang.String%5B%5D,java.lang.String%5B%5D,boolean))
- If encodings are supplied, their count must equal the source-root count; otherwise `setEnvironment` throws `IllegalArgumentException`. A null encoding array means platform encoding. [ASTParser.setEnvironment](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#setEnvironment(java.lang.String%5B%5D,java.lang.String%5B%5D,java.lang.String%5B%5D,boolean))
- When raw `char[]` source is used and bindings are requested, JDT requires both an environment (or Java project) and a unit name. The unit name must use a Java-like extension and match the main public type; `module-info.java` must be named so that it is compiled as a module descriptor. [ASTParser binding location and setUnitName](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#setUnitName(java.lang.String))
- The file-oriented `createASTs(String[], String[], String[], FileASTRequestor, ...)` overload parses batches of source files, while `setEnvironment` supplies their resolution environment. JDT documents batch processing as more efficient when resolving bindings. [ASTParser.createASTs](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#createASTs(java.lang.String%5B%5D,java.lang.String%5B%5D,java.lang.String%5B%5D,org.eclipse.jdt.core.dom.FileASTRequestor,org.eclipse.core.runtime.IProgressMonitor))
- The standalone `setEnvironment` API exposes one binary-entry array named `classpathEntries`; it does not expose a separate module-path parameter. It separately controls only whether the running VM boot classpath is prepended. [ASTParser.setEnvironment signature](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#setEnvironment(java.lang.String%5B%5D,java.lang.String%5B%5D,java.lang.String%5B%5D,boolean))

### GraphCRUD inference

Source discovery and build-metadata interpretation must be GraphCRUD adapters outside JDT. They should produce a deterministic, validated environment:

1. canonical absolute source roots within the Analysis Project boundary;
2. one explicit encoding per root (prefer declared encoding, otherwise a recorded project default such as UTF-8);
3. canonical absolute binary entries from the allowed Phase 1 priority order: explicit manifest, prepared offline dependency directory, approved local cache, then best-effort static metadata;
4. an explicit Java runtime input policy.

Do not execute builds, wrappers, plugins, or annotation processors to obtain these values. Reject or retain as coverage failures paths that do not exist, escape the Analysis Project boundary when they are source inputs, or conflict in encoding/language level. Sort and de-duplicate environment entries before parsing for deterministic snapshots.

Because standalone `ASTParser` has no distinct module-path argument, Stage 2 must not claim that it reproduces a build tool's complete JPMS resolution merely by filling `classpathEntries`. Parse `module-info.java` with the correct unit name, retain module-related `IProblem`s, and mark semantics that depend on unresolved module readability as `unresolved`. A future implementation may validate a more complete module-aware setup separately; Issue #2 establishes no unsupported module-resolution guarantee.

Set `includeRunningVMBootclasspath` only under an explicit policy. For reproducibility, record the analyzer runtime identity if it is `true`; otherwise provide an explicitly selected Java 21 runtime binary input. Never let the host VM silently change snapshot semantics.

## 3. Binding resolution and recovery

### Documented facts

- Binding resolution is off by default. With `setResolveBindings(true)`, JDT resolves names and types while constructing the AST; with it disabled, every `resolveBinding` method returns null. Bindings consume additional time and memory and should not be retained longer than the AST needs to live. [ASTParser.setResolveBindings](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#setResolveBindings(boolean))
- Binding recovery is also off by default and only has an effect when binding resolution is enabled. With recovery enabled, JDT can return incomplete bindings; clients can identify them using `IBinding.isRecovered()`. [ASTParser.setBindingsRecovery](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#setBindingsRecovery(boolean)) and [IBinding.isRecovered](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/IBinding.html#isRecovered())
- Eclipse's JDT guide says recovery can make some bindings, typically missing types, non-null and thereby improve client resilience. It does not say recovered bindings are complete or semantically equivalent to ordinary bindings. [JDT guide: Creating an AST](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_manip.htm)
- Statement recovery is a different feature: `setStatementsRecovery(true)` tries to construct statement nodes from syntactically erroneous code. It does not establish that their bindings are complete. [ASTParser.setStatementsRecovery](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#setStatementsRecovery(boolean))

### GraphCRUD inference

Stage 2 should set:

```java
parser.setResolveBindings(true);
parser.setBindingsRecovery(true);
parser.setStatementsRecovery(true);
```

Classify binding outcomes at every semantic decision point:

| JDT outcome | Maximum GraphCRUD evidence level | Required retention |
| --- | --- | --- |
| unique, non-recovered binding and no relevant error | `confirmed` | canonical binding identity and source anchor |
| multiple statically bounded non-recovered candidates | `possible` | all candidates and why uniqueness failed |
| recovered binding | `unresolved` for any fact requiring that binding's identity | raw spelling, recovered key/name if available, source anchor, relevant problems |
| null binding | `unresolved` | raw spelling, source anchor, relevant problems or a stable fallback reason |

A recovered enclosing type must taint facts whose identity depends on that type; it need not taint unrelated, fully resolved declarations in the same file. Statement recovery may preserve a source anchor and raw expression, but cannot upgrade the semantic fact above what bindings and problems support.

## 4. Missing dependencies and compiler problems

### Documented facts

- `CompilationUnit.getProblems()` returns detailed problems noted during parsing or type checking, although the list may be only a subset of errors a Java compiler would report. [CompilationUnit.getProblems](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/CompilationUnit.html#getProblems())
- An `IProblem` exposes an ID, message, originating file, source start/end, line number, and severity predicates for error, warning, and info. [IProblem API](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/compiler/IProblem.html)
- Even severe syntax failures still return a `CompilationUnit` with at least compiler messages when the parser cannot recognize the input. Nodes can be marked `MALFORMED`, and normal AST nodes carry source ranges back to the input. [ASTParser.createAST](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html#createAST(org.eclipse.core.runtime.IProgressMonitor))

### GraphCRUD inference: bounded unresolved contract

JDT does not define GraphCRUD evidence levels or snapshot policy. Apply these project rules:

1. Collect problems per compilation unit before discarding its AST. Normalize each problem as `(problem ID, severity, originating unit, start, end, line, message)`; use ID and source range as stable machine fields and message as explanation text.
2. Attribute a problem to the smallest source region whose semantic resolution it can affect. Do not make every error in a file invalidate every otherwise complete binding.
3. Emit `unresolved` Evidence Occurrences for raw identifiers or invocation expressions when required bindings are null/recovered or a relevant error prevents unique resolution. Preserve source anchor, adapter, explanation, bounded candidates when present, and a stable reason code such as `JAVA_BINDING_NULL`, `JAVA_BINDING_RECOVERED`, `JAVA_DEPENDENCY_MISSING`, or `JAVA_SYNTAX_RECOVERED`.
4. Use `possible` only for a finite set of statically supported candidates. Missing dependencies alone do not justify inventing candidates.
5. Continue analyzing unrelated files, declarations, and call paths. Seal a partial snapshot with structured coverage when dependency or per-file failures remain; do not abort the Analysis Project solely for those failures.
6. Treat parser setup failures (invalid path/encoding arrays or contradictory settings) separately from source problems. They are manifest/input failures under the Phase 1 contract and can be fatal before a queryable snapshot is produced.

## 5. Stage 2 acceptance checklist

The first JDT implementation ticket should demonstrate, with minimal fixtures:

- fixed Java 21 AST and compiler compliance, with preview disabled unless declared;
- source roots, encodings, binary inputs, and runtime policy supplied without build execution;
- correct unit names, including `module-info.java` behavior where present;
- confirmed output only from unique non-recovered bindings;
- null and recovered bindings retained as bounded `unresolved` evidence;
- `IProblem` ID, severity, location, and explanation retained;
- one missing dependency degrades only the affected path while an unrelated direct-JDBC path remains confirmed;
- deterministic ordering and recorded effective environment metadata.

## Issue #3 readiness

Issue #3 is unblocked by this research. The official API establishes enough behavior to design source discovery and a standalone JDT environment safely. The JPMS limitation above is an explicit conservative boundary, not a blocker: Stage 2 can parse module descriptors and report module-dependent resolution as unresolved without claiming complete build-tool module-path equivalence.
