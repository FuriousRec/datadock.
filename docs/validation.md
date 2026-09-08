# Validation record

Recorded during initial implementation, 2026-09-08.

## Executed

- Java 17 compiler module compiled the actual domain classes and CoreChecks runner.
- All **33 CSV/domain checks passed** through `sh scripts/test-core.sh`.

- Java compiler syntax parsing passed for all 11 Java source files (not full type checking).
- Maven XML, workflow/Compose YAML and OpenAPI JSON parsed successfully.
- Git whitespace checks passed.

## Not yet executed

- Maven dependency resolution / full Spring Boot compilation.
- JUnit and real PostgreSQL HTTP integration tests.
- Docker build and Docker Compose startup.
- GitHub Actions execution.
- Bicep compilation, Azure resource provisioning, cloud deployment or smoke tests.

No CI success badges or claims of a running cloud service should be added until verified.
Update this record with actual command outputs and run URLs when those gates pass.
