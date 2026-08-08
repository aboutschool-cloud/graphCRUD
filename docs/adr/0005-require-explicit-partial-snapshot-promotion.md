# Require explicit promotion of partial snapshots

Partial snapshots are sealed and queryable by ID but do not automatically replace the active snapshot. Keeping the last accepted result as the default favors trustworthy customer queries over freshness; a user can promote a partial snapshot explicitly or opt into accepting partial results before analysis when its reported coverage is adequate.
