# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-04-30

### Added
- **Structured JSON claims** — prefix the claim name with `json:` (e.g. `$.data.User[0].team→json:teams`) to map an entire array of objects into a single OIDC/SAML claim, preserving the full JSON structure instead of flattening to `List<String>` ([#47](https://github.com/JoWe112/keycloak-rest-claim-mapper/pull/47))

### Fixed
- Normalized JSONPath output through Jackson to guarantee well-formed JSON in the token regardless of Jayway's internal provider types ([#47](https://github.com/JoWe112/keycloak-rest-claim-mapper/pull/47))
- Suppressed spurious GraalVM `truffleattach` warning at Keycloak startup when running from a fat JAR ([#47](https://github.com/JoWe112/keycloak-rest-claim-mapper/pull/47))
- Suppressed JBoss LogManager initialisation warning during `mvn clean package` ([#47](https://github.com/JoWe112/keycloak-rest-claim-mapper/pull/47))

### Docs
- Added tip explaining that appending `[0]` to a JSONPath expression forces a string claim value instead of an array ([#48](https://github.com/JoWe112/keycloak-rest-claim-mapper/pull/48))
- Expanded GraphQL section with structured JSON claim examples and notes on token size, SAML behaviour, and caching

### Dependencies
- Keycloak `26.5.5` → `26.6.2`
- GraalVM `25.0.2` → `25.0.3`
- Apache HttpClient5 `5.4.3` → `5.6.1`
- slf4j-api `2.0.17` → `2.0.18`
- JUnit Jupiter `5.11.4` → `5.12.2`
- `softprops/action-gh-release` `v1` → `v2`

## [1.1.0] - 2025-01-01

### Added
- Initial multi-endpoint REST claim mapper with parallel execution
- Support for persistent (imported) and transient (non-imported) users
- JSONPath and plain field mapping to OIDC and SAML claims
- GraalVM Polyglot JS query script evaluation
- TTL-based caching for persistent users
- Test Query admin panel
- API key, Basic Auth, and OAuth2 client credentials authentication

## [1.0.0] - 2024-11-01

### Added
- Initial release
