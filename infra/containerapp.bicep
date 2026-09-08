param location string = resourceGroup().location
param appName string = 'datadock'
param registryName string
param imageTag string
param databaseUrl string
param databaseUser string
@secure()
param databasePassword string
@secure()
param apiKey string

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: registryName
}

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: '${appName}-pull'
  location: location
}

resource pullRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, identity.id, 'acrpull')
  scope: registry
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource workspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: '${appName}-logs'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
  }
}

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: '${appName}-environment'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: workspace.properties.customerId
        sharedKey: workspace.listKeys().primarySharedKey
      }
    }
  }
}

resource app 'Microsoft.App/containerApps@2024-03-01' = {
  name: appName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${identity.id}': {} }
  }
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 8080
        allowInsecure: false
        transport: 'http'
      }
      registries: [{
        server: registry.properties.loginServer
        identity: identity.id
      }]
      secrets: [
        {
          name: 'database-password'
          value: databasePassword
        }
        {
          name: 'api-key'
          value: apiKey
        }
      ]
    }
    template: {
      containers: [{
        name: 'api'
        image: '${registry.properties.loginServer}/datadock:${imageTag}'
        resources: {
          cpu: json('0.5')
          memory: '1Gi'
        }
        env: [
          {
            name: 'DB_URL'
            value: databaseUrl
          }
          {
            name: 'DB_USER'
            value: databaseUser
          }
          {
            name: 'DB_PASSWORD'
            secretRef: 'database-password'
          }
          {
            name: 'APP_API_KEY'
            secretRef: 'api-key'
          }
        ]
        probes: [
          {
            type: 'Startup'
            httpGet: {
              path: '/actuator/health/liveness'
              port: 8080
            }
            periodSeconds: 10
            failureThreshold: 30
          }
          {
            type: 'Liveness'
            httpGet: {
              path: '/actuator/health/liveness'
              port: 8080
            }
            periodSeconds: 30
          }
          {
            type: 'Readiness'
            httpGet: {
              path: '/actuator/health/readiness'
              port: 8080
            }
            periodSeconds: 10
          }
        ]
      }]
      scale: {
        minReplicas: 0
        maxReplicas: 2
      }
    }
  }
  dependsOn: [pullRole]
}

output endpoint string = 'https://${app.properties.configuration.ingress.fqdn}'
