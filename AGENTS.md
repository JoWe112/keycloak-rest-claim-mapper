# AGENTS.md

## Documentation Guidelines

To ensure every project is well-documented and maintainable, the following rules MUST be followed:

### 1. Always Create or Update README.md

- Every project must have a `README.md` file in the root directory.
- The `README.md` must be optimized for **GitHub**.
- Include clear sections for:
    - **Project Overview**: What the project does and why it exists.
    - **Installation/Setup**: Step-by-step instructions for local development.
    - **Usage**: Examples and commands to run the application.
    - **Architecture**: A brief overview of the system design.
    - **API Reference**: If applicable, documentation of public endpoints or methods.
- **Rich Aesthetics**: Use clear headings, tables, and Mermaid diagrams to make the README visually appealing and easy to scan.

### 2. Prioritize Central Documentation

- Always search for and update central documentation files, especially in `docs/`.
- If a `docs/` folder exists, ensure it is kept in sync with any logic or feature changes.
- If a new complex feature is added, consider creating a dedicated markdown file in `docs/` for it.

### 3. Maintain Inline Documentation

- Ensure all functions, classes, and complex logic blocks include clear and concise comments (Javadoc).
- Update these internal comments for every logic change to prevent them from becoming stale.

### 4. Keep Documentation in Sync

- Any changes to existing logic or APIs must be reflected in the relevant documentation files (README, docs, etc.) in the same turn.
- Never leave documentation updates for "later"—they are a core part of the feature delivery.

### 5. Automated Documentation Searches

- Before completing a task, use grep or file listing tools to identify all existing documentation that might be affected by your changes.


## Git

### Issue-first workflow

Always create or reference a GitHub issue before making code changes:

1. Check for an existing issue that covers the work.
2. If none exists, create one using the appropriate issue template (bug or feature).
3. Reference the issue number in every branch name, commit, and PR.

### Branch naming

Use the format `<type>/<issue-number>-short-description`:

- `fix/42-null-pointer-in-token-mapper`
- `feat/17-oauth2-token-refresh`
- `docs/8-update-admin-guide`
- `chore/31-bump-keycloak-dependency`

### Commit style (Conventional Commits)

Format: `<type>(<scope>): <short description>`

- `fix(mapper): handle null attribute list in RestClaimMapper`
- `feat(admin): add test-query endpoint for transient users`
- `docs(caching): clarify TTL invalidation behavior`
- `chore(deps): bump keycloak to 26.5.5`

Types: `fix`, `feat`, `docs`, `chore`, `refactor`, `test`, `ci`

### Pull requests

- One issue per PR — keep PRs focused.
- Title format: `<type>: <short description> (#<issue>)` — e.g. `fix: handle empty claim list (#42)`
- Body must include `Closes #<issue>` so GitHub auto-closes the issue on merge.
- CI build must pass before merging.
- Request at least one review before merging into `main`.

### Code-review

Review pull request and look for bugs and security issues. Only report on bugs and potential vulnerabilities you find. Be concise.
