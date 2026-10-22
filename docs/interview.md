# Explain the project, then put it on your CV

The goal is demonstrable understanding. Do not claim work experience, deployment, users,
throughput or cloud operation that has not happened.

## Questions to practise

1. Why validate the full CSV before writing? What happens when record 500 is invalid?
2. Why does a pre-insert duplicate lookup fail under concurrency?
3. Why use BigDecimal and NUMERIC instead of double?
4. What are the limits of file-byte SHA-256 deduplication?
5. Why is database readiness different from application liveness?
6. Which identity deploys the app, and which identity pulls its image?
7. Why can a previous container image be incompatible with a new database migration?
8. What would you change for ten-million-row files or multiple customer accounts?

## Honest milestone wording

After you review and understand the implementation, but before deployment:

“Developed a Java/Spring Boot sales-ingestion API with PostgreSQL, transactional CSV imports,
duplicate-upload protection, decimal revenue analytics, and Docker packaging.”

Only add “tested” after the relevant suite passes. Only after completing deployment and
verifying it yourself:

“Deployed the API to Azure Container Apps, configured health probes and secret injection,
and automated tested image deployment through GitHub Actions with OIDC.”

Do not add adoption, latency, savings or reliability numbers unless measured. Retain a
redacted deployment screenshot, workflow run link and short demo for evidence.


## Why expose import provenance?

A sale's `importId` is only useful lineage if a client can resolve it. The
provenance endpoint connects query results back to the original batch metadata:
row count, content hash and commit timestamp. It supports audit and debugging
without returning the uploaded file or weakening API-key protection.
