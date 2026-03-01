package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class RestMapperConfig {

    private static final Logger LOG = Logger.getLogger(RestMapperConfig.class);

    // ── Config key constants ──────────────────────────────────────────────────

    public static final String CFG_ENDPOINT_COUNT = "endpoint.count";
    public static final String CFG_CACHE_TTL = "cache.ttl.seconds";

    // ── Config property definitions ──────────────────────────────────────────

    public static final List<ProviderConfigProperty> COMMON_PROPERTIES;

    static {
        List<ProviderConfigProperty> props = new ArrayList<>();

        // ── General settings ─────────────────────────────────────────────────
        props.add(cfgProp(CFG_ENDPOINT_COUNT,
                "Number of Endpoints",
                "How many REST API endpoints are configured (max 3). "
                        + "Only slots 1..N are read.",
                ProviderConfigProperty.LIST_TYPE, "1",
                List.of("1", "2", "3")));

        props.add(cfgProp(CFG_CACHE_TTL,
                "Cache TTL (seconds)",
                "For persistent (imported) users: how long to cache REST API "
                        + "attributes in UserModel before re-fetching. Default: 300.",
                ProviderConfigProperty.STRING_TYPE, "300"));

        // ── Per-endpoint slots (1..3) ─────────────────────────────────────────
        for (int n = 1; n <= ConfigParser.MAX_ENDPOINTS; n++) {
            final String prefix = "endpoint." + n;

            if (n > 1) {
                props.add(cfgProp(prefix + ".separator",
                        "─── ENDPOINT " + n + " ───",
                        "Configuration section for Endpoint " + n,
                        ProviderConfigProperty.STRING_TYPE,
                        "⬇️ Configure below ⬇️"));
            }

            props.add(cfgProp(prefix + ".url",
                    "Endpoint " + n + ": URL",
                    "Base URL of REST API #" + n + ". Leave blank to disable this slot.",
                    ProviderConfigProperty.STRING_TYPE, ""));

            props.add(cfgProp(prefix + ".auth.type",
                    "Endpoint " + n + ": Auth Type",
                    "Authentication type: 'apikey', 'basic', or 'oauth2'.",
                    ProviderConfigProperty.LIST_TYPE, "apikey",
                    List.of("apikey", "basic", "oauth2")));

            props.add(cfgProp(prefix + ".auth.value",
                    "Endpoint " + n + ": Auth Value",
                    "For apikey: the API key sent as X-API-Key. "
                            + "For basic: base64 encoded 'username:password'. "
                            + "For oauth2: 'clientId:clientSecret:tokenUrl'.",
                    ProviderConfigProperty.PASSWORD, ""));

            for (int k = 1; k <= ConfigParser.MAX_QUERY_PARAMS; k++) {
                props.add(cfgProp(prefix + ".query.param." + k,
                        "Endpoint " + n + ": Query Param " + k,
                        "Keycloak user context field to expose as script variable "
                                + "(e.g. 'username', 'email', 'sub'). Leave blank to disable.",
                        ProviderConfigProperty.STRING_TYPE, ""));
            }

            props.add(cfgProp(prefix + ".query.script",
                    "Endpoint " + n + ": Query Script",
                    "JavaScript expression (GraalVM) that builds the query string. "
                            + "Declared query params are available as variables. "
                            + "Example: \"?user=\" + username + \"&mail=\" + email",
                    ProviderConfigProperty.TEXT_TYPE, "\"\""));

            props.add(cfgProp(prefix + ".mapping",
                    "Endpoint " + n + ": Claim Mapping",
                    "Comma-separated 'apiField→claimName' pairs. Supports JSONPath: "
                            + "$.user.dept→user_dept,role→user_role",
                    ProviderConfigProperty.STRING_TYPE, ""));
        }

        COMMON_PROPERTIES = Collections.unmodifiableList(props);
    }

    private RestMapperConfig() {
        // Utility class
    }

    // ── User context ──────────────────────────────────────────────────────────

    /**
     * Builds a flat map of Keycloak user context fields that can be
     * referenced by query scripts.
     */
    public static @NotNull Map<String, String> buildUserContext(@NotNull UserModel user,
            @NotNull UserSessionModel userSession) {
        Map<String, String> ctx = new HashMap<>();
        // Standard claims
        ctx.put("sub", user.getId());
        ctx.put("username", nvl(user.getUsername()));
        ctx.put("email", nvl(user.getEmail()));
        ctx.put("firstName", nvl(user.getFirstName()));
        ctx.put("lastName", nvl(user.getLastName()));
        // Session
        ctx.put("sessionId", nvl(userSession.getId()));
        // All user attributes (flat, first value)
        user.getAttributes().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                ctx.putIfAbsent(k, v.get(0));
            }
        });
        return ctx;
    }

    /**
     * Returns true if the user is a persistent (locally imported) user.
     * Transient users have no ID in the local DB.
     */
    public static boolean isPersistentUser(@NotNull UserModel user) {
        try {
            return org.keycloak.storage.StorageId.isLocalStorage(user.getId());
        } catch (Exception e) {
            LOG.debugf("Could not determine storage type for user %s — treating as transient",
                    user.getId());
            return false;
        }
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    public static @NotNull String nvl(@Nullable String s) {
        return s != null ? s : "";
    }

    public static long parseLongOrDefault(@Nullable String value, long defaultValue) {
        if (value == null || value.isBlank())
            return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static @NotNull ProviderConfigProperty cfgProp(@NotNull String name, @NotNull String label,
            @Nullable String helpText, @NotNull String type,
            @Nullable String defaultValue) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name);
        p.setLabel(label);
        p.setHelpText(helpText);
        p.setType(type);
        p.setDefaultValue(defaultValue);
        return p;
    }

    private static @NotNull ProviderConfigProperty cfgProp(@NotNull String name, @NotNull String label,
            @Nullable String helpText, @NotNull String type,
            @Nullable String defaultValue,
            @NotNull List<String> options) {
        ProviderConfigProperty p = cfgProp(name, label, helpText, type, defaultValue);
        p.setOptions(options);
        return p;
    }
}
