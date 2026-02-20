# Admin Guide — REST Attribute Enrichment Mapper

## Table of Contents
1. [Per-Client Mapper Setup](#per-client-mapper-setup)
2. [Shared Client Scope Setup](#shared-client-scope-setup)
3. [Configuration Reference](#configuration-reference)
4. [Test Query Panel](#test-query-panel)
5. [Full Example](#full-example)

---

## Per-Client Mapper Setup

Use this when you want the REST enrichment on a single client only.

1. **Admin Console → Clients → `<your-client>` → Client Scopes**
2. Click the `<your-client>-dedicated` scope link.
3. **Mappers → Add Mapper → By Configuration**
4. Select **REST Attribute Enrichment** from the list.
5. Fill in the configuration fields (see [Configuration Reference](#configuration-reference)).
6. Click **Save**.

The claims will appear in the Access Token, ID Token, and UserInfo response for that client.

---

## Shared Client Scope Setup

Use this when you want the same REST enrichment across multiple clients without repeating configuration.

1. **Admin Console → Client Scopes → Create client scope**
   - Name: e.g. `rest-enrichment`
   - Type: `Default` (automatically added to all clients) or `Optional`
   - Click **Save**
2. Open the new scope → **Mappers → Add Mapper → By Configuration**
3. Select **REST Attribute Enrichment** and configure it.
4. Click **Save**.
5. **To assign to an existing client**: `Clients → <client> → Client Scopes → Add client scope → rest-enrichment`

All clients that include this scope will now receive the enriched claims.

---

## Configuration Reference

### General Settings

| Config Key | Label | Description | Default |
|---|---|---|---|
| `endpoint.count` | Number of Endpoints | How many endpoint slots are active (1–5). Only slots 1..N are read. | `1` |
| `cache.ttl.seconds` | Cache TTL (seconds) | For **persistent** (imported) users: how many seconds to cache REST attributes in `UserModel` before re-fetching. | `300` |

### Per-Endpoint Settings (repeat for N = 1..5)

| Config Key | Label | Description |
|---|---|---|
| `endpoint.N.url` | Endpoint N: URL | Base REST API URL. Leave blank to disable this slot. |
| `endpoint.N.auth.type` | Endpoint N: Auth Type | `apikey` or `oauth2` |
| `endpoint.N.auth.value` | Endpoint N: Auth Value | For `apikey`: the key sent as `X-API-Key`. For `oauth2`: `clientId:clientSecret:tokenUrl`. |
| `endpoint.N.query.param.1` … `query.param.5` | Endpoint N: Query Param K | Keycloak user context field whose value is injected as a JS variable. Examples: `username`, `email`, `sub`, `firstName`. |
| `endpoint.N.query.script` | Endpoint N: Query Script | JavaScript expression (GraalVM) that returns the query string. Declared params are available as variables. |
| `endpoint.N.mapping` | Endpoint N: Claim Mapping | Comma-separated `apiField→claimName` pairs. Supports JSONPath (prefix with `$`). |

### Available User Context Variables

These field names can be used in `query.param.K` and are then available in `query.script`:

| Variable | Source |
|---|---|
| `sub` | `user.getId()` |
| `username` | `user.getUsername()` |
| `email` | `user.getEmail()` |
| `firstName` | `user.getFirstName()` |
| `lastName` | `user.getLastName()` |
| `sessionId` | `userSession.getId()` |
| `<attribute>` | Any flat user attribute (first value) |

### Claim Mapping Syntax

The `endpoint.N.mapping` value is a comma-separated list of rules:

```
role→user_role,department→user_dept,$.profile.groups[0]→first_group
```

- **Plain name** (`role→user_role`): uses Jackson to read `response["role"]`
- **JSONPath** (`$.user.profile.dept→user_dept`): uses Jayway JSONPath
- Multi-value: if the API returns a JSON array, the claim becomes a `List<String>`

---

## Test Query Panel

The mapper registers a realm-scoped REST endpoint to test your config without a real login:

```
POST /realms/{realm}/rest-claim-mapper/test-query
Authorization: Bearer <admin-token>
Content-Type: application/json
```

**Request body:**
```json
{
  "url":         "https://api.example.com/users",
  "authType":    "apikey",
  "authValue":   "my-secret-key",
  "queryParams": ["username", "email"],
  "queryScript": "\"?user=\" + username + \"&mail=\" + email",
  "mapping":     "role→user_role,department→user_dept",
  "testVars": {
    "username": "jdoe",
    "email":    "jdoe@example.com"
  }
}
```

**Response:**
```json
{
  "queryString":  "?user=jdoe&mail=jdoe@example.com",
  "rawResponse":  "{\"role\":\"admin\",\"department\":\"Engineering\"}",
  "mappedClaims": {
    "user_role": "admin",
    "user_dept": "Engineering"
  },
  "error": null
}
```

### Getting an Admin Token

```bash
TOKEN=$(curl -s -X POST \
  "https://keycloak.example.com/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=<pw>" \
  | jq -r .access_token)

curl -s -X POST \
  "https://keycloak.example.com/realms/myrealm/rest-claim-mapper/test-query" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ ... }'
```

---

## Full Example

### Scenario

- Endpoint 1: user roles from API key–protected service
- Endpoint 2: department from OAuth2-protected profile service
- Cache TTL: 5 minutes

### Mapper Configuration

| Key | Value |
|---|---|
| `endpoint.count` | `2` |
| `cache.ttl.seconds` | `300` |
| `endpoint.1.url` | `https://api.example.com/users` |
| `endpoint.1.auth.type` | `apikey` |
| `endpoint.1.auth.value` | `my-secret-key` |
| `endpoint.1.query.param.1` | `username` |
| `endpoint.1.query.param.2` | `email` |
| `endpoint.1.query.script` | `"?user=" + username + "&mail=" + email` |
| `endpoint.1.mapping` | `role→user_role,department→user_dept` |
| `endpoint.2.url` | `https://api.example.com/profile` |
| `endpoint.2.auth.type` | `oauth2` |
| `endpoint.2.auth.value` | `clientId:clientSecret:https://auth.example.com/token` |
| `endpoint.2.query.param.1` | `sub` |
| `endpoint.2.query.script` | `"?id=" + sub` |
| `endpoint.2.mapping` | `$.user.profile.department→user_dept` |

### Resulting JWT Claims

```json
{
  "sub": "abc123",
  "username": "jdoe",
  "user_role": "admin",
  "user_dept": "Engineering"
}
```
