# Build an evidence-first graph from normalized facts

All source, configuration, SQL, and database adapters emit immutable facts with source locations and evidence levels. A single resolver normalizes them before GraphStore persists the graph, so static facts, manual mappings, and future runtime observations remain distinguishable and explainable.
