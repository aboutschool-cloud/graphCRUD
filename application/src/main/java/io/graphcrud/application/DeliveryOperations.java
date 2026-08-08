package io.graphcrud.application;

import io.graphcrud.model.NodeId;
import io.graphcrud.model.SnapshotId;

public interface DeliveryOperations {
    AnalyzeResponse analyze(AnalysisJobRequest request, CancellationToken cancellationToken);
    default AnalyzeResponse analyze(AnalysisJobRequest request, CancellationToken cancellationToken,
                                    java.util.function.Consumer<AnalysisProgress> progressListener) {
        var response = analyze(request, cancellationToken);
        response.result().progress().forEach(progressListener);
        return response;
    }
    StatusResponse status(ProjectId projectId);
    StatusResponse status(ProjectId projectId, SnapshotId snapshotId);
    ImpactResponse tableImpact(ProjectId projectId, SnapshotId snapshotId, NodeId tableId, QueryBounds bounds);
    OperationResponse promotePartial(ProjectId projectId, SnapshotId snapshotId);
    ExportResponse export(ProjectId projectId, SnapshotId snapshotId);
    OperationResponse purgeProject(ProjectId projectId);
}
