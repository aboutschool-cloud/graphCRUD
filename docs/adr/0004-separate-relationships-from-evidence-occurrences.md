# Separate canonical relationships from evidence occurrences

A graph relationship is a canonical semantic assertion whose identity is stable across snapshots, while every source observation is stored as a separate snapshot-specific Evidence Occurrence. This preserves multiple call sites and explanations without duplicating semantic edges, and lets evidence be added or removed without changing the logical identity of nodes or relationships.
