# graphCRUD

An evidence-first code knowledge graph for tracing code paths to database CRUD facts. Its first delivery focuses on a bounded Java persistence stack.

## Analysis scope

**Phase-one target stack**:
Java services using Spring and MyBatis, with PostgreSQL SQL. Python, inter-service calls, and message-bus links are outside the first delivery.
_Avoid_: multi-language MVP, full enterprise scope

**CRUD fact**:
A source-backed assertion that a resolved code method reads from or writes to a specific database table through SQL.
_Avoid_: inferred database impact, guessed data access
