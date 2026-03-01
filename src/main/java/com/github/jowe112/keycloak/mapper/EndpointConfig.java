package com.github.jowe112.keycloak.mapper;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the parsed configuration for a single numbered REST endpoint.
 * <p>
 * Instances are created by {@link ConfigParser} from the Keycloak mapper
 * configuration map.
 */
public final class EndpointConfig {

    /** Base URL of the REST API, e.g. {@code https://api.example.com/users}. */
    private final String url;

    /**
     * Authentication type: {@code "apikey"}, {@code "basic"}, or {@code "oauth2"}.
     */
    private final String authType;

    /**
     * Authentication value:
     * <ul>
     * <li>For {@code apikey}: the raw API key sent as {@code X-API-Key}
     * header.</li>
     * <li>For {@code basic}: pre-encoded Base64 {@code username:password} string,
     * sent as {@code Authorization: Basic <value>}.</li>
     * <li>For {@code oauth2}: {@code clientId:clientSecret:tokenUrl}.</li>
     * </ul>
     */
    private final String authValue;

    /**
     * Ordered list of Keycloak user context field names whose values are
     * injected as variables into {@link #queryScript}.
     * E.g. {@code ["username", "email"]}.
     */
    private final List<String> queryParams;

    /**
     * JavaScript expression evaluated by GraalVM Polyglot.
     * Variables declared in {@link #queryParams} are available by name.
     * Returns the query string to append to {@link #url},
     * e.g. {@code "?user=" + username + "&mail=" + email}.
     */
    private final String queryScript;

    /**
     * Ordered list of field-to-claim mapping rules.
     */
    private final List<MappingRule> mappingRules;

    /** 1-based endpoint index; used for cache-key namespacing. */
    private final int index;

    public EndpointConfig(int index, @Nullable String url, @NotNull String authType, @Nullable String authValue,
            @NotNull List<String> queryParams, @Nullable String queryScript,
            @NotNull List<MappingRule> mappingRules) {
        this.index = index;
        this.url = url;
        this.authType = authType;
        this.authValue = authValue;
        this.queryParams = new ArrayList<>(queryParams);
        this.queryScript = queryScript;
        this.mappingRules = new ArrayList<>(mappingRules);
    }

    public int getIndex() {
        return index;
    }

    public @Nullable String getUrl() {
        return url;
    }

    public @NotNull String getAuthType() {
        return authType;
    }

    public @Nullable String getAuthValue() {
        return authValue;
    }

    public @NotNull List<String> getQueryParams() {
        return queryParams;
    }

    public @Nullable String getQueryScript() {
        return queryScript;
    }

    public @NotNull List<MappingRule> getMappingRules() {
        return mappingRules;
    }

    /**
     * Returns true if this endpoint has a non-empty URL configured.
     */
    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    /**
     * Returns a deterministic hash of this endpoint's configuration.
     * This is used to invalidate the cache immediately if the admin
     * changes the URL, mapping, script, or auth settings.
     */
    public @NotNull String getConfigHash() {
        int hash = 17;
        hash = 31 * hash + (url != null ? url.hashCode() : 0);
        hash = 31 * hash + (authType != null ? authType.hashCode() : 0);
        hash = 31 * hash + (authValue != null ? authValue.hashCode() : 0);
        hash = 31 * hash + (queryScript != null ? queryScript.hashCode() : 0);
        for (String p : queryParams) {
            hash = 31 * hash + p.hashCode();
        }
        for (MappingRule r : mappingRules) {
            hash = 31 * hash + r.getApiField().hashCode();
            hash = 31 * hash + r.getClaimName().hashCode();
        }
        return Integer.toHexString(hash);
    }
}
