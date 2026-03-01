# Caching Strategy — Persistent Users

## Overview

For **persistent** (imported) users, REST API attributes are cached as `UserModel`
attributes in the Keycloak database.  This avoids a REST API call on every token
issuance and keeps latency low.

**Transient (non-imported) users** have no Keycloak DB entry, so caching is not
possible.  Their attributes are fetched live on every token issuance.

## Cache Key Naming

All cache attributes are namespaced to avoid conflicts with real user attributes:

| Attribute key | Content |
|---|---|
| `rest_claim_mapper.<mapperId>.<claimName>` | Cached claim value (String or multi-value) |
| `rest_claim_mapper.<mapperId>.ep<N>.cached_at` | `<epoch seconds>\|<configHash>` |

> **Note on `<mapperId>`**: Every instance of the REST Claim Mapper you create gets a unique UUID. This ensures that if you configure two different mappers on the same client, their cache keys will never collide.

## TTL Behaviour & Instant Invalidation

```
At token issuance:
  for each endpoint N:
    read rest_claim_mapper.<mapperId>.ep<N>.cached_at
    if missing OR (now - cached_at) >= cache.ttl.seconds OR configHash changed:
      → fetch from REST API
      → store mapped values as rest_claim_mapper.<mapperId>.<claimName>
      → store rest_claim_mapper.<mapperId>.ep<N>.cached_at = "now|newHash"
    else:
      → read values directly from rest_claim_mapper.<mapperId>.<claimName>
```

**Instant Invalidation:** The mapper hashes its configuration (URL, Auth, Mapping Rules, etc.). If you change the endpoint settings in the Keycloak UI, the `<configHash>` changes, and the cache is immediately invalidated on the very next token issuance, bypassing the TTL.

Default TTL is **300 seconds (5 minutes)**.

## Configuring TTL

Set `cache.ttl.seconds` in the mapper configuration:

| Value | Effect |
|---|---|
| `300` (default) | Re-fetch every 5 minutes |
| `0` | Always re-fetch (effectively no cache) |
| `3600` | Re-fetch every hour |
| `86400` | Re-fetch once per day |

## Force Cache Invalidation

To force an immediate re-fetch for a specific user:

```bash
# Remove the cached_at timestamp for endpoint 1 via Admin REST API
curl -s -X DELETE \
  "https://keycloak.example.com/admin/realms/myrealm/users/<userId>/attributes" \
  -H "Authorization: Bearer $TOKEN"
```

Or navigate to: `Admin Console → Users → <user> → Attributes` and delete any
attributes prefixed with `rest_claim_mapper.`.

## Storage Implications

Each configured claim adds one attribute per user to the `user_attribute` table.
With 5 endpoints × 5 claims each = up to 25 extra attribute rows per user.
These are small VARCHAR values; storage impact is negligible at typical scale.

## TTL and Session Length

Note that cached values are **per-user** (not per-session). If a user has an active
long-lived session and the TTL expires, the next token refresh will re-fetch and
update the cached values.  All sessions for that user will then see fresh values on
their next token issuance.
