package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.mappers.*;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.IDToken;
import org.keycloak.representations.JsonWebToken;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Keycloak custom OIDC Protocol Mapper: <strong>REST Attribute
 * Enrichment</strong>.
 * <p>
 * At token issuance time this mapper:
 * <ol>
 * <li>Reads its configuration (up to {@value ConfigParser#MAX_ENDPOINTS} REST
 * endpoints).</li>
 * <li>Builds the user context (sub, username, email, …).</li>
 * <li>Detects whether the user is persistent (imported) or transient
 * (non-imported).</li>
 * <li>Delegates attribute enrichment to {@link PersistentUserHandler} or
 * {@link TransientUserHandler}.</li>
 * <li>Writes the returned claim values into the token.</li>
 * </ol>
 *
 * <h3>Registration</h3>
 * Registered via
 * {@code META-INF/services/org.keycloak.protocol.ProtocolMapper}.
 *
 * <h3>Deployment</h3>
 * Build a fat JAR and copy to {@code /opt/keycloak/providers/}.
 */
public class RestClaimMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, UserInfoTokenMapper {

    private static final Logger LOG = Logger.getLogger(RestClaimMapper.class);

    // ── Provider IDs ──────────────────────────────────────────────────────────

    public static final String PROVIDER_ID = "rest-claim-mapper";
    public static final String DISPLAY_TYPE = "REST Attribute Enrichment";
    public static final String DISPLAY_CATEGORY = TOKEN_MAPPER_CATEGORY;

    // ── Config key constants ──────────────────────────────────────────────────

    public static final String CFG_ENDPOINT_COUNT = "endpoint.count";
    public static final String CFG_CACHE_TTL = "cache.ttl.seconds";

    // ── Config property definitions ──────────────────────────────────────────

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES;

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
                    ProviderConfigProperty.SCRIPT_TYPE, "\"\""));

            props.add(cfgProp(prefix + ".mapping",
                    "Endpoint " + n + ": Claim Mapping",
                    "Comma-separated 'apiField→claimName' pairs. Supports JSONPath: "
                            + "$.user.dept→user_dept,role→user_role",
                    ProviderConfigProperty.STRING_TYPE, ""));
        }

        CONFIG_PROPERTIES = Collections.unmodifiableList(props);
    }

    // ── AbstractOIDCProtocolMapper overrides ──────────────────────────────────

    @Override
    public @NotNull String getId() {
        return PROVIDER_ID;
    }

    @Override
    public @NotNull String getDisplayType() {
        return DISPLAY_TYPE;
    }

    @Override
    public @NotNull String getDisplayCategory() {
        return DISPLAY_CATEGORY;
    }

    @Override
    public @NotNull String getHelpText() {
        return "Enriches tokens with attributes fetched from one or more external REST APIs. "
                + "Works for any federation (LDAP, AD, etc.) and supports both persistent "
                + "(imported) and transient (non-imported) users.";
    }

    @Override
    public @NotNull List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    // ── Token transformation entry points ─────────────────────────────────────

    @Override
    public @NotNull AccessToken transformAccessToken(@NotNull AccessToken token,
            @NotNull ProtocolMapperModel mappingModel,
            @NotNull KeycloakSession session,
            @NotNull UserSessionModel userSession,
            @Nullable ClientSessionContext clientSessionCtx) {
        addClaims(token, mappingModel, userSession);
        return token;
    }

    @Override
    public @NotNull IDToken transformIDToken(@NotNull IDToken token,
            @NotNull ProtocolMapperModel mappingModel,
            @NotNull KeycloakSession session,
            @NotNull UserSessionModel userSession,
            @Nullable ClientSessionContext clientSessionCtx) {
        // IDToken extends AccessToken so we can pass it directly
        addClaims(token, mappingModel, userSession);
        return token;
    }

    @Override
    public @NotNull AccessToken transformUserInfoToken(@NotNull AccessToken token,
            @NotNull ProtocolMapperModel mappingModel,
            @NotNull KeycloakSession session,
            @NotNull UserSessionModel userSession,
            @Nullable ClientSessionContext clientSessionCtx) {
        addClaims(token, mappingModel, userSession);
        return token;
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    /**
     * Resolves REST claims and adds them to the token.
     * Works for AccessToken, IDToken (both extend JsonWebToken which has
     * getOtherClaims).
     */
    private void addClaims(@NotNull JsonWebToken token,
            @NotNull ProtocolMapperModel mappingModel,
            @NotNull UserSessionModel userSession) {
        try {
            Map<String, String> config = mappingModel.getConfig();
            List<EndpointConfig> endpoints = ConfigParser.parse(config);

            if (endpoints.isEmpty()) {
                LOG.debugf("No endpoints configured for mapper '%s'", mappingModel.getName());
                return;
            }

            UserModel user = userSession.getUser();
            Map<String, String> userCtx = buildUserContext(user, userSession);

            long ttl = parseLongOrDefault(config.get(CFG_CACHE_TTL), 300L);

            Map<String, Object> claims;
            if (isPersistentUser(user)) {
                claims = PersistentUserHandler.fetchAndCache(user, endpoints, userCtx, ttl);
            } else {
                claims = TransientUserHandler.fetchLive(endpoints, userCtx);
            }

            setClaims(token, claims);

        } catch (Exception e) {
            // Never block token issuance
            LOG.errorf(e, "RestClaimMapper: unexpected error — claims skipped for session %s",
                    userSession.getId());
        }
    }

    /**
     * Writes claim values into the token's other claims map.
     */
    private void setClaims(@NotNull JsonWebToken token, @NotNull Map<String, Object> claims) {
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            token.getOtherClaims().put(entry.getKey(), entry.getValue());
        }
    }

    // ── User context ──────────────────────────────────────────────────────────

    /**
     * Builds a flat map of Keycloak user context fields that can be
     * referenced by query scripts.
     */
    private @NotNull Map<String, String> buildUserContext(@NotNull UserModel user,
            @NotNull UserSessionModel userSession) {
        Map<String, String> ctx = new HashMap<>();
        // Standard OIDC claims
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
    private boolean isPersistentUser(@NotNull UserModel user) {
        // A user coming from federation-without-import has no local Keycloak storage;
        // StorageId.isLocalStorage() checks if the ID is a plain UUID (local storage).
        try {
            return org.keycloak.storage.StorageId.isLocalStorage(user.getId());
        } catch (Exception e) {
            LOG.debugf("Could not determine storage type for user %s — treating as transient",
                    user.getId());
            return false;
        }
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private static @NotNull String nvl(@Nullable String s) {
        return s != null ? s : "";
    }

    private static long parseLongOrDefault(@Nullable String value, long defaultValue) {
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
