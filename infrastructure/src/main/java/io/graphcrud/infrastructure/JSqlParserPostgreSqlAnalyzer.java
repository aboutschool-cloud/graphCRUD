package io.graphcrud.infrastructure;

import io.graphcrud.application.PostgreSqlAnalysisInput;
import io.graphcrud.application.PostgreSqlAnalysisResult;
import io.graphcrud.application.PostgreSqlAnalyzer;
import io.graphcrud.application.SnapshotCompletion;
import io.graphcrud.model.CanonicalFact;
import io.graphcrud.model.EvidenceLevel;
import io.graphcrud.model.EvidenceOccurrence;
import io.graphcrud.model.NodeFact;
import io.graphcrud.model.NodeId;
import io.graphcrud.model.NodeKind;
import io.graphcrud.model.RelationshipAssertion;
import io.graphcrud.model.RelationshipType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** JSqlParser-backed PostgreSQL adapter. Parser types never cross the application seam. */
public final class JSqlParserPostgreSqlAnalyzer implements PostgreSqlAnalyzer {
    private static final String ADAPTER = "jsqlparser-postgresql";

    @Override
    public PostgreSqlAnalysisResult analyze(PostgreSqlAnalysisInput input) {
        var facts = new ArrayList<CanonicalFact>();
        try {
            Statement statement = CCJSqlParserUtil.parse(input.sql());
            var allTables = new LinkedHashSet<>(TablesNamesFinder.findTables(input.sql()));
            var target = targetOf(statement);
            var writeTypes = writesOf(statement);
            if (target != null) {
                allTables.removeIf(name -> sameIdentifier(name, target.getFullyQualifiedName()));
                for (var type : writeTypes) {
                    addCrud(facts, input, target.getFullyQualifiedName(), type);
                }
            }
            for (var table : allTables) {
                addCrud(facts, input, table, RelationshipType.READS);
            }
            facts.sort(Comparator.comparing(CanonicalFact::factKind).thenComparing(CanonicalFact::canonicalId));
            var completion = facts.stream().filter(EvidenceOccurrence.class::isInstance)
                    .map(EvidenceOccurrence.class::cast)
                    .anyMatch(evidence -> evidence.evidenceLevel() == EvidenceLevel.UNRESOLVED)
                    ? SnapshotCompletion.PARTIAL : SnapshotCompletion.COMPLETE;
            return new PostgreSqlAnalysisResult(facts, completion);
        } catch (Exception exception) {
            facts.add(EvidenceOccurrence.of(
                    input.sqlStatementId(), input.snapshotId(), ADAPTER, input.sourceAnchor(),
                    EvidenceLevel.UNRESOLVED,
                    "POSTGRESQL_SQL_PARSE_FAILURE: " + exception.getClass().getSimpleName()));
            return new PostgreSqlAnalysisResult(facts, SnapshotCompletion.PARTIAL);
        }
    }

    static boolean isSimplyUpdatableView(String sql) {
        try {
            var statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select) || TablesNamesFinder.findTables(sql).size() != 1) return false;
            var rendered = statement.toString().toLowerCase(java.util.Locale.ROOT);
            if (!rendered.startsWith("select ") || rendered.startsWith("with ")) return false;
            int from = rendered.indexOf(" from ");
            if (from < 0 || rendered.substring("select ".length(), from).contains("(")) return false;
            return java.util.stream.Stream.of(
                            " join ", " group by ", " distinct ", " union ", " intersect ", " except ",
                            " having ", " limit ", " offset ", " order by ", " over ", " lateral ",
                            "count(", "sum(", "avg(", "min(", "max(", "generate_series(")
                    .noneMatch(rendered::contains);
        } catch (Exception exception) {
            return false;
        }
    }

    private static Table targetOf(Statement statement) {
        if (statement instanceof Insert insert) return insert.getTable();
        if (statement instanceof Update update) return update.getTable();
        if (statement instanceof Delete delete) return delete.getTable();
        if (statement instanceof Merge merge) return merge.getTable();
        return null;
    }

    private static Set<RelationshipType> writesOf(Statement statement) {
        if (statement instanceof Insert) return Set.of(RelationshipType.INSERTS);
        if (statement instanceof Update) return Set.of(RelationshipType.UPDATES);
        if (statement instanceof Delete) return Set.of(RelationshipType.DELETES);
        if (statement instanceof Merge merge) {
            var rendered = merge.toString().toLowerCase(java.util.Locale.ROOT);
            var operations = new java.util.HashSet<RelationshipType>();
            if (rendered.contains("when matched") && rendered.contains(" update ")) {
                operations.add(RelationshipType.UPDATES);
            }
            if (rendered.contains("when not matched") && rendered.contains(" insert ")) {
                operations.add(RelationshipType.INSERTS);
            }
            if (rendered.contains("when matched") && rendered.contains(" delete")) {
                operations.add(RelationshipType.DELETES);
            }
            return Set.copyOf(operations);
        }
        if (statement instanceof Select) return Set.of();
        throw new IllegalArgumentException("POSTGRESQL_SQL_UNSUPPORTED_STATEMENT: "
                + statement.getClass().getSimpleName());
    }

    private static void addCrud(
            List<CanonicalFact> facts,
            PostgreSqlAnalysisInput input,
            String rawIdentifier,
            RelationshipType type) {
        var identifier = PostgreSqlIdentifier.parse(rawIdentifier, input.defaultSchema());
        var level = input.declaredTables().contains(identifier.qualifiedName())
                ? EvidenceLevel.CONFIRMED : EvidenceLevel.UNRESOLVED;
        var tableId = NodeId.of(NodeKind.TABLE, Map.of(
                "project", input.projectId().value(),
                "databaseSource", input.databaseSource(),
                "schema", identifier.schema(),
                "name", identifier.name()));
        if (facts.stream().noneMatch(f -> f instanceof NodeFact node && node.id().equals(tableId))) {
            facts.add(new NodeFact(tableId, Map.of(
                    "displayName", identifier.schema() + "." + identifier.name(),
                    "originalIdentifier", identifier.original(),
                    "dialect", "postgresql")));
            facts.add(EvidenceOccurrence.of(
                    tableId, input.snapshotId(), ADAPTER, input.sourceAnchor(), level,
                    level == EvidenceLevel.CONFIRMED
                            ? "JSqlParser resolved a declared PostgreSQL Table."
                            : "POSTGRESQL_TABLE_UNDECLARED: " + identifier.qualifiedName()));
        }
        var assertion = RelationshipAssertion.of(input.sqlStatementId(), type, tableId, Map.of());
        if (facts.stream().noneMatch(f -> f instanceof RelationshipAssertion existing
                && existing.id().equals(assertion.id()))) {
            facts.add(assertion);
            facts.add(EvidenceOccurrence.of(
                    assertion.id(), input.snapshotId(), ADAPTER, input.sourceAnchor(), level,
                    level == EvidenceLevel.CONFIRMED
                            ? "JSqlParser recovered the PostgreSQL " + type.name() + " table role."
                            : "POSTGRESQL_TABLE_UNDECLARED: " + identifier.qualifiedName()));
        }
    }

    private static boolean sameIdentifier(String left, String right) {
        return left.equals(right) || left.equalsIgnoreCase(right);
    }

}
