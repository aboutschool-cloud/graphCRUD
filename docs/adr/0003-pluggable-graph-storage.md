# Isolate graph storage behind a capability contract

Neo4j is the first GraphStore adapter, but the core depends only on a capability contract and portable JSONL facts. This avoids coupling the product to Neo4j Cypher or its GPLv3 Community Edition in customer delivery, while requiring every alternative backend to pass the same graph and query tests.
