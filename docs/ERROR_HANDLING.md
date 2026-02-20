# Error Handling & Logging Strategy

## Design Principle

> **Authentication is never blocked by REST API failures.**

If any endpoint fails, the mapper logs the error and continues.  Available claims from
other endpoints are still added to the token.  The token is always issued.

## Logging

The mapper uses **JBoss Logging** (already on the classpath in Keycloak).  Log levels
follow standard Keycloak conventions:

| Class | Logger Name |
|---|---|
| `RestClaimMapper` | `com.github.jowe112.keycloak.mapper.RestClaimMapper` |
| `RestApiClient` | `com.github.jowe112.keycloak.mapper.RestApiClient` |
| `QueryScriptEvaluator` | `com.github.jowe112.keycloak.mapper.QueryScriptEvaluator` |
| `JsonPathMapper` | `com.github.jowe112.keycloak.mapper.JsonPathMapper` |
| `PersistentUserHandler` | `com.github.jowe112.keycloak.mapper.PersistentUserHandler` |
| `TransientUserHandler` | `com.github.jowe112.keycloak.mapper.TransientUserHandler` |

### Enabling Debug Logging

Add to your `keycloak.conf`:
```
log-level=com.github.jowe112.keycloak:DEBUG
```

Or for a running instance via `kc.sh`:
```bash
/opt/keycloak/bin/kcadm.sh update serverinfo -s 'loglevel={"com.github.jowe112.keycloak":"DEBUG"}'
```

## Error Scenarios and Handling

| Scenario | Handling |
|---|---|
| REST API unreachable / timeout | `RestApiClient` logs `ERROR` with URL and exception. Returns `null`. Claims from that endpoint are skipped. |
| REST API returns non-2xx | `RestApiClient` logs `ERROR` with status code and body. Returns `null`. |
| OAuth2 token fetch fails | `RestApiClient` logs `ERROR`. Returns `null`. No `Authorization` header is sent; the data call may then fail with 401. |
| `query.script` JS error | `QueryScriptEvaluator` logs `ERROR` with script and `PolyglotException` message. Returns `""`. |
| Malformed JSON response | `JsonPathMapper` logs `ERROR`. Returns empty map. |
| JSONPath not found in response | `JsonPathMapper` logs `DEBUG` (not an error — field may be optional). |
| Malformed `mapping` rule | `ConfigParser` logs `WARN` and skips the rule. |
| Unexpected exception in mapper | `RestClaimMapper.addClaims()` catches and logs `ERROR`. Token is still issued without REST claims. |
| Cache attribute corrupt (NaN) | `PersistentUserHandler` logs `DEBUG` and re-fetches. |

## What Is Never Logged

- The content of `auth.value` (API keys, client secrets) — only the auth type is logged.
- The raw JSON response body (only the status code on error; bodies may contain PII).
- User passwords or session tokens.

## Monitoring Recommendations

Create an alert on the following log pattern in your SIEM/log aggregator:

```
logger=com.github.jowe112.keycloak.mapper.RestClaimMapper level=ERROR
```

This indicates token enrichment is failing that will result in missing claims.

Check in combination with:
```
logger=com.github.jowe112.keycloak.mapper.RestApiClient level=ERROR
```
to identify which endpoint is failing.
