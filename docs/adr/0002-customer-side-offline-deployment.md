# Keep customer source and analysis data inside the customer environment

The MVP is deployed on customer-controlled Linux or WSL2 infrastructure, with source read-only and no default external network use. The analyzer never executes customer Maven or Gradle code and consumes only explicit or prepared offline classpaths plus best-effort static metadata; any future dependency acquisition is a separate, explicit, checksummed operation. Offline packages, local reports, redacted diagnostics, and customer-controlled purge preserve the boundary needed for source-code pilots.
