package io.graphcrud.launcher;

import io.graphcrud.application.AnalysisProgress;
import io.graphcrud.application.AnalyzeResponse;

final class DeliverySse {
    private DeliverySse() {}
    static String progress(AnalysisProgress value) {
        return "event: progress\ndata: {\"state\":\"" + value.state() + "\",\"stage\":\"" + value.stage()
                + "\",\"factsWritten\":" + value.factsWritten() + ",\"message\":\""
                + DeliveryJson.escape(value.message()) + "\"}\n\n";
    }
    static String result(AnalyzeResponse value) { return "event: result\ndata: " + DeliveryJson.analyze(value) + "\n\n"; }
}
