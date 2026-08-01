package io.graphcrud.application;

import java.util.List;

public record Coverage(boolean complete, List<String> affectedRegions, List<String> reasons) {
    public Coverage { affectedRegions = List.copyOf(affectedRegions); reasons = List.copyOf(reasons); }
    public static Coverage forCompletion(SnapshotCompletion completion) {
        return completion == SnapshotCompletion.COMPLETE ? new Coverage(true, List.of(), List.of())
                : new Coverage(false, List.of("snapshot"), List.of("Analysis completed with unresolved or possible evidence."));
    }
}
