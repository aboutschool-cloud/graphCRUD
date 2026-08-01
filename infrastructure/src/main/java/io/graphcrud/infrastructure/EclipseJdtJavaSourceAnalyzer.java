package io.graphcrud.infrastructure;

import io.graphcrud.application.JavaAnalysisInput;
import io.graphcrud.application.JavaAnalysisResult;
import io.graphcrud.application.JavaSourceAnalyzer;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import io.graphcrud.model.SourceAnchor;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;

public final class EclipseJdtJavaSourceAnalyzer implements JavaSourceAnalyzer {
    private static final String ADAPTER = "eclipse-jdt";
    private static final int MAX_POLYMORPHIC_CANDIDATES_PER_INVOCATION = 100;

    @Override
    public JavaAnalysisResult analyze(JavaAnalysisInput input) {
        try {
            var projectRoot = input.projectRoot().toRealPath();
            var roots = resolveSourceRoots(input, projectRoot);
            var sources = discoverSources(roots, projectRoot);
            var facts = new ArrayList<CanonicalFact>();

            var parser = ASTParser.newParser(AST.JLS21);
            var options = new HashMap<String, String>();
            JavaCore.setComplianceOptions(JavaCore.VERSION_21, options);
            options.put(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES, JavaCore.DISABLED);
            parser.setCompilerOptions(options);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setStatementsRecovery(true);
            parser.setEnvironment(
                    absolutePaths(input.buildMetadata().classpathEntries()),
                    roots.stream().map(root -> root.path().toString()).toArray(String[]::new),
                    roots.stream().map(root -> root.charset().name()).toArray(String[]::new),
                    input.buildMetadata().includeRunningVmBootclasspath());

            var encodings = sources.stream().map(SourceFile::charset).map(Charset::name).toArray(String[]::new);
            var requestor = new FactRequestor(input, projectRoot, facts);
            parser.createASTs(
                    sources.stream().map(SourceFile::path).map(Path::toString).toArray(String[]::new),
                    encodings,
                    new String[0],
                    requestor,
                    null);
            requestor.addPolymorphicCandidates();
            facts.sort(Comparator.comparing(CanonicalFact::factKind).thenComparing(CanonicalFact::canonicalId));
            return new JavaAnalysisResult(
                    facts, requestor.isPartial() ? SnapshotCompletion.PARTIAL : SnapshotCompletion.COMPLETE);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot analyze Java sources", exception);
        }
    }

    private static List<SourceRoot> resolveSourceRoots(JavaAnalysisInput input, Path projectRoot) throws IOException {
        var roots = new ArrayList<SourceRoot>();
        for (var entry : input.buildMetadata().sourceRoots().entrySet()) {
            var path = entry.getKey().toRealPath();
            if (!path.startsWith(projectRoot)) {
                throw new IllegalArgumentException("source root escapes Analysis Project: " + entry.getKey());
            }
            roots.add(new SourceRoot(path, entry.getValue()));
        }
        roots.sort(Comparator.comparing(root -> root.path().toString()));
        return roots;
    }

