package io.graphcrud.launcher;

import io.graphcrud.application.StatusResponse;
import io.graphcrud.application.ImpactResponse;

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
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>graphCRUD impact report</title></head><body>"
                + "<h1>" + heading + "</h1><p>Truncated: " + response.truncated() + "</p><pre>"
                + htmlEscape(json(response)) + "</pre></body></html>";
    }
    private static String htmlEscape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
