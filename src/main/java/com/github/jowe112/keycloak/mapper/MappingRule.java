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
 * <p>
 * Prefixing the claim name with {@code json:} (e.g. {@code "json:users"})
 * preserves the full JSON structure of the resolved value instead of flattening
 * arrays to {@code List<String>}.
 */
public final class MappingRule {

    private static final String JSON_PREFIX = "json:";

    private final String apiField;
    private final String claimName;
    private final boolean preserveJsonStructure;

    public MappingRule(@NotNull String apiField, @NotNull String claimName) {
        this.apiField = apiField;
        if (claimName.startsWith(JSON_PREFIX)) {
            this.claimName = claimName.substring(JSON_PREFIX.length());
            this.preserveJsonStructure = true;
        } else {
            this.claimName = claimName;
            this.preserveJsonStructure = false;
        }
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

    /**
     * Returns true if the original claim name was prefixed with {@code json:},
     * indicating that the resolved value should preserve its full JSON structure.
     */
    public boolean isPreserveJsonStructure() {
        return preserveJsonStructure;
    }

    @Override
    public @NotNull String toString() {
        return apiField + "→" + (preserveJsonStructure ? JSON_PREFIX : "") + claimName;
    }
}
