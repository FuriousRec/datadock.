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

## Additional packaging gates

CI now also compiles the Bicep template and starts the Docker Compose stack.
`scripts/smoke-test.py` waits for readiness, checks authentication, imports the sample,
checks exact totals and rejects a duplicate. It requires an empty disposable database.
Check the latest Verify run for the result of these newly added gates.

## Still outstanding

Azure provisioning, cloud deployment, and live cloud smoke testing have not been performed.
Bicep compilation alone does not validate subscription permissions, quotas or network connectivity.
