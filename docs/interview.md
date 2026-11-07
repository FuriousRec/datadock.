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
8. Why aggregate daily trends in PostgreSQL instead of loading every sale into Java?
9. What would you change for ten-million-row files or multiple customer accounts?
10. Why does the product ranking use `ORDER BY revenue DESC, sku` instead of revenue alone?

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


## Why use a compound cursor for import history?

Timestamps alone are not guaranteed to be unique. Ordering by `createdAt` and
UUID gives every batch a deterministic position. Passing the final pair from
one response into the next request avoids offset shifts when a newer import is
committed during traversal.


## Why expose daily analytics?

A summary answers “how much,” but not “when.” The daily endpoint lets clients plot a
trend without downloading every sale. PostgreSQL groups exact NUMERIC values close to
the data and orders the result deterministically. Empty days are omitted so the API does
not invent observations; charting clients can add zero-valued calendar gaps if needed.


## Why aggregate top products in PostgreSQL?

The endpoint answers a common business question without returning every sale to the client.
PostgreSQL groups repeated SKU rows and sums NUMERIC revenue exactly. A bounded limit controls
response and database work, while the SKU tie-breaker makes equal-revenue rankings reproducible.