    private static List<SourceFile> discoverSources(List<SourceRoot> roots, Path projectRoot) throws IOException {
        var sources = new ArrayList<SourceFile>();
        var ignoreRules = IgnoreRules.load(projectRoot);
        for (var root : roots) {
            try (var paths = Files.walk(root.path())) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !ignoreRules.ignores(projectRoot.relativize(path)))
                        .forEach(path -> sources.add(new SourceFile(path, root.charset())));
            }
        }
        sources.sort(Comparator.comparing(source -> source.path().toString()));
        return sources;
    }

    private static String[] absolutePaths(List<Path> paths) {
        return paths.stream().map(Path::normalize).map(Path::toString).toArray(String[]::new);
    }

    private record SourceRoot(Path path, Charset charset) {}

    private record SourceFile(Path path, Charset charset) {}

    private static final class IgnoreRules {
        private static final Set<String> BUILT_IN_DIRECTORIES = Set.of(
                ".git", ".gradle", ".idea", "build", "target", "out", "node_modules",
                "generated", "generated-sources");

        private final List<Pattern> customerPatterns;

        private IgnoreRules(List<Pattern> customerPatterns) {
            this.customerPatterns = customerPatterns;
        }

        private static IgnoreRules load(Path projectRoot) throws IOException {
            var ignoreFile = projectRoot.resolve(".graphcrudignore");
            if (!Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS)) {
                return new IgnoreRules(List.of());
            }
            var patterns = Files.readAllLines(ignoreFile).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(IgnoreRules::compileGlob)
                    .toList();
            return new IgnoreRules(patterns);
        }

        private boolean ignores(Path relativePath) {
            for (var segment : relativePath) {
                if (BUILT_IN_DIRECTORIES.contains(segment.toString().toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            }
            var normalized = relativePath.toString().replace('\\', '/');
            return customerPatterns.stream().anyMatch(pattern -> pattern.matcher(normalized).matches());
        }

        private static Pattern compileGlob(String rawPattern) {
            var pattern = rawPattern.replace('\\', '/');
            if (pattern.startsWith("/")) {
                pattern = pattern.substring(1);
            }
            boolean directory = pattern.endsWith("/");
            if (directory) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            var regex = new StringBuilder();
            if (!pattern.contains("/")) {
                regex.append("(?:^|.*/)");
            } else {
                regex.append('^');
            }
            for (int index = 0; index < pattern.length(); index++) {
                char character = pattern.charAt(index);
                if (character == '*') {
                    if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '*') {
                        regex.append(".*");
                        index++;
                    } else {
                        regex.append("[^/]*");
                    }
                } else if (character == '?') {
                    regex.append("[^/]");
                } else {
                    if (".[]{}()+-^$|".indexOf(character) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(character);
                }
            }
            regex.append(directory ? "(?:/.*)?$" : "$");
            return Pattern.compile(regex.toString());
        }
    }

    private static final class FactRequestor extends FileASTRequestor {
        private final JavaAnalysisInput input;
        private final Path projectRoot;
        private final List<CanonicalFact> facts;
        private final List<IMethodBinding> sourceMethods = new ArrayList<>();
        private final List<ITypeBinding> sourceTypes = new ArrayList<>();
        private final List<PendingPolymorphicCall> pendingPolymorphicCalls = new ArrayList<>();
        private final List<BeanCandidate> beanCandidates = new ArrayList<>();
        private final List<PendingInjection> pendingInjections = new ArrayList<>();
        private final List<PendingScheduled> pendingScheduled = new ArrayList<>();
        private boolean schedulingEnabled;
        private boolean partial;

        private FactRequestor(JavaAnalysisInput input, Path projectRoot, List<CanonicalFact> facts) {
            this.input = input;
            this.projectRoot = projectRoot;
            this.facts = facts;
        }

        private boolean isPartial() {
            return partial;
        }

        @Override
        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
            var sourceFile = Path.of(sourceFilePath);
            var relativePath = projectRoot.relativize(sourceFile).toString().replace('\\', '/');
            var sourceId = NodeId.of(NodeKind.SOURCE_FILE, Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "path", relativePath));
            facts.add(new NodeFact(sourceId, Map.of("displayName", sourceFile.getFileName().toString())));
            facts.add(EvidenceOccurrence.of(
                    sourceId, input.snapshotId(), ADAPTER, new SourceAnchor(relativePath, 1, 1),
                    EvidenceLevel.CONFIRMED, "Discovered Java source within the Analysis Project."));

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    if (isConfirmed(node.resolveBinding())) {
                        sourceTypes.add(node.resolveBinding());
                    }
                    if (hasAnnotation(node, "org.springframework.scheduling.annotation.EnableScheduling")) {
                        schedulingEnabled = true;
                    }
                    addManagedBean(node, ast, relativePath);
                    addFilterRegistration(node, ast, relativePath);
                    return true;
                }

                @Override
                public boolean visit(FieldDeclaration node) {
                    addAutowiredFilterField(node, ast, relativePath);
                    addResourceField(node, ast, relativePath);
                    return true;
                }

                @Override
                public boolean visit(MethodDeclaration node) {
                    addMethod(node, ast, relativePath);
                    addHttpEndpoint(node, ast, relativePath);
                    addScheduledMethod(node, ast, relativePath);
                    addStartupMethod(node, ast, relativePath);
                    addEventListener(node, ast, relativePath);
                    return true;
                }
            });
            ast.accept(new ASTVisitor() {
                private final Map<MethodDeclaration, Integer> jdbcOrdinals = new HashMap<>();

                @Override
                public boolean visit(MethodInvocation invocation) {
                    addInvocation(invocation, ast, relativePath, jdbcOrdinals);
                    return true;
                }
            });
        }

        private void addMethod(MethodDeclaration declaration, CompilationUnit ast, String relativePath) {
            IMethodBinding binding = declaration.resolveBinding();
            if (binding == null || binding.isRecovered() || binding.getDeclaringClass() == null) {
                return;
            }
            var methodId = NodeId.javaMethod(
                    input.projectId().value(),
                    input.buildMetadata().moduleName(),
                    "java",
                    binding.getDeclaringClass().getQualifiedName(),
                    binding.getName(),
                    descriptor(binding));
            sourceMethods.add(binding.getMethodDeclaration());
            facts.add(new NodeFact(methodId, Map.of("displayName", binding.getName())));
            facts.add(EvidenceOccurrence.of(
                    methodId,
                    input.snapshotId(),
                    ADAPTER,
                    new SourceAnchor(
                            relativePath,
                            ast.getLineNumber(declaration.getStartPosition()),
                            ast.getColumnNumber(declaration.getStartPosition()) + 1),
                    EvidenceLevel.CONFIRMED,
                    "JDT resolved the Java method declaration uniquely."));
            if (declaration.isConstructor() && declaration.getParent() instanceof TypeDeclaration type
                    && isManagedBean(type)) {
                for (var parameterObject : declaration.parameters()) {
                    var parameter = (org.eclipse.jdt.core.dom.SingleVariableDeclaration) parameterObject;
                    var requiredType = parameter.getType().resolveBinding();
                    if (requiredType != null && !requiredType.isRecovered()) {
                        pendingInjections.add(new PendingInjection(
                                methodId, requiredType, "", false, sourceAnchor(ast, relativePath, parameter)));
                    }
                }
            }
        }

        private void addManagedBean(TypeDeclaration declaration, CompilationUnit ast, String relativePath) {
            var binding = declaration.resolveBinding();
            if (!isConfirmed(binding) || !isManagedBean(declaration)) {
                return;
            }
            var constructors = java.util.Arrays.stream(binding.getDeclaredMethods())
                    .filter(IMethodBinding::isConstructor)
                    .filter(FactRequestor::isConfirmed)
                    .map(this::nodeId)
                    .sorted(Comparator.comparing(NodeId::canonicalValue))
                    .toList();
            if (constructors.size() != 1) {
                return;
            }
            var constructorId = constructors.get(0);
            beanCandidates.add(new BeanCandidate(binding, constructorId, beanName(declaration, binding)));
            facts.add(EvidenceOccurrence.of(
                    constructorId, input.snapshotId(), ADAPTER, sourceAnchor(ast, relativePath, declaration),
                    EvidenceLevel.CONFIRMED, "Spring stereotype declares a managed-bean candidate with one constructor."));
        }

        private void addHttpEndpoint(MethodDeclaration declaration, CompilationUnit ast, String relativePath) {
            if (!(declaration.getParent() instanceof TypeDeclaration type)
                    || !hasAnnotation(type, "org.springframework.web.bind.annotation.RestController")) {
                return;
            }
            var mapping = findHttpMapping(declaration);
            var binding = declaration.resolveBinding();
            if (mapping == null || !isConfirmed(binding)) {
                return;
            }
            var typeMapping = findAnnotation(type, "org.springframework.web.bind.annotation.RequestMapping");
            var rawRoute = joinRoutes(annotationValue(typeMapping), annotationValue(mapping.annotation()));
            if (rawRoute.contains("${") || rawRoute.contains("#{")) {
                partial = true;
                facts.add(EvidenceOccurrence.of(
                        nodeId(binding), input.snapshotId(), ADAPTER,
                        sourceAnchor(ast, relativePath, mapping.annotation()), EvidenceLevel.UNRESOLVED,
                        "SPRING_MAPPING_DYNAMIC: raw=" + rawRoute));
                return;
            }
            var routeShape = rawRoute.replaceAll("\\{[^/{}]+}", "{}");
            var identity = Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "httpMethod", mapping.httpMethod(),
                    "rawRoute", rawRoute,
                    "routeShape", routeShape);
            var invocationSource = NodeId.of(NodeKind.INVOCATION_SOURCE, identity);
            var endpoint = NodeId.of(NodeKind.HTTP_ENDPOINT_BINDING, identity);
            var properties = Map.of(
                    "displayName", mapping.httpMethod() + " " + rawRoute,
                    "httpMethod", mapping.httpMethod(),
                    "rawRoute", rawRoute,
                    "routeShape", routeShape);
            facts.add(new NodeFact(invocationSource, properties));
            facts.add(new NodeFact(endpoint, properties));
            var methodAnchor = sourceAnchor(ast, relativePath, mapping.annotation());
            facts.add(EvidenceOccurrence.of(
                    invocationSource, input.snapshotId(), ADAPTER, methodAnchor, EvidenceLevel.CONFIRMED,
                    "Spring MVC mapping declares an HTTP Invocation Source."));
            facts.add(EvidenceOccurrence.of(
                    endpoint, input.snapshotId(), ADAPTER, methodAnchor, EvidenceLevel.CONFIRMED,
                    "Spring MVC composed constant type and method mappings."));
            if (typeMapping != null) {
                facts.add(EvidenceOccurrence.of(
                        endpoint, input.snapshotId(), ADAPTER, sourceAnchor(ast, relativePath, typeMapping),
                        EvidenceLevel.CONFIRMED, "Spring MVC type-level mapping contributes the route prefix."));
            }
            addRelationship(
                    invocationSource, RelationshipType.ROUTES_TO, endpoint, methodAnchor,
                    "The HTTP Invocation Source resolves to this endpoint binding.");
            addRelationship(
                    endpoint, RelationshipType.ROUTES_TO, nodeId(binding), methodAnchor,
                    "The Spring MVC endpoint binding resolves to this Java method.");
        }

        private void addFilterRegistration(
                TypeDeclaration declaration, CompilationUnit ast, String relativePath) {
            var registration = findAnnotation(declaration, "jakarta.servlet.annotation.WebFilter");
            var type = declaration.resolveBinding();
            if (registration == null || !isConfirmed(type) || !implementsType(type, "jakarta.servlet.Filter")) {
                return;
            }
            var doFilter = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(FactRequestor::isConfirmed)
                    .filter(method -> method.getName().equals("doFilter") && method.getParameterTypes().length == 3)
                    .findFirst()
                    .orElse(null);
            if (doFilter == null) {
                return;
            }
            var urlPattern = annotationValue(registration);
            var sourceId = NodeId.of(NodeKind.INVOCATION_SOURCE, Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "invocationKind", "servlet-filter",
                    "filterType", type.getQualifiedName(),
                    "urlPattern", urlPattern));
            facts.add(new NodeFact(sourceId, Map.of(
                    "displayName", "FILTER " + urlPattern,
                    "invocationKind", "servlet-filter",
                    "urlPattern", urlPattern)));
            var anchor = sourceAnchor(ast, relativePath, registration);
            facts.add(EvidenceOccurrence.of(
                    sourceId, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                    "Jakarta @WebFilter explicitly registers this servlet filter."));
            addRelationship(
                    sourceId, RelationshipType.ROUTES_TO, nodeId(doFilter), anchor,
                    "The evidenced servlet filter registration invokes this doFilter method.");
        }

        private void addAutowiredFilterField(
                FieldDeclaration field, CompilationUnit ast, String relativePath) {
            if (findAnnotation(field, "org.springframework.beans.factory.annotation.Autowired") == null
                    || !(field.getParent() instanceof TypeDeclaration type)
                    || !isManagedBean(type)) {
                return;
            }
            var typeBinding = type.resolveBinding();
            if (!isConfirmed(typeBinding) || !implementsType(typeBinding, "jakarta.servlet.Filter")) {
                return;
            }
            var doFilter = java.util.Arrays.stream(typeBinding.getDeclaredMethods())
                    .filter(FactRequestor::isConfirmed)
                    .filter(method -> method.getName().equals("doFilter") && method.getParameterTypes().length == 3)
                    .findFirst()
                    .orElse(null);
            if (doFilter == null || field.fragments().size() != 1) {
                return;
            }
            var fragment = (org.eclipse.jdt.core.dom.VariableDeclarationFragment) field.fragments().get(0);
            var variable = fragment.resolveBinding();
            if (variable != null && variable.getType() != null && !variable.getType().isRecovered()) {
                pendingInjections.add(new PendingInjection(
                        nodeId(doFilter), variable.getType(), "", false, sourceAnchor(ast, relativePath, field)));
            }
        }

        private void addScheduledMethod(
                MethodDeclaration declaration, CompilationUnit ast, String relativePath) {
            var annotation = findAnnotation(declaration, "org.springframework.scheduling.annotation.Scheduled");
            var binding = declaration.resolveBinding();
            if (annotation != null && isConfirmed(binding)) {
                pendingScheduled.add(new PendingScheduled(
                        nodeId(binding), annotationMemberValue(annotation, "fixedDelay"),
                        sourceAnchor(ast, relativePath, annotation)));
            }
        }

        private void addStartupMethod(
                MethodDeclaration declaration, CompilationUnit ast, String relativePath) {
            var binding = declaration.resolveBinding();
            if (!isConfirmed(binding)) {
                return;
            }
            String invocationKind = null;
            if (isJavaMain(binding)) {
                invocationKind = "main";
            } else if (declaration.getParent() instanceof TypeDeclaration type && isManagedBean(type)) {
                var declaringType = binding.getDeclaringClass();
                if (binding.getName().equals("run")
                        && implementsType(declaringType, "org.springframework.boot.ApplicationRunner")) {
                    invocationKind = "application-runner";
                } else if (binding.getName().equals("run")
                        && implementsType(declaringType, "org.springframework.boot.CommandLineRunner")) {
                    invocationKind = "command-line-runner";
                }
            }
            if (invocationKind == null) {
                return;
            }
            var target = nodeId(binding);
            var source = NodeId.of(NodeKind.INVOCATION_SOURCE, Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "invocationKind", invocationKind,
                    "target", target.canonicalValue()));
            facts.add(new NodeFact(source, Map.of("displayName", invocationKind, "invocationKind", invocationKind)));
            var anchor = sourceAnchor(ast, relativePath, declaration);
            facts.add(EvidenceOccurrence.of(
                    source, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                    "A supported application-startup runtime contract declares this Invocation Source."));
            addRelationship(
                    source, RelationshipType.ROUTES_TO, target, anchor,
                    "The supported application-startup runtime invokes this Java method.");
        }

        private static boolean isJavaMain(IMethodBinding binding) {
            var parameters = binding.getParameterTypes();
            return binding.getName().equals("main")
                    && Modifier.isPublic(binding.getModifiers())
                    && Modifier.isStatic(binding.getModifiers())
                    && parameters.length == 1
                    && parameters[0].isArray()
                    && parameters[0].getElementType().getErasure().getQualifiedName().equals("java.lang.String")
                    && binding.getReturnType().getName().equals("void");
        }

        private void addEventListener(
                MethodDeclaration declaration, CompilationUnit ast, String relativePath) {
            var annotation = findAnnotation(declaration, "org.springframework.context.event.EventListener");
            var binding = declaration.resolveBinding();
            if (annotation == null || !isConfirmed(binding)
                    || !(declaration.getParent() instanceof TypeDeclaration type) || !isManagedBean(type)) {
                return;
            }
            var eventTypes = new ArrayList<ITypeBinding>();
            if (binding.getParameterTypes().length == 1) {
                eventTypes.add(binding.getParameterTypes()[0]);
            } else if (annotation.resolveAnnotationBinding() != null) {
                for (var pair : annotation.resolveAnnotationBinding().getDeclaredMemberValuePairs()) {
                    if (pair.getName().equals("classes")) {
                        var value = pair.getValue();
                        if (value instanceof ITypeBinding typeBinding) {
                            eventTypes.add(typeBinding);
                        } else if (value instanceof Object[] values) {
                            for (var candidate : values) {
                                if (candidate instanceof ITypeBinding typeBinding) {
                                    eventTypes.add(typeBinding);
                                }
                            }
                        }
                    }
                }
            }
            var target = nodeId(binding);
            var anchor = sourceAnchor(ast, relativePath, annotation);
            if (eventTypes.isEmpty() || eventTypes.stream().anyMatch(eventType -> !isConfirmed(eventType))) {
                partial = true;
                facts.add(EvidenceOccurrence.of(
                        target, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.UNRESOLVED,
                        "SPRING_EVENT_TYPE_UNRESOLVED: raw=" + annotation));
                return;
            }
            var condition = annotationMemberValue(annotation, "condition");
            eventTypes.stream()
                    .map(ITypeBinding::getErasure)
                    .distinct()
                    .sorted(Comparator.comparing(ITypeBinding::getQualifiedName))
                    .forEach(eventType -> addResolvedEventBinding(target, eventType, condition, anchor));
        }

        private void addResolvedEventBinding(
                NodeId target, ITypeBinding eventType, String condition, SourceAnchor anchor) {
            var eventName = eventType.getErasure().getQualifiedName();
            var source = NodeId.of(NodeKind.INVOCATION_SOURCE, Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "invocationKind", "spring-event",
                    "eventType", eventName,
                    "target", target.canonicalValue()));
            var properties = new HashMap<String, String>();
            properties.put("displayName", "EVENT " + eventName);
            properties.put("invocationKind", "spring-event");
            properties.put("eventType", eventName);
            if (!condition.isBlank()) {
                properties.put("condition", condition);
            }
            facts.add(new NodeFact(source, properties));
            if (!condition.isBlank()) {
                facts.add(EvidenceOccurrence.of(
                        source, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.POSSIBLE,
                        "SPRING_EVENT_CONDITION_RUNTIME: " + condition));
                addPossibleInvocationBinding(
                        source, target, anchor, "SPRING_EVENT_CONDITION_RUNTIME: " + condition);
                return;
            }
            facts.add(EvidenceOccurrence.of(
                    source, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                    "@EventListener declares one resolved event type."));
            addRelationship(
                    source, RelationshipType.ROUTES_TO, target, anchor,
                    "The resolved Spring event listener invokes this Java method.");
        }

        private void addResourceField(FieldDeclaration field, CompilationUnit ast, String relativePath) {
            var annotation = findAnnotation(field, "jakarta.annotation.Resource");
            if (annotation == null || !(field.getParent() instanceof TypeDeclaration type) || !isManagedBean(type)) {
                return;
            }
            var scheduledMethod = java.util.Arrays.stream(type.getMethods())
                    .filter(method -> findAnnotation(method, "org.springframework.scheduling.annotation.Scheduled") != null)
                    .map(MethodDeclaration::resolveBinding)
                    .filter(FactRequestor::isConfirmed)
                    .findFirst()
                    .orElse(null);
            if (scheduledMethod == null || field.fragments().size() != 1) {
                return;
            }
            var fragment = (org.eclipse.jdt.core.dom.VariableDeclarationFragment) field.fragments().get(0);
            var variable = fragment.resolveBinding();
            if (variable != null && variable.getType() != null && !variable.getType().isRecovered()) {
                var explicitName = annotationMemberValue(annotation, "name");
                pendingInjections.add(new PendingInjection(
                        nodeId(scheduledMethod), variable.getType(),
                        explicitName.isBlank() ? fragment.getName().getIdentifier() : explicitName,
                        !explicitName.isBlank(),
                        sourceAnchor(ast, relativePath, field)));
            }
        }

        private void addInvocation(
                MethodInvocation invocation,
                CompilationUnit ast,
                String relativePath,
                Map<MethodDeclaration, Integer> jdbcOrdinals) {
            var declaration = enclosingMethod(invocation);
            var sourceBinding = declaration == null ? null : declaration.resolveBinding();
            var targetBinding = invocation.resolveMethodBinding();
            if (!isConfirmed(sourceBinding)) {
                return;
            }
            if (!isConfirmed(targetBinding)) {
                partial = true;
                facts.add(EvidenceOccurrence.of(
                        nodeId(sourceBinding),
                        input.snapshotId(),
                        ADAPTER,
                        sourceAnchor(ast, relativePath, invocation),
                        EvidenceLevel.UNRESOLVED,
                        unresolvedInvocationExplanation(invocation, ast, targetBinding)));
                return;
            }
            if (isDirectJdbcSql(targetBinding) && !invocation.arguments().isEmpty()) {
                int ordinal = jdbcOrdinals.merge(declaration, 1, Integer::sum);
                var sqlExpression = (Expression) invocation.arguments().get(0);
                var constant = sqlExpression.resolveConstantExpressionValue();
                if (constant instanceof String sql) {
                    addSqlInvocation(nodeId(sourceBinding), sql, ordinal, invocation, ast, relativePath);
                } else {
                    addUnresolvedSqlInvocation(
                            nodeId(sourceBinding), sqlExpression.toString(), ordinal, invocation, ast, relativePath);
                }
                return;
            }
            var assertion = RelationshipAssertion.of(
                    nodeId(sourceBinding), RelationshipType.CALLS, nodeId(targetBinding), Map.of());
            addAssertionOnce(assertion);
            var anchor = sourceAnchor(ast, relativePath, invocation);
            facts.add(EvidenceOccurrence.of(
                    assertion.id(), input.snapshotId(), ADAPTER, anchor,
                    EvidenceLevel.CONFIRMED, "JDT resolved the direct Java call target uniquely."));
            if (isPolymorphicDispatch(targetBinding)) {
                pendingPolymorphicCalls.add(
                        new PendingPolymorphicCall(nodeId(sourceBinding), targetBinding.getMethodDeclaration(), anchor));
            }
        }

        private void addPolymorphicCandidates() {
            for (var pending : pendingPolymorphicCalls) {
                var candidates = sourceMethods.stream()
                        .filter(FactRequestor::isConcreteInstanceMethod)
                        .filter(candidate -> candidate.overrides(pending.declaredTarget()))
                        .map(this::nodeId)
                        .distinct()
                        .sorted(Comparator.comparing(NodeId::canonicalValue))
                        .toList();
                for (var candidate : candidates.stream()
                        .limit(MAX_POLYMORPHIC_CANDIDATES_PER_INVOCATION)
                        .toList()) {
                    var assertion = RelationshipAssertion.of(
                            pending.source(), RelationshipType.CALLS, candidate, Map.of());
                    addAssertionOnce(assertion);
                    facts.add(EvidenceOccurrence.of(
                            assertion.id(), input.snapshotId(), ADAPTER, pending.anchor(), EvidenceLevel.POSSIBLE,
                            "JAVA_POLYMORPHIC_CANDIDATE: project source overrides the declared call target."));
                }
                if (candidates.size() > MAX_POLYMORPHIC_CANDIDATES_PER_INVOCATION) {
                    partial = true;
                    var declaredAssertion = RelationshipAssertion.of(
                            pending.source(), RelationshipType.CALLS, nodeId(pending.declaredTarget()), Map.of());
                    facts.add(EvidenceOccurrence.of(
                            declaredAssertion.id(), input.snapshotId(), ADAPTER, pending.anchor(),
                            EvidenceLevel.UNRESOLVED,
                            "JAVA_POLYMORPHIC_CANDIDATE_LIMIT: retained "
                                    + MAX_POLYMORPHIC_CANDIDATES_PER_INVOCATION + " of " + candidates.size()
                                    + " source candidates."));
                }
            }
            addConfiguredConsumers();
            addLegacyUrlBindings();
            addManualUiActions();
            sourceMethods.clear();
            sourceTypes.clear();
            pendingPolymorphicCalls.clear();
            addScheduledBindings();
            addConstructorInjections();
        }

        private void addConfiguredConsumers() {
            var manifest = projectRoot.resolve(".graphcrud-consumers");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try {
                var lines = Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    var raw = lines.get(index).trim();
                    if (raw.isEmpty() || raw.startsWith("#") || !raw.contains("=")) {
                        continue;
                    }
                    var parts = raw.split("=", 2);
                    var key = parts[0].trim();
                    var rawTarget = parts[1].trim();
                    var targetParts = rawTarget.split("#", 2);
                    var anchor = new SourceAnchor(".graphcrud-consumers", index + 1, 1);
                    var entry = NodeId.of(NodeKind.CONFIGURATION_ENTRY, Map.of(
                            "project", input.projectId().value(),
                            "module", input.buildMetadata().moduleName(),
                            "path", ".graphcrud-consumers",
                            "key", key));
                    facts.add(new NodeFact(entry, Map.of(
                            "displayName", key, "rawTarget", rawTarget,
                            "resolutionReason", "explicit-consumer-manifest")));
                    facts.add(EvidenceOccurrence.of(
                            entry, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                            "Explicit consumer Configuration Entry."));
                    if (targetParts.length != 2) {
                        addUnresolvedConsumer(entry, anchor, rawTarget, "invalid-target-format");
                        continue;
                    }
                    var candidates = sourceMethods.stream()
                            .filter(FactRequestor::isConfirmed)
                            .filter(method -> method.getDeclaringClass().getErasure().getQualifiedName()
                                    .equals(targetParts[0]))
                            .filter(method -> method.getName().equals(targetParts[1]))
                            .map(this::nodeId)
                            .distinct()
                            .sorted(Comparator.comparing(NodeId::canonicalValue))
                            .toList();
                    if (candidates.size() != 1) {
                        addUnresolvedConsumer(entry, anchor, rawTarget, "target-count=" + candidates.size());
                        continue;
                    }
                    var source = NodeId.of(NodeKind.INVOCATION_SOURCE, Map.of(
                            "project", input.projectId().value(),
                            "module", input.buildMetadata().moduleName(),
                            "invocationKind", "configured-consumer",
                            "consumerKey", key));
                    facts.add(new NodeFact(source, Map.of(
                            "displayName", key, "invocationKind", "configured-consumer", "rawTarget", rawTarget)));
                    facts.add(EvidenceOccurrence.of(
                            source, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                            "Explicit consumer configuration resolved one Java target."));
                    addRelationship(entry, RelationshipType.ROUTES_TO, source, anchor,
                            "The Configuration Entry declares this consumer Invocation Source.");
                    addRelationship(source, RelationshipType.ROUTES_TO, candidates.get(0), anchor,
                            "The explicit raw target resolves uniquely to this Java method.");
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("cannot read explicit consumer manifest", exception);
            }
        }

        private void addUnresolvedConsumer(
                NodeId entry, SourceAnchor anchor, String rawTarget, String reason) {
            partial = true;
            facts.add(EvidenceOccurrence.of(
                    entry, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.UNRESOLVED,
                    "CONFIGURED_CONSUMER_UNRESOLVED: raw=" + rawTarget + " reason=" + reason));
        }

        private void addLegacyUrlBindings() {
            var manifest = projectRoot.resolve(".graphcrud-legacy-urls");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try {
                var lines = Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    var raw = lines.get(index).trim();
                    if (raw.isEmpty() || raw.startsWith("#") || !raw.contains("=")) {
                        continue;
                    }
                    var parts = raw.split("=", 2);
                    var route = parts[0].trim();
                    var rawTarget = parts[1].trim();
                    var targetParts = rawTarget.split("#", 2);
                    var anchor = new SourceAnchor(".graphcrud-legacy-urls", index + 1, 1);
                    var entry = NodeId.of(NodeKind.CONFIGURATION_ENTRY, Map.of(
                            "project", input.projectId().value(), "module", input.buildMetadata().moduleName(),
                            "path", ".graphcrud-legacy-urls", "key", route));
                    var routeShape = route.replaceAll("\\{[^/{}]+}", "{}");
                    facts.add(new NodeFact(entry, Map.of("displayName", route, "rawTarget", rawTarget)));
                    facts.add(EvidenceOccurrence.of(
                            entry, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                            "Explicit legacy URL Configuration Entry."));
                    var source = NodeId.of(NodeKind.INVOCATION_SOURCE, Map.of(
                            "project", input.projectId().value(), "module", input.buildMetadata().moduleName(),
                            "invocationKind", "legacy-url", "httpMethod", "ANY", "rawRoute", route,
                            "routeShape", routeShape));
                    facts.add(new NodeFact(source, Map.of(
                            "displayName", "ANY " + route, "invocationKind", "legacy-url",
                            "httpMethod", "ANY", "rawRoute", route, "routeShape", routeShape)));
                    facts.add(EvidenceOccurrence.of(
                            source, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                            "Legacy URL configuration has no HTTP-method restriction."));
                    addRelationship(entry, RelationshipType.ROUTES_TO, source, anchor,
                            "The Configuration Entry declares this legacy URL Invocation Source.");
                    if (targetParts.length == 1) {
                        var types = sourceTypes.stream()
                                .filter(type -> type.getErasure().getQualifiedName().equals(targetParts[0]))
                                .toList();
                        if (types.size() == 1) {
                            var typeId = NodeId.of(NodeKind.JAVA_TYPE, Map.of(
                                    "project", input.projectId().value(),
                                    "module", input.buildMetadata().moduleName(),
                                    "language", "java", "qualifiedName", targetParts[0]));
                            facts.add(new NodeFact(typeId, Map.of(
                                    "displayName", types.get(0).getName(), "qualifiedName", targetParts[0])));
                            facts.add(EvidenceOccurrence.of(
                                    typeId, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                                    "Legacy class-only target resolves to one Java type; no method was inferred."));
                            addRelationship(source, RelationshipType.ROUTES_TO, typeId, anchor,
                                    "The class-only legacy target resolves to this Java type.");
                        }
                        continue;
                    }
                    var methods = sourceMethods.stream()
                            .filter(method -> method.getDeclaringClass().getErasure().getQualifiedName()
                                    .equals(targetParts[0]))
                            .filter(method -> method.getName().equals(targetParts[1]))
                            .map(this::nodeId).distinct().toList();
                    if (methods.size() == 1) {
                        addRelationship(source, RelationshipType.ROUTES_TO, methods.get(0), anchor,
                                "The explicit legacy class and method resolve uniquely.");
                    } else {
                        partial = true;
                        facts.add(EvidenceOccurrence.of(
                                entry, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.UNRESOLVED,
                                "LEGACY_URL_TARGET_AMBIGUOUS: raw=" + rawTarget + " candidates=" + methods.size()));
                    }
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("cannot read legacy URL manifest", exception);
            }
        }

        private void addManualUiActions() {
            var manifest = projectRoot.resolve(".graphcrud-ui-actions");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try {
                var lines = Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    var raw = lines.get(index).trim();
                    if (raw.isEmpty() || raw.startsWith("#") || !raw.contains("=")) {
                        continue;
                    }
                    var parts = raw.split("=", 2);
                    var label = parts[0].trim();
                    var rawTarget = parts[1].trim();
                    var targetParts = rawTarget.split("#", 2);
                    var anchor = new SourceAnchor(".graphcrud-ui-actions", index + 1, 1);
                    var action = NodeId.of(NodeKind.UI_ACTION, Map.of(
                            "project", input.projectId().value(), "module", input.buildMetadata().moduleName(),
                            "manifest", ".graphcrud-ui-actions", "label", label));
                    facts.add(new NodeFact(action, Map.of(
                            "displayName", label, "label", label, "rawTarget", rawTarget)));
                    facts.add(EvidenceOccurrence.of(
                            action, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED,
                            "Manually supplied UI Action."));
                    var candidates = targetParts.length == 2
                            ? sourceMethods.stream()
                                    .filter(method -> method.getDeclaringClass().getErasure().getQualifiedName()
                                            .equals(targetParts[0]))
                                    .filter(method -> method.getName().equals(targetParts[1]))
                                    .map(this::nodeId).distinct().toList()
                            : List.<NodeId>of();
                    if (candidates.size() == 1) {
                        addRelationship(action, RelationshipType.ROUTES_TO, candidates.get(0), anchor,
                                "The manually supplied Java target resolves uniquely.");
                    } else {
                        partial = true;
                        facts.add(EvidenceOccurrence.of(
                                action, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.UNRESOLVED,
                                "UI_ACTION_TARGET_UNRESOLVED: raw=" + rawTarget
                                        + " candidates=" + candidates.size()));
                    }
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("cannot read manual UI Action manifest", exception);
            }
        }

        private void addScheduledBindings() {
            if (schedulingEnabled) {
                for (var pending : pendingScheduled) {
                    var source = NodeId.of(NodeKind.INVOCATION_SOURCE, Map.of(
                            "project", input.projectId().value(),
                            "module", input.buildMetadata().moduleName(),
                            "invocationKind", "scheduled",
                            "target", pending.target().canonicalValue(),
                            "schedule", pending.schedule()));
                    facts.add(new NodeFact(source, Map.of(
                            "displayName", "SCHEDULED " + pending.schedule(),
                            "invocationKind", "scheduled",
                            "schedule", pending.schedule())));
                    facts.add(EvidenceOccurrence.of(
                            source, input.snapshotId(), ADAPTER, pending.anchor(), EvidenceLevel.CONFIRMED,
                            "@Scheduled is enabled by a statically resolved @EnableScheduling declaration."));
                    addRelationship(
                            source, RelationshipType.ROUTES_TO, pending.target(), pending.anchor(),
                            "The enabled @Scheduled declaration invokes this Java method.");
                }
            }
            pendingScheduled.clear();
        }

        private void addConstructorInjections() {
            for (var pending : pendingInjections) {
                var typeCandidates = beanCandidates.stream()
                        .filter(candidate -> candidate.type().isEqualTo(pending.requiredType())
                                || candidate.type().isAssignmentCompatible(pending.requiredType()))
                        .sorted(Comparator.comparing(candidate -> candidate.id().canonicalValue()))
                        .toList();
                var namedCandidates = pending.requestedName().isBlank() ? List.<BeanCandidate>of() : typeCandidates.stream()
                        .filter(candidate -> candidate.beanName().equals(pending.requestedName()))
                        .toList();
                var candidates = !namedCandidates.isEmpty()
                        ? namedCandidates
                        : pending.explicitName() ? List.<BeanCandidate>of() : typeCandidates;
                if (candidates.size() == 1) {
                    addRelationship(
                            pending.constructor(), RelationshipType.INJECTS, candidates.get(0).id(), pending.anchor(),
                            "Spring constructor injection has one statically supported bean candidate.");
                } else if (candidates.size() > 1) {
                    for (var candidate : candidates.stream()
                            .limit(MAX_POLYMORPHIC_CANDIDATES_PER_INVOCATION)
                            .toList()) {
                        var assertion = RelationshipAssertion.of(
                                pending.constructor(), RelationshipType.INJECTS, candidate.id(), Map.of());
                        addAssertionOnce(assertion);
                        facts.add(EvidenceOccurrence.of(
                                assertion.id(), input.snapshotId(), ADAPTER, pending.anchor(), EvidenceLevel.POSSIBLE,
                                "SPRING_DI_AMBIGUOUS: multiple statically supported bean candidates."));
                    }
                    if (candidates.size() > MAX_POLYMORPHIC_CANDIDATES_PER_INVOCATION) {
                        partial = true;
                        facts.add(EvidenceOccurrence.of(
                                pending.constructor(), input.snapshotId(), ADAPTER, pending.anchor(),
                                EvidenceLevel.UNRESOLVED,
                                "SPRING_DI_CANDIDATE_LIMIT: retained "
                                        + MAX_POLYMORPHIC_CANDIDATES_PER_INVOCATION + " of " + candidates.size()
                                        + " bean candidates."));
                    }
                } else if (!pending.requestedName().isBlank()) {
                    partial = true;
                    facts.add(EvidenceOccurrence.of(
                            pending.constructor(), input.snapshotId(), ADAPTER, pending.anchor(),
                            EvidenceLevel.UNRESOLVED,
                            "SPRING_RESOURCE_MISSING: name=" + pending.requestedName()
                                    + " type=" + pending.requiredType().getQualifiedName() + " candidates=[]"));
                }
            }
            beanCandidates.clear();
            pendingInjections.clear();
        }

        private static boolean isPolymorphicDispatch(IMethodBinding binding) {
            return !binding.isConstructor()
                    && !Modifier.isStatic(binding.getModifiers())
                    && !Modifier.isPrivate(binding.getModifiers())
                    && (binding.getDeclaringClass().isInterface() || Modifier.isAbstract(binding.getModifiers()));
        }

        private static boolean isConcreteInstanceMethod(IMethodBinding binding) {
            return isConfirmed(binding)
                    && !binding.isConstructor()
                    && !Modifier.isAbstract(binding.getModifiers())
                    && !Modifier.isStatic(binding.getModifiers())
                    && !Modifier.isPrivate(binding.getModifiers());
        }

        private void addSqlInvocation(
                NodeId owner,
                String sql,
                int ordinal,
                MethodInvocation invocation,
                CompilationUnit ast,
                String relativePath) {
            var sqlId = NodeId.of(NodeKind.SQL_STATEMENT, Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "owner", owner.canonicalValue(),
                    "ordinal", Integer.toString(ordinal),
                    "sql", sql));
            facts.add(new NodeFact(sqlId, Map.of("displayName", sql, "sql", sql)));
            facts.add(EvidenceOccurrence.of(
                    sqlId, input.snapshotId(), ADAPTER, sourceAnchor(ast, relativePath, invocation),
                    EvidenceLevel.CONFIRMED, "JDT recovered a direct JDBC string literal."));
            var assertion = RelationshipAssertion.of(owner, RelationshipType.EXECUTES, sqlId, Map.of());
            addAssertionOnce(assertion);
            facts.add(EvidenceOccurrence.of(
                    assertion.id(), input.snapshotId(), ADAPTER, sourceAnchor(ast, relativePath, invocation),
                    EvidenceLevel.CONFIRMED, "The uniquely resolved direct JDBC call executes this SQL Statement."));
        }

        private void addUnresolvedSqlInvocation(
                NodeId owner,
                String expression,
                int ordinal,
                MethodInvocation invocation,
                CompilationUnit ast,
                String relativePath) {
            partial = true;
            var sqlId = NodeId.of(NodeKind.SQL_STATEMENT, Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "owner", owner.canonicalValue(),
                    "ordinal", Integer.toString(ordinal),
                    "expression", expression));
            facts.add(new NodeFact(sqlId, Map.of("displayName", expression, "expression", expression)));
            var explanation = "JAVA_SQL_RUNTIME_DEPENDENT: " + expression;
            facts.add(EvidenceOccurrence.of(
                    sqlId, input.snapshotId(), ADAPTER, sourceAnchor(ast, relativePath, invocation),
                    EvidenceLevel.UNRESOLVED, explanation));
            var assertion = RelationshipAssertion.of(owner, RelationshipType.EXECUTES, sqlId, Map.of());
            addAssertionOnce(assertion);
            facts.add(EvidenceOccurrence.of(
                    assertion.id(), input.snapshotId(), ADAPTER, sourceAnchor(ast, relativePath, invocation),
                    EvidenceLevel.UNRESOLVED, explanation));
        }

        private void addAssertionOnce(RelationshipAssertion assertion) {
            if (facts.stream().noneMatch(fact -> fact instanceof RelationshipAssertion existing
                    && existing.id().equals(assertion.id()))) {
                facts.add(assertion);
            }
        }

        private void addRelationship(
                NodeId source,
                RelationshipType type,
                NodeId target,
                SourceAnchor anchor,
                String explanation) {
            var assertion = RelationshipAssertion.of(source, type, target, Map.of());
            addAssertionOnce(assertion);
            facts.add(EvidenceOccurrence.of(
                    assertion.id(), input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED, explanation));
            if (type == RelationshipType.ROUTES_TO
                    && (source.kind() == NodeKind.INVOCATION_SOURCE || source.kind() == NodeKind.UI_ACTION)
                    && (target.kind() == NodeKind.CODE_SYMBOL || target.kind() == NodeKind.JAVA_TYPE)) {
                addInvocationBinding(source, target, anchor, explanation);
            }
        }

        private void addInvocationBinding(
                NodeId source, NodeId target, SourceAnchor anchor, String explanation) {
            var binding = NodeId.of(NodeKind.INVOCATION_BINDING, Map.of(
                    "project", input.projectId().value(),
                    "module", input.buildMetadata().moduleName(),
                    "source", source.canonicalValue(),
                    "target", target.canonicalValue()));
            facts.add(new NodeFact(binding, Map.of("displayName", "Invocation Binding")));
            facts.add(EvidenceOccurrence.of(
                    binding, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED, explanation));
            var sourceToBinding = RelationshipAssertion.of(source, RelationshipType.ROUTES_TO, binding, Map.of());
            addAssertionOnce(sourceToBinding);
            facts.add(EvidenceOccurrence.of(
                    sourceToBinding.id(), input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED, explanation));
            var bindingToTarget = RelationshipAssertion.of(binding, RelationshipType.ROUTES_TO, target, Map.of());
            addAssertionOnce(bindingToTarget);
            facts.add(EvidenceOccurrence.of(
                    bindingToTarget.id(), input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED, explanation));
        }

        private void addPossibleInvocationBinding(
                NodeId source, NodeId target, SourceAnchor anchor, String explanation) {
            var direct = RelationshipAssertion.of(source, RelationshipType.ROUTES_TO, target, Map.of());
            addAssertionOnce(direct);
            facts.add(EvidenceOccurrence.of(
                    direct.id(), input.snapshotId(), ADAPTER, anchor, EvidenceLevel.POSSIBLE, explanation));
            var binding = NodeId.of(NodeKind.INVOCATION_BINDING, Map.of(
                    "project", input.projectId().value(), "module", input.buildMetadata().moduleName(),
                    "source", source.canonicalValue(), "target", target.canonicalValue()));
            facts.add(new NodeFact(binding, Map.of("displayName", "Invocation Binding")));
            facts.add(EvidenceOccurrence.of(
                    binding, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.POSSIBLE, explanation));
            for (var assertion : List.of(
                    RelationshipAssertion.of(source, RelationshipType.ROUTES_TO, binding, Map.of()),
                    RelationshipAssertion.of(binding, RelationshipType.ROUTES_TO, target, Map.of()))) {
                addAssertionOnce(assertion);
                facts.add(EvidenceOccurrence.of(
                        assertion.id(), input.snapshotId(), ADAPTER, anchor, EvidenceLevel.POSSIBLE, explanation));
            }
        }

        private static boolean isManagedBean(TypeDeclaration declaration) {
            return hasAnnotation(declaration, "org.springframework.web.bind.annotation.RestController")
                    || hasAnnotation(declaration, "org.springframework.stereotype.Controller")
                    || hasAnnotation(declaration, "org.springframework.stereotype.Service")
                    || hasAnnotation(declaration, "org.springframework.stereotype.Component");
        }

        private static boolean implementsType(ITypeBinding type, String qualifiedName) {
            if (type.getErasure().getQualifiedName().equals(qualifiedName)) {
                return true;
            }
            for (var interfaceType : type.getInterfaces()) {
                if (implementsType(interfaceType, qualifiedName)) {
                    return true;
                }
            }
            return type.getSuperclass() != null && implementsType(type.getSuperclass(), qualifiedName);
        }

        private static HttpMapping findHttpMapping(MethodDeclaration declaration) {
            var mappings = Map.of(
                    "org.springframework.web.bind.annotation.GetMapping", "GET",
                    "org.springframework.web.bind.annotation.PostMapping", "POST",
                    "org.springframework.web.bind.annotation.PutMapping", "PUT",
                    "org.springframework.web.bind.annotation.DeleteMapping", "DELETE",
                    "org.springframework.web.bind.annotation.PatchMapping", "PATCH");
            for (var entry : mappings.entrySet()) {
                var annotation = findAnnotation(declaration, entry.getKey());
                if (annotation != null) {
                    return new HttpMapping(entry.getValue(), annotation);
                }
            }
            return null;
        }

        private static boolean hasAnnotation(
                org.eclipse.jdt.core.dom.BodyDeclaration declaration, String qualifiedName) {
            return findAnnotation(declaration, qualifiedName) != null;
        }

        private static Annotation findAnnotation(
                org.eclipse.jdt.core.dom.BodyDeclaration declaration, String qualifiedName) {
            for (var modifier : declaration.modifiers()) {
                if (modifier instanceof Annotation annotation) {
                    var type = annotation.resolveTypeBinding();
                    if (type != null && !type.isRecovered() && type.getQualifiedName().equals(qualifiedName)) {
                        return annotation;
                    }
                }
            }
            return null;
        }

        private static String annotationValue(Annotation annotation) {
            return annotationMemberValue(annotation, "value");
        }

        private static String annotationMemberValue(Annotation annotation, String memberName) {
            if (annotation == null || annotation.resolveAnnotationBinding() == null) {
                return "";
            }
            for (var pair : annotation.resolveAnnotationBinding().getDeclaredMemberValuePairs()) {
                if (pair.getName().equals(memberName)) {
                    var value = pair.getValue();
                    if (value instanceof String string) {
                        return string;
                    }
                    if (value instanceof Object[] values && values.length == 1 && values[0] instanceof String string) {
                        return string;
                    }
                }
            }
            return "";
        }

        private static String beanName(TypeDeclaration declaration, ITypeBinding binding) {
            for (var annotationName : List.of(
                    "org.springframework.stereotype.Component",
                    "org.springframework.stereotype.Service",
                    "org.springframework.stereotype.Controller")) {
                var explicit = annotationValue(findAnnotation(declaration, annotationName));
                if (!explicit.isBlank()) {
                    return explicit;
                }
            }
            var simpleName = binding.getName();
            return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        }

        private static String joinRoutes(String prefix, String suffix) {
            var joined = ("/" + prefix + "/" + suffix).replaceAll("/+", "/");
            return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
        }

        private NodeId nodeId(IMethodBinding binding) {
            return NodeId.javaMethod(
                    input.projectId().value(),
                    input.buildMetadata().moduleName(),
                    "java",
                    binding.getDeclaringClass().getQualifiedName(),
                    binding.getName(),
                    descriptor(binding));
        }

        private static boolean isConfirmed(IMethodBinding binding) {
            return binding != null && !binding.isRecovered() && binding.getDeclaringClass() != null
                    && !binding.getDeclaringClass().isRecovered();
        }

        private static boolean isConfirmed(ITypeBinding binding) {
            return binding != null && !binding.isRecovered();
        }

        private static boolean isDirectJdbcSql(IMethodBinding binding) {
            var declaringType = binding.getDeclaringClass().getErasure().getQualifiedName();
            return (declaringType.equals("java.sql.Connection") && binding.getName().equals("prepareStatement"))
                    || (declaringType.equals("org.springframework.jdbc.core.JdbcTemplate")
                            && Set.of("execute", "query", "update").contains(binding.getName()))
                    || (declaringType.equals(
                                    "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
                            && Set.of("execute", "query", "update").contains(binding.getName()));
        }

        private static MethodDeclaration enclosingMethod(MethodInvocation invocation) {
            var current = invocation.getParent();
            while (current != null && !(current instanceof MethodDeclaration)) {
                current = current.getParent();
            }
            return (MethodDeclaration) current;
        }

        private static String unresolvedInvocationExplanation(
                MethodInvocation invocation, CompilationUnit ast, IMethodBinding binding) {
            var problems = java.util.Arrays.stream(ast.getProblems())
                    .filter(IProblem::isError)
                    .filter(problem -> problem.getSourceStart() <= invocation.getStartPosition() + invocation.getLength()
                            && problem.getSourceEnd() >= invocation.getStartPosition())
                    .sorted(Comparator.comparingInt(IProblem::getSourceStart).thenComparingInt(IProblem::getID))
                    .toList();
            var dependencyProblem = problems.stream()
                    .filter(problem -> problem.getID() == IProblem.UndefinedType
                            || problem.getID() == IProblem.UndefinedName
                            || problem.getID() == IProblem.UndefinedMethod)
                    .findFirst();
            var relevant = dependencyProblem.or(() -> problems.stream().findFirst());
            var reason = dependencyProblem.isPresent()
                    ? "JAVA_DEPENDENCY_MISSING"
                    : relevant.isPresent() ? "JAVA_SYNTAX_RECOVERED"
                    : binding != null && binding.isRecovered() ? "JAVA_BINDING_RECOVERED" : "JAVA_BINDING_NULL";
            var candidate = binding != null && binding.getDeclaringClass() != null
                    ? binding.getDeclaringClass().getQualifiedName() + "#" + binding.getName()
                    : "";
            var problemMetadata = relevant.map(problem -> " problemId=" + problem.getID()
                            + " severity=" + (problem.isError() ? "ERROR" : "WARNING")
                            + " start=" + problem.getSourceStart() + " end=" + problem.getSourceEnd()
                            + " line=" + problem.getSourceLineNumber() + " message=" + problem.getMessage())
                    .orElse("");
            return reason + ": raw=" + invocation + " candidates="
                    + (candidate.isBlank() ? "[]" : "[" + candidate + "]") + problemMetadata;
        }

        private static SourceAnchor sourceAnchor(
                CompilationUnit ast, String relativePath, org.eclipse.jdt.core.dom.ASTNode node) {
            return new SourceAnchor(
                    relativePath,
                    ast.getLineNumber(node.getStartPosition()),
                    ast.getColumnNumber(node.getStartPosition()) + 1);
        }

        private static String descriptor(IMethodBinding binding) {
            var result = new StringBuilder("(");
            for (var parameter : binding.getParameterTypes()) {
                result.append(descriptor(parameter));
            }
            return result.append(')').append(descriptor(binding.getReturnType())).toString();
        }

        private static String descriptor(ITypeBinding type) {
            var erased = type.getErasure();
            if (erased.isPrimitive()) {
                return primitiveDescriptor(erased.getName());
            }
            if (erased.isArray()) {
                return "[" + descriptor(erased.getElementType());
            }
            var binaryName = erased.getBinaryName();
            if (binaryName == null || binaryName.isBlank()) {
                binaryName = erased.getQualifiedName();
            }
            return "L" + binaryName.replace('.', '/') + ";";
        }

        private static String primitiveDescriptor(String name) {
            return switch (name) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "double" -> "D";
                case "float" -> "F";
                case "int" -> "I";
                case "long" -> "J";
                case "short" -> "S";
                case "void" -> "V";
                default -> throw new IllegalArgumentException("unknown primitive type: " + name);
            };
        }

        private record PendingPolymorphicCall(
                NodeId source, IMethodBinding declaredTarget, SourceAnchor anchor) {}

        private record BeanCandidate(ITypeBinding type, NodeId id, String beanName) {}

        private record PendingInjection(
                NodeId constructor,
                ITypeBinding requiredType,
                String requestedName,
                boolean explicitName,
                SourceAnchor anchor) {}

        private record HttpMapping(String httpMethod, Annotation annotation) {}

        private record PendingScheduled(NodeId target, String schedule, SourceAnchor anchor) {}
    }
}
