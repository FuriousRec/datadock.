# Design decisions

## Bounded synchronous ingestion

Files are capped at 2 MiB / 10,000 rows so validation can finish in memory before writing.
This avoids a queue, worker coordination and asynchronous job-state management for the first
version. It is not a design for arbitrarily large files or high upload concurrency.
Multipart parsing can happen before authentication reaches the controller, so the servlet
upload limits are important; add ingress rate limiting before public high-volume use.

## Atomicity and concurrent duplicates

An import transaction inserts the batch row and all sales. A unique SHA-256 constraint on
the batch table is the final authority for duplicate detection, not a check-then-insert race.
Two identical simultaneous uploads produce one successful import and one duplicate conflict.
The duplicate exception escapes the transactional service so Spring can roll it back before
the HTTP exception handler returns 409.

SHA-256 hashes the original bytes. This is reproducible, but does not deduplicate different
serializations of the same business data. Production ingestion would normally require a
source-system identifier and an immutable event ID.

## Decimal money and database-side analytics

BigDecimal and NUMERIC avoid binary floating-point rounding. Currency is fixed to EUR;
adding currencies without grouping and conversion rules would make the summary misleading.
Queries aggregate in PostgreSQL instead of loading all records into Java. A date index
supports filtering. Pagination sorts by date then unique ID. Offset pages can shift during concurrent imports,
so the sales endpoint also supports an optional `(soldAt, id)` keyset cursor. The cursor
avoids duplicates and gaps caused by rows inserted before the current position; clients
still need to retain the last returned cursor and restart if their filter changes.

## Authentication boundary

One shared API key protects a single-tenant portfolio instance. This is not user login,
OAuth, role-based access control or multi-tenancy. There is no browser frontend, session or
cookie authentication. Do not place the key in URLs, browser code, logs or source control.
Use HTTPS in cloud deployments. The health endpoints intentionally disclose only status.

## Cloud responsibilities

Container Apps runs the container. A user-assigned identity pulls the private ACR image.
GitHub deploys through an OIDC federated identity rather than a stored Azure client secret.
The app uses a database password supplied as a Container Apps secret; managed PostgreSQL
identity authentication and Key Vault references are future improvements, not implemented claims.

The Bicep template assumes an existing registry and reachable PostgreSQL server. For a
production deployment, integrate the environment with a VNet and PostgreSQL private access.
The current template does not provision that network. Do not open PostgreSQL to the entire
internet to make the example work.

## Explicit limits

No async worker, S3/Blob storage, UI, tenancy, distributed tracing, benchmark results,
production uptime record, file-retention system or automatic database backup configuration.
Raw uploaded files are not persisted. Import metadata retains hash, ID, count and timestamp.


## Import provenance

Every sale response includes the UUID of its import batch. The authenticated
`GET /api/v1/imports/{id}` endpoint resolves that identifier to the batch row
count, byte-level SHA-256 and database creation timestamp. This makes the API's
lineage information usable without exposing database access. Missing valid UUIDs
return `404`; malformed UUID paths remain client errors.


## Import history pagination

The authenticated `GET /api/v1/imports` endpoint lists newest batches first.
It uses the compound cursor `(createdAt, id)` instead of an offset so imports
committed between requests do not shift later pages. Both cursor fields are
required together, the page size is capped at 100, and a matching descending
database index keeps the query bounded as the history grows.
