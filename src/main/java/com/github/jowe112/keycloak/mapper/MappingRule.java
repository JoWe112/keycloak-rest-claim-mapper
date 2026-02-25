package com.github.jowe112.keycloak.mapper;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a single field mapping rule.
 * <p>
 * {@code apiField} is either a simple JSON field name (e.g. {@code "role"}) or
 * a
 * JSONPath expression starting with {@code "$"} (e.g.
 * {@code "$.user.profile.dept"}).
 * {@code claimName} is the OIDC claim name to write into the token.
 */
public final class MappingRule {

    private final String apiField;
    private final String claimName;

    public MappingRule(@NotNull String apiField, @NotNull String claimName) {
        this.apiField = apiField;
        this.claimName = claimName;
    }

    public @NotNull String getApiField() {
        return apiField;
    }

    public @NotNull String getClaimName() {
        return claimName;
    }

    /**
     * Returns true if this rule uses JSONPath notation (starts with {@code "$"}).
     */
    public boolean isJsonPath() {
        return apiField != null && apiField.startsWith("$");
    }

    @Override
    public @NotNull String toString() {
        return apiField + "→" + claimName;
    }
}
