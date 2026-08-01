package io.graphcrud.infrastructure;

import io.graphcrud.application.JavaAnalysisResult;
import io.graphcrud.application.PersistenceProjectAnalysisInput;
import io.graphcrud.application.PersistenceProjectAnalyzer;
import io.graphcrud.application.PostgreSqlAnalysisInput;
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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Project-level adapter for MyBatis XML and in-root PostgreSQL schema sources. */
public final class MyBatisPostgreSqlProjectAnalyzer implements PersistenceProjectAnalyzer {
    private static final String ADAPTER = "mybatis-postgresql-project";
    private static final Set<String> STATEMENT_ELEMENTS = Set.of("select", "insert", "update", "delete");
    private static final Pattern CREATE_VIEW = Pattern.compile(
            "(?is)create\\s+(?:or\\s+replace\\s+)?view\\s+([^\\s]+)\\s+as\\s+(.+)");
    private static final Pattern CREATE_ROUTINE = Pattern.compile(
            "(?is)create\\s+(?:or\\s+replace\\s+)?(?:function|procedure)\\s+([^\\s(]+)\\s*\\(([^)]*)\\).*?as\\s+\\$\\$(.*?)\\$\\$");
    private static final Pattern CREATE_TRIGGER = Pattern.compile(
            "(?is)create\\s+trigger\\s+([^\\s]+)\\s+(.*?)\\s+on\\s+([^\\s]+).*?execute\\s+(?:function|procedure)\\s+([^\\s(]+)\\s*\\(");
    private static final Pattern CALL_ROUTINE = Pattern.compile("(?is)^\\s*call\\s+([^\\s(]+)\\s*\\((.*?)\\)");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([^\\s(]+)");
    private static final Pattern STATIC_EXECUTE = Pattern.compile("(?is)execute\\s+'((?:''|[^'])*)'");
    private static final Pattern DYNAMIC_EXECUTE = Pattern.compile("(?is)\\bexecute\\s+(?!')([^;]+)");

    @Override
    public JavaAnalysisResult analyze(PersistenceProjectAnalysisInput input) {
        try {
            var root = input.projectRoot().toRealPath();
            var facts = new ArrayList<>(input.javaFacts());
            var state = new State(input, root, facts);
            state.readMyBatisXml();
            state.replaySchemaSources();
            facts.sort(Comparator.comparing(CanonicalFact::factKind).thenComparing(CanonicalFact::canonicalId));
            var unresolved = facts.stream().filter(EvidenceOccurrence.class::isInstance)
                    .map(EvidenceOccurrence.class::cast)
                    .anyMatch(evidence -> evidence.evidenceLevel() == EvidenceLevel.UNRESOLVED);
            return new JavaAnalysisResult(
                    facts, state.partial || unresolved ? SnapshotCompletion.PARTIAL : SnapshotCompletion.COMPLETE);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot analyze persistence project", exception);
        }
    }

    private static final class State {
        private final PersistenceProjectAnalysisInput input;
        private final Path root;
        private final List<CanonicalFact> facts;
        private final Map<String, NodeId> routines = new HashMap<>();
        private final Map<String, List<NodeId>> routinesByName = new HashMap<>();
        private final Map<String, NodeId> views = new HashMap<>();
        private final Set<NodeId> simplyUpdatableViews = new HashSet<>();
        private final Set<String> declaredTables = new HashSet<>();
        private final Set<NodeId> conflictingObjects = new HashSet<>();
        private final Set<String> conflictingRoutineNames = new HashSet<>();
        private boolean partial;

        private State(PersistenceProjectAnalysisInput input, Path root, List<CanonicalFact> facts) {
            this.input = input; this.root = root; this.facts = facts;
        }

        private void readMyBatisXml() throws IOException {
            for (var path : files(".xml")) {
                try {
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    var builder = factory.newDocumentBuilder();
                    builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
                    var document = builder.parse(path.toFile());
                    var mapper = document.getDocumentElement();
                    if (!"mapper".equals(mapper.getTagName())) continue;
                    var namespace = mapper.getAttribute("namespace");
                    var byId = new HashMap<String, List<Element>>();
                    for (int index = 0; index < mapper.getChildNodes().getLength(); index++) {
                        var child = mapper.getChildNodes().item(index);
                        if (child instanceof Element element && STATEMENT_ELEMENTS.contains(element.getTagName())) {
                            byId.computeIfAbsent(element.getAttribute("id"), ignored -> new ArrayList<>()).add(element);
                        }
                    }
                    for (var entry : byId.entrySet()) analyzeStatements(path, namespace, entry.getKey(), entry.getValue());
                } catch (Exception exception) {
                    partial = true;
                    facts.add(EvidenceOccurrence.of(
                            configuration(path), input.snapshotId(), ADAPTER, anchor(path), EvidenceLevel.UNRESOLVED,
                            "MYBATIS_XML_PARSE_FAILURE: " + exception.getClass().getSimpleName()));
                }
            }
        }

        private void analyzeStatements(Path path, String namespace, String id, List<Element> variants) {
            var candidates = mapperMethods(namespace, id);
            if (candidates.size() != 1) {
                partial = true;
                var subject = configuration(path);
                facts.add(new NodeFact(subject, Map.of("namespace", namespace, "statementId", id)));
                facts.add(EvidenceOccurrence.of(subject, input.snapshotId(), ADAPTER, anchor(path),
                        candidates.isEmpty() ? EvidenceLevel.UNRESOLVED : EvidenceLevel.POSSIBLE,
                        "MYBATIS_XML_METHOD_" + (candidates.isEmpty() ? "MISSING" : "AMBIGUOUS")
                                + ": " + namespace + "." + id + " candidates="
                                + candidates.stream().map(NodeId::canonicalValue).sorted().toList()));
                return;
            }
            var selected = selectDatabaseVariant(variants);
            if (selected.isEmpty()) {
                partial = true;
                facts.add(EvidenceOccurrence.of(candidates.get(0), input.snapshotId(), ADAPTER, anchor(path),
                        EvidenceLevel.UNRESOLVED,
                        "MYBATIS_DATABASE_ID_RUNTIME_DEPENDENT: " + namespace + "." + id));
                return;
            }
            var element = selected.orElseThrow();
            boolean dynamic = hasDynamicChildren(element);
            List<String> alternatives;
            try {
                alternatives = renderAlternatives(element);
            } catch (UnboundedDynamicSql exception) {
                partial = true;
                facts.add(EvidenceOccurrence.of(candidates.get(0), input.snapshotId(), ADAPTER, anchor(path),
                        EvidenceLevel.UNRESOLVED,
                        "MYBATIS_DYNAMIC_SQL_UNBOUNDED: " + namespace + "." + id));
                return;
            }
            var level = dynamic ? EvidenceLevel.POSSIBLE : EvidenceLevel.CONFIRMED;
            if (hasAnnotationSql(candidates.get(0))) {
                level = EvidenceLevel.POSSIBLE;
                downgradeAnnotationEvidence(candidates.get(0));
                partial = true;
            }
            for (int alternative = 0; alternative < alternatives.size(); alternative++) {
                addXmlSql(path, namespace, id, candidates.get(0), alternatives.get(alternative), alternative, level, dynamic);
            }
        }

        private void addXmlSql(Path path, String namespace, String id, NodeId owner, String sql,
                int alternative, EvidenceLevel level, boolean dynamic) {
            var sqlId = NodeId.of(NodeKind.SQL_STATEMENT, Map.of(
                    "project", input.projectId().value(), "module", input.moduleName(),
                    "owner", owner.canonicalValue(), "sourceKind", "mybatis-xml",
                    "alternative", Integer.toString(alternative),
                    "statement", namespace + "." + id, "sql", sql));
            facts.add(new NodeFact(sqlId, Map.of("sql", sql, "namespace", namespace, "statementId", id)));
            facts.add(EvidenceOccurrence.of(sqlId, input.snapshotId(), ADAPTER, anchor(path), level,
                    dynamic ? "MYBATIS_DYNAMIC_SQL_BRANCH" : "MyBatis XML supplied static SQL."));
            addRelationship(owner, RelationshipType.EXECUTES, sqlId, anchor(path), level,
                    "MyBatis namespace and statement id bind this Mapper method.");
            addSqlFacts(sqlId, sql, anchor(path), level);
        }

        private static List<String> renderAlternatives(org.w3c.dom.Node parent) {
            var results = new ArrayList<String>(); results.add("");
            for (int index = 0; index < parent.getChildNodes().getLength(); index++) {
                var child = parent.getChildNodes().item(index);
                List<String> choices;
                if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                        || child.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                    choices = List.of(child.getTextContent());
                } else if (child instanceof Element element) {
                    if ("foreach".equals(element.getTagName()) || "bind".equals(element.getTagName())) {
                        throw new UnboundedDynamicSql();
                    } else if ("if".equals(element.getTagName())) {
                        choices = new ArrayList<>(); choices.add(""); choices.addAll(renderAlternatives(element));
                    } else if ("choose".equals(element.getTagName())) {
                        choices = new ArrayList<>();
                        for (int option = 0; option < element.getChildNodes().getLength(); option++) {
                            if (element.getChildNodes().item(option) instanceof Element branch
                                    && Set.of("when", "otherwise").contains(branch.getTagName())) {
                                choices.addAll(renderAlternatives(branch));
                            }
                        }
                    } else choices = renderAlternatives(element);
                } else continue;
                var expanded = new ArrayList<String>();
                for (var prefix : results) for (var choice : choices) {
                    expanded.add(prefix + " " + choice);
                    if (expanded.size() > 32) throw new UnboundedDynamicSql();
                }
                results = expanded;
            }
            return results.stream().map(sql -> sql.replaceAll("#\\{[^}]+}", "?")
                            .replaceAll("\\s+", " ").trim())
                    .filter(sql -> !sql.isBlank()).distinct().sorted().toList();
        }

        private static final class UnboundedDynamicSql extends RuntimeException {}

        private Optional<Element> selectDatabaseVariant(List<Element> variants) {
            if (variants.size() == 1 && variants.get(0).getAttribute("databaseId").isBlank()) return Optional.of(variants.get(0));
            if (input.databaseId().isEmpty()) return Optional.empty();
            return variants.stream().filter(e -> e.getAttribute("databaseId").equals(input.databaseId().orElseThrow()))
                    .findFirst().or(() -> variants.stream().filter(e -> e.getAttribute("databaseId").isBlank()).findFirst());
        }

        private List<NodeId> mapperMethods(String namespace, String id) {
            return facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                    .map(NodeFact::id).filter(node -> node.kind() == NodeKind.CODE_SYMBOL)
                    .filter(node -> namespace.equals(node.identityParts().get("declaringType")))
                    .filter(node -> id.equals(node.identityParts().get("methodName")))
                    .sorted(Comparator.comparing(NodeId::canonicalValue)).toList();
        }

        private void replaySchemaSources() throws IOException {
            var schemaFiles = schemaFiles();
            for (var path : schemaFiles) {
                var content = Files.readString(path, StandardCharsets.UTF_8);
                createTables(path, content);
                createViews(path, content);
                createRoutines(path, content);
            }
            for (var path : schemaFiles) createTriggers(path, Files.readString(path, StandardCharsets.UTF_8));
            resolveViewReferences();
            resolveRoutineCalls();
        }

        private void createTables(Path path, String content) {
            var matcher = CREATE_TABLE.matcher(content);
            while (matcher.find()) {
                var table = table(matcher.group(1));
                var qualified = table.identityParts().get("schema") + "." + table.identityParts().get("name");
                if (!declaredTables.add(qualified)) {
                    partial = true;
                    declaredTables.remove(qualified);
                    conflictingObjects.add(table);
                    downgradeObjectEvidence(table, "POSTGRESQL_SCHEMA_CONFLICT_TABLE: " + matcher.group(1));
                    facts.add(EvidenceOccurrence.of(table, input.snapshotId(), ADAPTER, anchor(path),
                            EvidenceLevel.UNRESOLVED, "POSTGRESQL_SCHEMA_CONFLICT_TABLE: " + matcher.group(1)));
                    continue;
                }
                addNode(table, Map.of("originalIdentifier", matcher.group(1), "dialect", "postgresql"), anchor(path),
                        "PostgreSQL Schema Source declares this Table.");
                confirmDeclaredTable(table);
            }
        }

        private void createViews(Path path, String content) {
            var matcher = CREATE_VIEW.matcher(content);
            while (matcher.find()) {
                var view = databaseObject(NodeKind.VIEW, matcher.group(1), Map.of());
                var key = normalizeQualified(matcher.group(1));
                if (views.putIfAbsent(key, view) != null) {
                    partial = true;
                    conflictingObjects.add(view);
                    downgradeObjectEvidence(view, "POSTGRESQL_SCHEMA_CONFLICT_VIEW: " + matcher.group(1));
                    facts.add(EvidenceOccurrence.of(view, input.snapshotId(), ADAPTER, anchor(path),
                            EvidenceLevel.UNRESOLVED, "POSTGRESQL_SCHEMA_CONFLICT_VIEW: " + matcher.group(1)));
                    continue;
                }
                addNode(view, Map.of("originalIdentifier", matcher.group(1)), anchor(path),
                        "PostgreSQL Schema Source declares this View.");
                if (JSqlParserPostgreSqlAnalyzer.isSimplyUpdatableView(matcher.group(2))) {
                    simplyUpdatableViews.add(view);
                }
                addSqlFacts(view, matcher.group(2), anchor(path), EvidenceLevel.CONFIRMED);
            }
        }

        private void createRoutines(Path path, String content) {
            var matcher = CREATE_ROUTINE.matcher(content);
            while (matcher.find()) {
                var signature = normalizeTypes(matcher.group(2));
                var routine = databaseObject(NodeKind.DATABASE_ROUTINE, matcher.group(1), Map.of("inputTypes", signature));
                var signatureKey = normalizeQualified(matcher.group(1)) + "(" + signature + ")";
                if (routines.putIfAbsent(signatureKey, routine) != null) {
                    partial = true;
                    conflictingRoutineNames.add(normalizeQualified(matcher.group(1)));
                    conflictingObjects.add(routine);
                    downgradeObjectEvidence(routine, "POSTGRESQL_SCHEMA_CONFLICT_ROUTINE: " + signatureKey);
                    facts.add(EvidenceOccurrence.of(routine, input.snapshotId(), ADAPTER, anchor(path),
                            EvidenceLevel.UNRESOLVED, "POSTGRESQL_SCHEMA_CONFLICT_ROUTINE: " + signatureKey));
                    continue;
                }
                routines.putIfAbsent(normalizeQualified(matcher.group(1)), routine);
                routinesByName.computeIfAbsent(normalizeQualified(matcher.group(1)), ignored -> new ArrayList<>())
                        .add(routine);
                addNode(routine, Map.of("inputTypes", signature), anchor(path),
                        "PostgreSQL Schema Source declares this Routine.");
                var body = matcher.group(3);
                var staticExecute = STATIC_EXECUTE.matcher(body);
                var consumed = new HashSet<String>();
                while (staticExecute.find()) {
                    var sql = staticExecute.group(1).replace("''", "'");
                    consumed.add(staticExecute.group());
                    addSqlFacts(routine, sql, anchor(path), EvidenceLevel.CONFIRMED);
                }
                var dynamicExecute = DYNAMIC_EXECUTE.matcher(body);
                while (dynamicExecute.find()) {
                    if (consumed.contains(dynamicExecute.group())) continue;
                    partial = true;
                    facts.add(EvidenceOccurrence.of(routine, input.snapshotId(), ADAPTER, anchor(path),
                            EvidenceLevel.UNRESOLVED,
                            "POSTGRESQL_DYNAMIC_EXECUTE: " + dynamicExecute.group(1).trim()));
                }
                for (var sql : body.split(";")) {
                    var trimmed = sql.trim().replaceFirst("(?is)^begin\\s+", "");
                    if (trimmed.toLowerCase(Locale.ROOT).matches("^(select|insert|update|delete|merge)\\b.*")) {
                        addSqlFacts(routine, trimmed, anchor(path), EvidenceLevel.CONFIRMED);
                    }
                }
            }
        }

        private void createTriggers(Path path, String content) {
            var matcher = CREATE_TRIGGER.matcher(content);
            while (matcher.find()) {
                var events = triggerEvents(matcher.group(2));
                var trigger = databaseObject(NodeKind.TRIGGER, matcher.group(1), Map.of("table", normalizeQualified(matcher.group(3))));
                addNode(trigger, Map.of("eventTable", matcher.group(3), "events", String.join(",", events)), anchor(path),
                        "PostgreSQL Schema Source declares this Trigger.");
                var sourceTable = table(matcher.group(3));
                addRelationship(sourceTable, RelationshipType.TRIGGERS, trigger, anchor(path), EvidenceLevel.CONFIRMED,
                        "The PostgreSQL trigger is attached to this Table.");
                linkTriggerActivations(sourceTable, trigger, events, anchor(path));
                var routine = uniqueRoutine(matcher.group(4), "()");
                if (routine == null) {
                    partial = true;
                    facts.add(EvidenceOccurrence.of(trigger, input.snapshotId(), ADAPTER, anchor(path),
                            EvidenceLevel.UNRESOLVED, "POSTGRESQL_TRIGGER_ROUTINE_MISSING: " + matcher.group(4)));
                } else {
                    addRelationship(trigger, RelationshipType.EXECUTES, routine, anchor(path), EvidenceLevel.CONFIRMED,
                            "The PostgreSQL Trigger executes this Routine.");
                }
            }
        }

        private void linkTriggerActivations(NodeId table, NodeId trigger, Set<String> events, SourceAnchor anchor) {
            var eventTypes = Map.of(
                    RelationshipType.INSERTS, "INSERT", RelationshipType.UPDATES, "UPDATE",
                    RelationshipType.DELETES, "DELETE");
            for (var fact : List.copyOf(facts)) {
                if (fact instanceof RelationshipAssertion assertion && assertion.target().equals(table)
                        && eventTypes.containsKey(assertion.type())
                        && events.contains(eventTypes.get(assertion.type()))) {
                    var activation = RelationshipAssertion.of(assertion.source(), RelationshipType.TRIGGERS, trigger,
                            Map.of("event", eventTypes.get(assertion.type())));
                    facts.add(activation);
                    facts.add(EvidenceOccurrence.of(activation.id(), input.snapshotId(), ADAPTER, anchor,
                            EvidenceLevel.CONFIRMED,
                            "This SQL write activates the PostgreSQL Trigger for its declared event."));
                }
            }
        }

        private static Set<String> triggerEvents(String declaration) {
            var upper = declaration.toUpperCase(Locale.ROOT);
            var events = new java.util.TreeSet<String>();
            for (var event : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) if (upper.contains(event)) events.add(event);
            return Set.copyOf(events);
        }

        private void resolveRoutineCalls() {
            for (var fact : List.copyOf(facts)) {
                if (!(fact instanceof NodeFact node) || node.id().kind() != NodeKind.SQL_STATEMENT) continue;
                var matcher = CALL_ROUTINE.matcher(node.properties().getOrDefault("sql", ""));
                if (!matcher.find()) continue;
                var callAnchor = facts.stream().filter(EvidenceOccurrence.class::isInstance)
                        .map(EvidenceOccurrence.class::cast)
                        .filter(evidence -> evidence.subject().equals(node.id()))
                        .map(EvidenceOccurrence::sourceAnchor).findFirst()
                        .orElseThrow(() -> new IllegalStateException("SQL Statement lacks source evidence"));
                var routine = uniqueRoutine(matcher.group(1), inferredArgumentTypes(matcher.group(2)));
                if (routine == null) {
                    partial = true;
                    facts.add(EvidenceOccurrence.of(node.id(), input.snapshotId(), ADAPTER,
                            callAnchor, EvidenceLevel.UNRESOLVED,
                            "POSTGRESQL_ROUTINE_MISSING_OR_AMBIGUOUS: " + matcher.group(1)
                                    + " candidates=" + routinesByName.getOrDefault(
                                            normalizeQualified(matcher.group(1)), List.of()).stream()
                                            .map(NodeId::canonicalValue).sorted().toList()));
                } else {
                    facts.removeIf(f -> f instanceof EvidenceOccurrence evidence
                            && evidence.subject().equals(node.id())
                            && evidence.explanation().startsWith("POSTGRESQL_SQL_PARSE_FAILURE:"));
                    addRelationship(node.id(), RelationshipType.EXECUTES, routine,
                            callAnchor, EvidenceLevel.CONFIRMED,
                            "The static PostgreSQL CALL resolves to this Routine.");
                }
            }
        }

        private NodeId uniqueRoutine(String rawName, String inputTypes) {
            var normalizedName = normalizeQualified(rawName);
            if (conflictingRoutineNames.contains(normalizedName)) return null;
            var candidates = routinesByName.getOrDefault(normalizedName, List.of());
            var exact = candidates.stream()
                    .filter(candidate -> inputTypes.equals(candidate.identityParts().get("inputTypes"))).toList();
            if (exact.size() == 1) return exact.get(0);
            return candidates.size() == 1 ? candidates.get(0) : null;
        }

        private static String inferredArgumentTypes(String arguments) {
            if (arguments.isBlank()) return "()";
            return java.util.Arrays.stream(arguments.split(",")).map(String::trim).map(value -> {
                if (value.matches("[-+]?\\d+")) return "integer";
                if (value.matches("[-+]?\\d+\\.\\d+")) return "numeric";
                if (value.startsWith("'") && value.endsWith("'")) return "text";
                if (Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) return "boolean";
                return "?";
            }).collect(java.util.stream.Collectors.joining(","));
        }

        private boolean hasAnnotationSql(NodeId owner) {
            return facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                    .anyMatch(node -> node.id().kind() == NodeKind.SQL_STATEMENT
                            && owner.canonicalValue().equals(node.id().identityParts().get("owner"))
                            && node.id().identityParts().getOrDefault("sourceKind", "").startsWith("annotation-"));
        }

        private void downgradeAnnotationEvidence(NodeId owner) {
            var sqlIds = facts.stream().filter(NodeFact.class::isInstance).map(NodeFact.class::cast)
                    .map(NodeFact::id).filter(id -> id.kind() == NodeKind.SQL_STATEMENT)
                    .filter(id -> owner.canonicalValue().equals(id.identityParts().get("owner")))
                    .filter(id -> id.identityParts().getOrDefault("sourceKind", "").startsWith("annotation-"))
                    .collect(java.util.stream.Collectors.toSet());
            var subjects = new HashSet<io.graphcrud.model.EvidenceSubject>(sqlIds);
            facts.stream().filter(RelationshipAssertion.class::isInstance).map(RelationshipAssertion.class::cast)
                    .filter(assertion -> (assertion.source().equals(owner) && sqlIds.contains(assertion.target()))
                            || sqlIds.contains(assertion.source()))
                    .map(RelationshipAssertion::id).forEach(subjects::add);
            for (int index = 0; index < facts.size(); index++) {
                if (facts.get(index) instanceof EvidenceOccurrence evidence
                        && evidence.evidenceLevel() == EvidenceLevel.CONFIRMED
                        && subjects.contains(evidence.subject())) {
                    facts.set(index, EvidenceOccurrence.of(
                            evidence.subject(), evidence.snapshotId(), evidence.adapter(), evidence.sourceAnchor(),
                            EvidenceLevel.POSSIBLE,
                            "MYBATIS_XML_ANNOTATION_CONFLICT: " + evidence.explanation()));
                }
            }
        }

        private void resolveViewReferences() {
            var replacements = new HashMap<RelationshipAssertion, EvidenceOccurrence>();
            for (var fact : List.copyOf(facts)) {
                if (!(fact instanceof RelationshipAssertion assertion) || assertion.target().kind() != NodeKind.TABLE) continue;
                var key = assertion.target().identityParts().get("schema") + "." + assertion.target().identityParts().get("name");
                var view = views.get(key);
                if (view == null || assertion.source().equals(view)) continue;
                var evidence = facts.stream().filter(EvidenceOccurrence.class::isInstance).map(EvidenceOccurrence.class::cast)
                        .filter(e -> e.subject().equals(assertion.id())).findFirst().orElse(null);
                if (evidence != null) {
                    replacements.put(RelationshipAssertion.of(
                            assertion.source(), assertion.type(), view, assertion.semanticQualifiers()), evidence);
                }
                facts.removeIf(f -> f.equals(assertion)
                        || f instanceof EvidenceOccurrence occurrence && occurrence.subject().equals(assertion.id()));
            }
            replacements.forEach((replacement, evidence) -> {
                facts.add(replacement);
                boolean write = Set.of(RelationshipType.INSERTS, RelationshipType.UPDATES, RelationshipType.DELETES)
                        .contains(replacement.type());
                boolean uniqueView = !conflictingObjects.contains(replacement.target());
                boolean supportedWrite = !write || simplyUpdatableViews.contains(replacement.target());
                if (!uniqueView || !supportedWrite) partial = true;
                facts.add(EvidenceOccurrence.of(
                        replacement.id(), evidence.snapshotId(), evidence.adapter(), evidence.sourceAnchor(),
                        uniqueView && supportedWrite ? evidence.evidenceLevel() : EvidenceLevel.UNRESOLVED,
                        uniqueView && supportedWrite
                                ? "PostgreSQL resolved the table reference to a statically declared View."
                                : uniqueView
                                        ? "POSTGRESQL_VIEW_WRITE_UNSUPPORTED: no evidenced simple-updatable or INSTEAD OF path."
                                        : "POSTGRESQL_SCHEMA_CONFLICT_VIEW: reference is not uniquely resolvable."));
                if (write && supportedWrite && uniqueView) {
                    var baseTables = facts.stream().filter(RelationshipAssertion.class::isInstance)
                            .map(RelationshipAssertion.class::cast)
                            .filter(assertion -> assertion.source().equals(replacement.target()))
                            .filter(assertion -> assertion.type() == RelationshipType.READS)
                            .map(RelationshipAssertion::target).distinct().toList();
                    for (var baseTable : baseTables) {
                        addRelationship(replacement.target(), replacement.type(), baseTable,
                                evidence.sourceAnchor(), evidence.evidenceLevel(),
                                "The statically declared View extends this write to its base Table.");
                    }
                }
            });
        }

        private void addSqlFacts(NodeId source, String sql, SourceAnchor anchor, EvidenceLevel level) {
            if (CALL_ROUTINE.matcher(sql).find()) return;
            var result = new JSqlParserPostgreSqlAnalyzer().analyze(new PostgreSqlAnalysisInput(
                    input.projectId(), input.snapshotId(), source, sql, input.databaseSource(), input.defaultSchema(),
                    declaredTables, anchor));
            if (result.completion() == SnapshotCompletion.PARTIAL
                    && result.facts().stream().filter(EvidenceOccurrence.class::isInstance)
                            .map(EvidenceOccurrence.class::cast)
                            .anyMatch(evidence -> evidence.evidenceLevel() == EvidenceLevel.UNRESOLVED
                                    && !evidence.explanation().startsWith("POSTGRESQL_TABLE_UNDECLARED:"))) {
                partial = true;
            }
            for (var fact : result.facts()) {
                if (level == EvidenceLevel.POSSIBLE && fact instanceof EvidenceOccurrence evidence
                        && evidence.evidenceLevel() == EvidenceLevel.CONFIRMED) {
                    facts.add(EvidenceOccurrence.of(evidence.subject(), evidence.snapshotId(), evidence.adapter(),
                            evidence.sourceAnchor(), EvidenceLevel.POSSIBLE,
                            "MYBATIS_DYNAMIC_BRANCH: " + evidence.explanation()));
                } else facts.add(fact);
            }
        }

        private void addNode(NodeId id, Map<String, String> properties, SourceAnchor anchor, String explanation) {
            if (facts.stream().noneMatch(f -> f instanceof NodeFact n && n.id().equals(id))) facts.add(new NodeFact(id, properties));
            facts.add(EvidenceOccurrence.of(id, input.snapshotId(), ADAPTER, anchor, EvidenceLevel.CONFIRMED, explanation));
        }

        private void confirmDeclaredTable(NodeId table) {
            if (conflictingObjects.contains(table)) return;
            var related = facts.stream().filter(RelationshipAssertion.class::isInstance)
                    .map(RelationshipAssertion.class::cast).filter(assertion -> assertion.target().equals(table))
                    .map(RelationshipAssertion::id).collect(java.util.stream.Collectors.toSet());
            for (int index = 0; index < facts.size(); index++) {
                if (facts.get(index) instanceof EvidenceOccurrence evidence
                        && evidence.evidenceLevel() == EvidenceLevel.UNRESOLVED
                        && evidence.explanation().startsWith("POSTGRESQL_TABLE_UNDECLARED:")
                        && (evidence.subject().equals(table) || related.contains(evidence.subject()))) {
                    facts.set(index, EvidenceOccurrence.of(
                            evidence.subject(), evidence.snapshotId(), evidence.adapter(), evidence.sourceAnchor(),
                            EvidenceLevel.CONFIRMED,
                            "PostgreSQL Schema Source uniquely declares the referenced Table."));
                }
            }
        }

        private void downgradeObjectEvidence(NodeId object, String reason) {
            var related = facts.stream().filter(RelationshipAssertion.class::isInstance)
                    .map(RelationshipAssertion.class::cast)
                    .filter(assertion -> assertion.source().equals(object) || assertion.target().equals(object))
                    .map(RelationshipAssertion::id).collect(java.util.stream.Collectors.toSet());
            for (int index = 0; index < facts.size(); index++) {
                if (facts.get(index) instanceof EvidenceOccurrence evidence
                        && evidence.evidenceLevel() == EvidenceLevel.CONFIRMED
                        && (evidence.subject().equals(object) || related.contains(evidence.subject()))) {
                    facts.set(index, EvidenceOccurrence.of(
                            evidence.subject(), evidence.snapshotId(), evidence.adapter(), evidence.sourceAnchor(),
                            EvidenceLevel.UNRESOLVED, reason));
                }
            }
        }

        private void addRelationship(NodeId source, RelationshipType type, NodeId target, SourceAnchor anchor,
                EvidenceLevel level, String explanation) {
            var assertion = RelationshipAssertion.of(source, type, target, Map.of());
            if (facts.stream().noneMatch(f -> f instanceof RelationshipAssertion r && r.id().equals(assertion.id()))) facts.add(assertion);
            facts.add(EvidenceOccurrence.of(assertion.id(), input.snapshotId(), ADAPTER, anchor, level, explanation));
        }

        private NodeId configuration(Path path) {
            return NodeId.of(NodeKind.CONFIGURATION_ENTRY, Map.of("project", input.projectId().value(),
                    "path", relative(path), "kind", "mybatis-mapper"));
        }

        private NodeId table(String raw) { return databaseObject(NodeKind.TABLE, raw, Map.of()); }

        private NodeId databaseObject(NodeKind kind, String raw, Map<String, String> extra) {
            var qualified = PostgreSqlIdentifier.parse(raw, input.defaultSchema());
            var identity = new HashMap<String, String>();
            identity.put("project", input.projectId().value()); identity.put("databaseSource", input.databaseSource());
            identity.put("schema", qualified.schema()); identity.put("name", qualified.name()); identity.putAll(extra);
            return NodeId.of(kind, identity);
        }

        private String normalizeQualified(String raw) {
            return PostgreSqlIdentifier.parse(raw, input.defaultSchema()).qualifiedName();
        }

        private static String normalizeTypes(String args) {
            if (args.isBlank()) return "()";
            return java.util.Arrays.stream(args.split(",")).map(String::trim)
                    .map(arg -> arg.replaceAll("(?i)^(in|out|inout|variadic)\\s+", ""))
                    .map(arg -> { var p = arg.split("\\s+"); return p[p.length - 1].toLowerCase(Locale.ROOT); })
                    .collect(java.util.stream.Collectors.joining(","));
        }

        private boolean hasDynamicChildren(Element element) {
            for (int i = 0; i < element.getElementsByTagName("if").getLength(); i++) return true;
            for (var name : List.of("choose", "when", "otherwise", "foreach", "bind"))
                if (element.getElementsByTagName(name).getLength() > 0) return true;
            return false;
        }

        private List<Path> files(String suffix) throws IOException {
            try (var paths = Files.walk(root)) {
                return paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(suffix))
                        .filter(path -> path.startsWith(root))
                        .filter(path -> java.util.stream.StreamSupport.stream(root.relativize(path).spliterator(), false)
                                .noneMatch(part -> Set.of(".git", ".gradle", "build", "target", "out").contains(part.toString())))
                        .sorted().toList();
            }
        }

