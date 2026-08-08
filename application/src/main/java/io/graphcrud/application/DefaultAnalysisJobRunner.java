package io.graphcrud.application;

import java.util.ArrayList;

/** Executes one bounded, synchronous analysis ingestion job through a GraphStore. */
public final class DefaultAnalysisJobRunner implements AnalysisJobRunner {
    private final GraphStore store;
    private final java.util.function.LongConsumer graphWriteTiming;

    public DefaultAnalysisJobRunner(GraphStore store) {
        this(store, ignored -> {});
    }

    public DefaultAnalysisJobRunner(GraphStore store, java.util.function.LongConsumer graphWriteTiming) {
        this.store = store;
        this.graphWriteTiming = java.util.Objects.requireNonNull(graphWriteTiming);
        store.capabilities().requireStageOne();
    }

    @Override
    public AnalysisJobResult run(AnalysisJobRequest request, CancellationToken cancellationToken) {
        return run(request, cancellationToken, ignored -> {});
    }

    @Override
    public AnalysisJobResult run(AnalysisJobRequest request, CancellationToken cancellationToken,
                                 java.util.function.Consumer<AnalysisProgress> listener) {
        var progress = new ProgressEvents(listener);
        progress.add(new AnalysisProgress(AnalysisJobState.QUEUED, 0, "Analysis job queued."));
        boolean staging = false;
        boolean sealing = false;
        int written = 0;
        try {
            if (cancellationToken.isCancellationRequested()) {
                return cancelBeforeStaging(progress);
            }
            progress.add(new AnalysisProgress(AnalysisJobState.RUNNING, 0, "Analysis job started."));
            var analysis = request.analysis().analyze(cancellationToken);
            var graphWriteStarted = System.nanoTime();
            if (cancellationToken.isCancellationRequested()) {
                return cancelBeforeStaging(progress);
            }
            store.beginSnapshot(request.projectId(), request.snapshotId());
            staging = true;
            for (int start = 0; start < analysis.facts().size(); start += request.batchSize()) {
                if (cancellationToken.isCancellationRequested()) {
                    return cancel(request, progress, written);
                }
                int end = Math.min(analysis.facts().size(), start + request.batchSize());
                store.writeFacts(request.snapshotId(), analysis.facts().subList(start, end));
                written += end - start;
                progress.add(new AnalysisProgress(AnalysisJobState.RUNNING, written, "Canonical facts staged."));
            }
            progress.add(new AnalysisProgress(AnalysisJobState.SEALING, written, "Snapshot sealing."));
            if (cancellationToken.isCancellationRequested()) {
                return cancel(request, progress, written);
            }
            sealing = true;
            store.sealSnapshot(request.snapshotId(), analysis.completion());
            graphWriteTiming.accept(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - graphWriteStarted));
            staging = false;
            var finalState = analysis.completion() == SnapshotCompletion.COMPLETE
                    ? AnalysisJobState.SUCCEEDED : AnalysisJobState.PARTIAL;
            progress.add(new AnalysisProgress(finalState, written, "Snapshot sealed."));
            return new AnalysisJobResult(finalState, progress);
        } catch (RuntimeException failure) {
            java.util.Optional<SnapshotCompletion> committed;
            try {
                committed = sealing ? store.sealedSnapshotCompletion(request.snapshotId())
                        : java.util.Optional.empty();
            } catch (RuntimeException reconciliationFailure) {
                failure.addSuppressed(reconciliationFailure);
                try {
                    if (staging) store.discardSnapshot(request.snapshotId());
                    progress.add(new AnalysisProgress(AnalysisJobState.FAILED, written,
                            "Analysis job failed before sealing could be confirmed."));
                    return new AnalysisJobResult(AnalysisJobState.FAILED, progress);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    progress.add(new AnalysisProgress(AnalysisJobState.RECONCILIATION_REQUIRED, written,
                            "Snapshot seal outcome requires reconciliation."));
                    return new AnalysisJobResult(AnalysisJobState.RECONCILIATION_REQUIRED, progress);
                }
            }
            if (committed.isPresent()) {
                var state = committed.orElseThrow() == SnapshotCompletion.COMPLETE
                        ? AnalysisJobState.SUCCEEDED : AnalysisJobState.PARTIAL;
                progress.add(new AnalysisProgress(state, written, "Snapshot seal outcome reconciled."));
                return new AnalysisJobResult(state, progress);
            }
            if (staging) {
                try {
                    store.discardSnapshot(request.snapshotId());
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            progress.add(new AnalysisProgress(AnalysisJobState.FAILED, written,
                    "Analysis job failed: " + failure.getClass().getSimpleName()));
            return new AnalysisJobResult(AnalysisJobState.FAILED, progress);
        }
    }

    private static final class ProgressEvents extends ArrayList<AnalysisProgress> {
        private final java.util.function.Consumer<AnalysisProgress> listener;
        private ProgressEvents(java.util.function.Consumer<AnalysisProgress> listener) { this.listener = listener; }
        @Override public boolean add(AnalysisProgress progress) { listener.accept(progress); return super.add(progress); }
    }

    private AnalysisJobResult cancelBeforeStaging(ArrayList<AnalysisProgress> progress) {
        progress.add(new AnalysisProgress(AnalysisJobState.CANCELLED, 0, "Analysis job cancelled."));
        return new AnalysisJobResult(AnalysisJobState.CANCELLED, progress);
    }

    private AnalysisJobResult cancel(AnalysisJobRequest request, ArrayList<AnalysisProgress> progress, int written) {
        store.discardSnapshot(request.snapshotId());
        progress.add(new AnalysisProgress(AnalysisJobState.CANCELLED, written, "Analysis job cancelled."));
        return new AnalysisJobResult(AnalysisJobState.CANCELLED, progress);
    }
}
