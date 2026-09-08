# Validation record

## Verified on GitHub Actions

[Successful main-branch run](https://github.com/FuriousRec/datadock./actions/runs/34254011987)
for commit `ec9323eeb981eefd3b7b7bae5cd2e944cf821fc6`, 2026-09-08:

- `mvn -B verify`: dependency resolution, compilation, unit tests and real PostgreSQL HTTP integration tests passed.
- `docker build -t datadock:ci .`: application container built successfully.
- Test reports uploaded as the run's `test-results` artifact.

## Verified locally

- 33 CSV/domain checks passed using `sh scripts/test-core.sh`.
- All 11 Java files passed syntax parsing; configuration files parsed successfully.

## Verified packaging and infrastructure compilation

[Successful complete verification run](https://github.com/FuriousRec/datadock./actions/runs/34278137123), 2026-09-08:

- Maven unit and real PostgreSQL integration tests passed again.
- Docker image built and the Docker Compose application started successfully.
- Sample API smoke test passed: readiness, unauthorized request rejection, import,
  duplicate rejection, and exact totals of 3 rows / 6 units / EUR 357.30.
- Azure Bicep template compiled successfully.

The first smoke-check attempt exposed an unhandled startup connection reset in the
check script. Retrying transient startup connections fixed it; post-readiness API
assertions remain strict. The successful run above includes the fix.

## Still outstanding

Azure provisioning, cloud deployment, and live cloud smoke testing have not been performed.
Bicep compilation alone does not validate subscription permissions, quotas or network connectivity.
