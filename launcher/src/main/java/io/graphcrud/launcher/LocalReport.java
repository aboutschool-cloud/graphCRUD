package io.graphcrud.launcher;

import io.graphcrud.application.StatusResponse;
import io.graphcrud.application.ImpactResponse;
import io.graphcrud.application.CrudOperation;
import io.graphcrud.application.ImpactPath;
import io.graphcrud.model.EvidenceOccurrenceId;
import io.graphcrud.model.NodeId;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public final class LocalReport {
    private LocalReport() {}
    public static String json(StatusResponse response) { return DeliveryJson.status(response); }
    public static String html(StatusResponse response) {
        var heading = response.coverage().complete() ? "Complete coverage" : "Partial coverage";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>graphCRUD report</title></head><body>"
                + "<h1>" + heading + "</h1><pre>" + htmlEscape(json(response)) + "</pre></body></html>";
    }
    public static String json(ImpactResponse response) { return DeliveryJson.impact(response); }
    public static String html(ImpactResponse response) {
        var heading = response.coverage().complete() ? "Complete coverage" : "Partial coverage";
        var visualPaths = visualPaths(response.paths());
        var counts = new EnumMap<CrudOperation, Integer>(CrudOperation.class);
        for (var operation : CrudOperation.values()) counts.put(operation, 0);
        visualPaths.forEach(path -> counts.merge(path.operation(), 1, Integer::sum));
        var body = new StringBuilder();
        body.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>graphCRUD impact report</title><style>").append(styles()).append("</style></head><body>")
                .append("<header><p class=\"eyebrow\">graphCRUD &middot; snapshot ").append(htmlEscape(response.snapshotId().value()))
                .append("</p><h1>CRUD call-chain analysis</h1><div class=\"badges\"><span class=\"coverage\">")
                .append(heading).append("</span>");
        body.append(response.truncated() ? "<span class=\"danger\">Results truncated</span>"
                : "<span class=\"coverage\">Not truncated</span>");
        body.append("</div></header><main><section><div class=\"section-title\"><div><p class=\"eyebrow\">Impact summary</p>")
                .append("<h2>CRUD overview</h2></div><p>").append(visualPaths.size())
                .append(" distinct call chains &middot; ").append(response.paths().size())
                .append(" evidence-backed path observations</p></div><div class=\"crud-grid\">");
        for (var operation : CrudOperation.values()) body.append("<button class=\"crud-card ")
                .append(operation.name().toLowerCase()).append("\" data-filter=\"").append(operation.name())
                .append("\" aria-pressed=\"false\"><span>").append(operationLabel(operation)).append("</span><strong>")
                .append(counts.get(operation)).append("</strong></button>");
        body.append("</div></section><section><div class=\"section-title\"><div><p class=\"eyebrow\">Evidence graph</p>")
                .append("<h2>CRUD call chains</h2></div><button id=\"show-all\">Show all</button></div><div class=\"chains\">");
        for (int index = 0; index < visualPaths.size(); index++) appendPath(body, visualPaths.get(index), index + 1);
        if (visualPaths.isEmpty()) body.append("<p class=\"empty\">No evidenced call chains matched this bounded query.</p>");
        body.append("</div></section><section class=\"coverage-panel\"><div><p class=\"eyebrow\">Trust boundary</p><h2>")
                .append(heading).append("</h2><p>Depth &le; ").append(response.bounds().maximumDepth())
                .append(" &middot; paths &le; ").append(response.bounds().maximumPaths()).append("</p></div><div>");
        response.warnings().forEach(warning -> body.append("<p class=\"warning\"><strong>")
                .append(htmlEscape(warning.code())).append(":</strong> ").append(htmlEscape(warning.message())).append("</p>"));
        response.coverage().reasons().forEach(reason -> body.append("<p class=\"reason\">")
                .append(htmlEscape(reason)).append("</p>"));
        body.append("</div></section></main><script>").append(script()).append("</script></body></html>");
        return body.toString();
    }
    private static void appendPath(StringBuilder body, VisualPath path, int ordinal) {
        int width = Math.max(640, path.nodes().size() * 230);
        body.append("<article class=\"chain\" data-operation=\"").append(path.operation().name()).append("\">")
                .append("<div class=\"chain-head\"><span class=\"operation ")
                .append(path.operation().name().toLowerCase()).append("\">").append(operationLabel(path.operation()))
                .append("</span><span>Path ").append(ordinal).append(" &middot; ").append(path.nodes().size()).append(" nodes</span></div>")
                .append("<div class=\"graph-scroll\"><svg role=\"img\" aria-label=\"").append(path.operation().name())
                .append(" CRUD call chain\" viewBox=\"0 0 ").append(width).append(" 190\"><desc>")
                .append(svgEscape(path.nodes().stream().map(LocalReport::nodeLabel)
                        .collect(java.util.stream.Collectors.joining(" to ")))).append("</desc>");
        for (int index = 0; index < path.nodes().size(); index++) {
            int x = 25 + index * 225;
            if (index > 0) {
                var edgeEvidence = path.edgeEvidence().get(index - 1);
                var evidence = edgeEvidence.get(0).value() + (edgeEvidence.size() > 1 ? " +" + (edgeEvidence.size() - 1) : "");
                body.append("<line x1=\"").append(x - 65).append("\" y1=\"80\" x2=\"").append(x)
                        .append("\" y2=\"80\" class=\"edge\"/><polygon points=\"").append(x - 8)
                        .append(",74 ").append(x).append(",80 ").append(x - 8).append(",86\" class=\"arrow\"/>")
                        .append("<text x=\"").append(x - 33).append("\" y=\"108\" class=\"evidence\">")
                        .append(svgEscape(shorten(evidence, 24))).append("</text>");
            }
            appendNode(body, path.nodes().get(index), x, 35);
        }
        body.append("</svg></div><details><summary>Accessible path and evidence</summary><ol class=\"identity-list\">");
        for (int index = 0; index < path.nodes().size(); index++) {
            var node = path.nodes().get(index);
            body.append("<li><strong>").append(htmlEscape(nodeLabel(node))).append("</strong><br><code>")
                    .append(htmlEscape(node.canonicalValue())).append("</code>");
            if (index < path.edgeEvidence().size()) {
                body.append("<ul>");
                path.edgeEvidence().get(index).forEach(evidence -> body.append("<li>Evidence: <code>")
                        .append(htmlEscape(evidence.value())).append("</code></li>"));
                body.append("</ul>");
            }
            body.append("</li>");
        }
        body.append("</ol></details></article>");
    }
    private static void appendNode(StringBuilder body, NodeId node, int x, int y) {
        var label = nodeLabel(node);
        body.append("<g><title>").append(svgEscape(node.canonicalValue())).append("</title><rect x=\"")
                .append(x).append("\" y=\"").append(y)
                .append("\" width=\"160\" height=\"90\" rx=\"14\" class=\"node ")
                .append(node.kind().name().toLowerCase()).append("\"/><text x=\"").append(x + 14)
                .append("\" y=\"").append(y + 25).append("\" class=\"kind\">").append(node.kind().name())
                .append("</text><text x=\"").append(x + 14).append("\" y=\"").append(y + 53)
                .append("\" class=\"label\">").append(svgEscape(shorten(label, 22))).append("</text></g>");
    }
    private static String nodeLabel(NodeId node) {
        var parts = node.identityParts();
        if (node.kind() == io.graphcrud.model.NodeKind.CODE_SYMBOL)
            return simple(parts.get("declaringType")) + "." + parts.get("methodName") + parts.get("jvmParameterSignature");
        for (var key : java.util.List.of("route", "name", "sql", "displayName", "path", "kind"))
            if (parts.containsKey(key)) return parts.get(key);
        return node.kind().name();
    }
    private static String simple(String value) { int dot = value.lastIndexOf('.'); return dot < 0 ? value : value.substring(dot + 1); }
    private static String shorten(String value, int maximum) { return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "..."; }
    private static String operationLabel(CrudOperation operation) { return switch (operation) {
        case READS -> "Read"; case INSERTS -> "Create"; case UPDATES -> "Update"; case DELETES -> "Delete"; };
    }
    private static List<VisualPath> visualPaths(List<ImpactPath> paths) {
        var grouped = new LinkedHashMap<String, VisualPathBuilder>();
        for (var path : paths) {
            var key = path.operation().name() + "|" + path.nodes().stream().map(NodeId::canonicalValue)
                    .collect(java.util.stream.Collectors.joining("|"));
            var builder = grouped.computeIfAbsent(key, ignored -> new VisualPathBuilder(path.operation(), path.nodes()));
            for (int index = 0; index < path.evidenceOccurrenceIds().size(); index++)
                builder.edgeEvidence.get(index).add(path.evidenceOccurrenceIds().get(index));
        }
        return grouped.values().stream().map(VisualPathBuilder::build).toList();
    }
    private record VisualPath(CrudOperation operation, List<NodeId> nodes,
                              List<List<EvidenceOccurrenceId>> edgeEvidence) {}
    private static final class VisualPathBuilder {
        private final CrudOperation operation; private final List<NodeId> nodes;
        private final List<LinkedHashSet<EvidenceOccurrenceId>> edgeEvidence = new ArrayList<>();
        private VisualPathBuilder(CrudOperation operation, List<NodeId> nodes) {
            this.operation = operation; this.nodes = List.copyOf(nodes);
            for (int index = 1; index < nodes.size(); index++) edgeEvidence.add(new LinkedHashSet<>());
        }
        private VisualPath build() { return new VisualPath(operation, nodes,
                edgeEvidence.stream().map(List::copyOf).toList()); }
    }
    private static String styles() { return """
            :root{color-scheme:dark;--bg:#09110f;--panel:#111d1a;--line:#31514a;--ink:#eaf7f1;--muted:#9ab2aa;--accent:#65e6b2}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 15% 0,#183329 0,transparent 35%),var(--bg);color:var(--ink);font:15px/1.5 system-ui,sans-serif}header,main{width:min(1180px,calc(100% - 40px));margin:auto}header{padding:64px 0 30px}h1{font-size:clamp(2.2rem,5vw,4.5rem);margin:.1em 0}h2{margin:.1em 0;font-size:1.5rem}.eyebrow{text-transform:uppercase;letter-spacing:.16em;color:var(--accent);font-size:.75rem}.badges{display:flex;gap:10px}.coverage,.danger,.operation{padding:6px 10px;border-radius:999px;background:#203c34}.danger{background:#572d2d;color:#ffd1cb}section{margin:18px 0;padding:24px;background:color-mix(in srgb,var(--panel) 94%,transparent);border:1px solid #213a34;border-radius:20px}.section-title,.chain-head,.coverage-panel{display:flex;align-items:center;justify-content:space-between;gap:20px}.crud-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:20px}button{color:inherit;background:#182823;border:1px solid #2b4740;border-radius:14px;padding:12px;cursor:pointer}.crud-card{text-align:left}.crud-card strong{display:block;font-size:2rem}.reads{--op:#6fb7ff}.inserts{--op:#65e6b2}.updates{--op:#ffc76b}.deletes{--op:#ff8174}.crud-card,.operation{border-color:var(--op);color:var(--op)}.chain{margin:16px 0;padding:18px;border:1px solid #29443d;border-radius:16px;background:#0c1714}.graph-scroll{overflow-x:auto}.graph-scroll svg{min-width:640px;width:100%;height:auto}.node{fill:#172824;stroke:#3d655b;stroke-width:2}.node.table{stroke:#65e6b2}.kind{fill:#8fa9a0;font-size:10px;letter-spacing:1px}.label{fill:#f1fff9;font-size:13px;font-weight:650}.edge{stroke:#53766c;stroke-width:2}.arrow{fill:#53766c}.evidence{fill:#8fa9a0;font-size:9px;text-anchor:middle}.warning{color:#ffd1cb}.reason{color:var(--muted)}code{color:#9ff2ce;overflow-wrap:anywhere}.hidden{display:none}.empty{color:var(--muted)}@media(max-width:700px){.crud-grid{grid-template-columns:1fr 1fr}.section-title,.coverage-panel{align-items:flex-start;flex-direction:column}}
            """; }
    private static String script() { return """
            const filters=[...document.querySelectorAll('[data-filter]')];filters.forEach(button=>button.addEventListener('click',()=>{const op=button.dataset.filter;filters.forEach(value=>value.setAttribute('aria-pressed',String(value===button)));document.querySelectorAll('.chain').forEach(chain=>chain.classList.toggle('hidden',chain.dataset.operation!==op));}));document.getElementById('show-all').addEventListener('click',()=>{filters.forEach(value=>value.setAttribute('aria-pressed','false'));document.querySelectorAll('.chain').forEach(chain=>chain.classList.remove('hidden'));});
            """; }
    private static String htmlEscape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String svgEscape(String value) { return htmlEscape(value).replace("'", "&apos;"); }
}