        private List<Path> schemaFiles() throws IOException {
            var ordered = new ArrayList<RankedPath>();
            for (var source : input.schemaSources()) {
                if (!source.environment().equals(input.targetEnvironment())) continue;
                var resolved = source.path().toRealPath();
                if (!resolved.startsWith(root)) {
                    throw new IllegalArgumentException("Schema Source escapes Analysis Project: " + source.path());
                }
                if (Files.isRegularFile(resolved) && resolved.toString().endsWith(".sql")) {
                    ordered.add(new RankedPath(resolved, source.priority()));
                } else if (Files.isDirectory(resolved)) {
                    try (var paths = Files.walk(resolved)) {
                        paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".sql"))
                                .forEach(path -> ordered.add(new RankedPath(path, source.priority())));
                    }
                }
            }
            return ordered.stream().sorted(Comparator.comparingInt(RankedPath::priority).reversed()
                            .thenComparingLong(value -> flywayOrder(value.path().getFileName().toString()))
                            .thenComparing(value -> value.path().toString()))
                    .map(RankedPath::path).toList();
        }

        private static long flywayOrder(String fileName) {
            var matcher = Pattern.compile("^V(\\d+)(?:[._](\\d+))?.*__.*\\.sql$").matcher(fileName);
            if (!matcher.matches()) return Long.MAX_VALUE;
            long major = Long.parseLong(matcher.group(1));
            long minor = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
            return major * 1_000_000L + minor;
        }

        private SourceAnchor anchor(Path path) { return new SourceAnchor(relative(path), 1, 1); }
        private String relative(Path path) { return root.relativize(path).toString().replace('\\', '/'); }
        private record RankedPath(Path path, int priority) {}
    }
}
