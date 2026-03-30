# Contributing

## Reporting bugs

Use the **Bug report** issue template. Include your Keycloak version, REST API type, query format, and relevant logs.

## Proposing features

Use the **Feature request** issue template. Describe the problem you're solving and the solution you have in mind.

## Development setup

**Requirements:** Java 21, Maven 3.9+, Keycloak 26.x

```bash
# Build
mvn clean package

# Build + run tests
mvn clean verify

# Skip tests
mvn clean package -DskipTests
```

Deploy the resulting JAR from `target/` to `/opt/keycloak/providers/` and restart Keycloak.

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for full deployment instructions.

## Workflow

1. **Open or find an issue** before writing code.
2. **Branch** from `main` using `<type>/<issue-number>-short-description`:
   - `fix/42-null-pointer-in-token-mapper`
   - `feat/17-oauth2-token-refresh`
3. **Commit** using [Conventional Commits](https://www.conventionalcommits.org/):
   - `fix(mapper): handle null attribute list`
   - `feat(admin): add test-query endpoint for transient users`
4. **Open a PR** — the PR template will guide you. Make sure `Closes #<issue>` is in the body.
5. **CI must pass** before merging. Request at least one review.

## Documentation

Update `docs/` alongside any logic or API changes. See [AGENTS.md](AGENTS.md) for the full documentation policy.
