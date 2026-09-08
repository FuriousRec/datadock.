# Azure deployment runbook

Status: configuration supplied; not deployed or validated in Azure yet.

## Before deployment

1. Run `mvn verify` with Java 17, Maven and Docker. Resolve failures first.
2. Create an Azure resource group in your intended region.
3. Create an Azure Container Registry in that resource group.
4. Provision PostgreSQL Flexible Server and create a `datadock` database and an application
   user allowed to run Flyway DDL in that database. Record its host and credentials.
5. Arrange restricted network connectivity between Container Apps and PostgreSQL.
   The supplied template does not provision a VNet. Extend it for VNet/private-database
   access, or configure narrowly scoped approved public egress rules for a development
   environment. Do not use a blanket allow-all database firewall rule.
6. Generate a random API key of at least 32 characters. Use real secrets, never `.env.example`.
7. Review costs for PostgreSQL, registry, Container Apps and Log Analytics; configure an
   Azure budget alert and a teardown plan.

## First image and infrastructure

Install Azure CLI and Bicep. Log in to your own account and select the intended subscription.
Set shell variables `RESOURCE_GROUP` and `ACR_NAME` to the existing resources.

Build the first image in ACR:

```bash
az acr build --registry "$ACR_NAME" --image datadock:bootstrap .
az bicep build --file infra/containerapp.bicep
```

Create a parameter file **outside the repository**. Supply registryName, imageTag
(`bootstrap`), databaseUrl, databaseUser, databasePassword and apiKey. Use the JDBC URL:

```text
jdbc:postgresql://YOUR_SERVER.postgres.database.azure.com:5432/datadock?sslmode=verify-full
```

The password and API key parameters are marked secure in Bicep. Keep the parameter file
private and delete it securely when no longer needed. Prefer Key Vault integration for
long-lived deployments.

Deploy the template using the private parameter file:

```bash
az deployment group create --resource-group "$RESOURCE_GROUP" \
  --template-file infra/containerapp.bicep --parameters @/private/path/datadock.parameters.json
```

Creating the AcrPull role assignment requires appropriate Azure RBAC privileges.
Allow time for role-assignment propagation if the initial private image pull fails.
The template emits an HTTPS endpoint. Verify readiness, then import the sample file and
confirm EUR 357.30. API calls require the new key; health checks do not.

## GitHub OIDC setup

Create a Microsoft Entra application/service principal for deployments. Add a federated
credential for the repository's **production environment**, using the subject:
`repo:FuriousRec/datadock.:environment:production` and audience `api://AzureADTokenExchange`.
Use your actual repository name if different.

Grant the deployment identity only the permissions required to build images in ACR and
update this Container App. Scope permissions to the relevant resources, not the subscription.
The first infrastructure deployment and role assignment can be performed separately by
an administrator; the deployment workflow only updates the application image.

Create a GitHub environment named `production` and configure these variables:

| Variable | Value |
|---|---|
| AZURE_CLIENT_ID | Deployment application's client ID |
| AZURE_TENANT_ID | Entra tenant ID |
| AZURE_SUBSCRIPTION_ID | Target subscription |
| AZURE_RESOURCE_GROUP | Existing resource group |
| ACR_NAME | Registry resource name, without domain |
| CONTAINER_APP_NAME | Container App name, normally datadock |

Restrict the environment to main and enable reviewer approval if supported by your plan.
Run **Deploy to Azure** manually from Actions. It verifies tests, builds an image tagged
with the commit SHA, updates the app and checks readiness. It does not create the database
or configure its secrets.

## Operations and teardown

- Inspect revisions and console logs in Container Apps.
- Keep liveness separate from database readiness to avoid restart loops during DB outages.
- Save previous image SHA tags; rollback uses `az containerapp update --image ...` with a
  known good tag. Database migrations must remain backward compatible for image rollback.
- Monitor budget alerts and log retention.
- Delete only a resource group dedicated to this demo when finished. Review its contents
  first; deleting a group also deletes its database and data.

Official references:
- https://learn.microsoft.com/en-us/azure/container-apps/github-actions
- https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-azure
