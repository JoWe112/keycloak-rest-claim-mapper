# kc-rest-claim-mapper

A **Keycloak 26.x custom OIDC Protocol Mapper** that enriches federated users with attributes fetched from one or more external REST APIs at token issuance time.

## Features

- 🔌 Works with **any existing federation** (LDAP, AD, or any User Storage SPI)
- 🔄 **Persistent users** (imported): attributes are cached in `UserModel` with a configurable TTL; re-fetched automatically when stale
- ⚡ **Transient users** (non-imported): attributes fetched live at every token issuance
- 🌐 Up to **3 configurable REST API endpoints** executed in *parallel* (significantly faster than configuring multiple separate Keycloak mappers)
- 🔐 Supports **API key**, **Basic Auth**, and **OAuth2 client credentials** authentication
- 📜 **GraalVM Polyglot JS** for dynamic query string construction (`query.script`)
- 🗂️ **JSONPath** (Jayway) and plain field mapping to OIDC claims
- 🧪 **Test Query panel** — live REST endpoint testing via Admin API without a real user login
- 📦 Deployed as a single fat JAR in `/opt/keycloak/providers/`

## Quick Start

### 1. Build

```bash
mvn clean package
```

This produces `target/kc-rest-claim-mapper-1.0.0.jar` (a shaded fat JAR with all dependencies).

### 2. Deploy

```bash
cp target/kc-rest-claim-mapper-1.0.0.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start
```

### 3. Configure

In the Keycloak Admin Console:

- **Per client**: `Clients → <client> → Client Scopes → <client>-dedicated → Add Mapper → By Configuration → REST Attribute Enrichment`
- **Shared (all clients)**: `Client Scopes → Create scope → Mappers → Add Mapper → By Configuration → REST Attribute Enrichment`

See [`docs/ADMIN_GUIDE.md`](docs/ADMIN_GUIDE.md) for the full configuration reference.

## Documentation

| Document | Description |
|---|---|
| [ADMIN_GUIDE.md](docs/ADMIN_GUIDE.md) | Mapper configuration, Client Scope setup, Test Query panel |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Build, deploy, and verify on Keycloak 26.x |
| [CACHING.md](docs/CACHING.md) | TTL caching strategy for persistent users |
| [ERROR_HANDLING.md](docs/ERROR_HANDLING.md) | Logging strategy and graceful degradation |

## Configuration Reference (Quick)

| Key | Description |
|---|---|
| `endpoint.count` | Number of active endpoints (1–3) |
| `cache.ttl.seconds` | Cache TTL for persistent users (default: 300) |
| `endpoint.N.url` | REST API base URL |
| `endpoint.N.auth.type` | `apikey`, `basic`, or `oauth2` |
| `endpoint.N.auth.value` | API key, base64 encoded `username:password`, or `clientId:clientSecret:tokenUrl` |
| `endpoint.N.query.param.K` | User context field name (e.g. `username`, `email`, `sub`) |
| `endpoint.N.query.script` | JS expression building the query string |
| `endpoint.N.mapping` | `apiField→claimName` pairs (comma-separated, JSONPath supported) |

## Project Structure

```
src/main/java/com/github/jowe112/keycloak/
  mapper/
    RestClaimMapper.java          # Main mapper (extends AbstractOIDCProtocolMapper)
    ConfigParser.java             # Parses KC config map → List<EndpointConfig>
    EndpointConfig.java           # Per-endpoint config POJO
    MappingRule.java              # apiField→claimName mapping rule
    QueryScriptEvaluator.java     # GraalVM Polyglot JS evaluation
    RestApiClient.java            # Apache HttpClient 5 wrapper (apikey + oauth2)
    JsonPathMapper.java           # Jayway JSONPath + Jackson field mapping
    PersistentUserHandler.java    # TTL cache via UserModel attributes
    TransientUserHandler.java     # Live fetch, no persistence
  admin/
    TestQueryResourceProvider.java        # JAX-RS test-query resource
    TestQueryResourceProviderFactory.java # RealmResourceProviderFactory

src/main/resources/META-INF/services/
  org.keycloak.protocol.ProtocolMapper
  org.keycloak.services.resource.RealmResourceProviderFactory
```

## Requirements

- Keycloak **26.x** (Quarkus distribution, GraalVM JDK)
- Java **21**
- Maven **3.9+**

## License

MIT
