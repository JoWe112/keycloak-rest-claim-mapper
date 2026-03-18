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

    // ── Config property definitions ──────────────────────────────────────────

    // Properties are sourced from shared RestMapperConfig

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
        return RestMapperConfig.COMMON_PROPERTIES;
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
            Map<String, String> userCtx = RestMapperConfig.buildUserContext(user, userSession);

            long ttl = RestMapperConfig.parseLongOrDefault(config.get(RestMapperConfig.CFG_CACHE_TTL), 300L);

            Map<String, Object> claims;
            if (RestMapperConfig.isPersistentUser(user)) {
                claims = PersistentUserHandler.fetchAndCache(user, mappingModel.getId(), endpoints, userCtx, ttl);
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

    // ── Utility helpers are now in RestMapperConfig ───────────────────────────
}
