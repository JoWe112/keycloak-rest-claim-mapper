package com.github.jowe112.keycloak.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.jboss.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe HTTP client for calling external REST APIs.
 * <p>
 * Uses Apache HttpClient 5 with a shared connection pool. Supports:
 * <ul>
 * <li>{@code apikey} — sends {@code X-API-Key: <value>} header.</li>
 * <li>{@code oauth2} — parses {@code clientId:clientSecret:tokenUrl}, obtains a
 * bearer token (cached in-memory until expiry - 30 s), sends
 * {@code Authorization: Bearer <token>}.</li>
 * </ul>
 * A single static instance is created lazily and shared across all mapper
 * invocations.
 */
public final class RestApiClient {

    private static final Logger LOG = Logger.getLogger(RestApiClient.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int RESPONSE_TIMEOUT_SECONDS = 10;
    private static final int MAX_TOTAL_CONNECTIONS = 50;
    private static final int MAX_PER_ROUTE = 10;

    /** Singleton HTTP client. */
    private static volatile RestApiClient INSTANCE;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── OAuth2 token cache ────────────────────────────────────────────────────
    private static final class CachedToken {
        final String token;
        final Instant expiresAt;

        CachedToken(String token, long expiresInSeconds) {
            this.token = token;
            this.expiresAt = Instant.now().plusSeconds(expiresInSeconds - 30);
        }

        boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /** Key: auth.value string (clientId:clientSecret:tokenUrl). */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    // ── Construction ─────────────────────────────────────────────────────────

    private RestApiClient() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(MAX_TOTAL_CONNECTIONS);
        cm.setDefaultMaxPerRoute(MAX_PER_ROUTE);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .setResponseTimeout(Timeout.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .build();

        this.httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    public static @NotNull RestApiClient getInstance() {
        if (INSTANCE == null) {
            synchronized (RestApiClient.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RestApiClient();
                }
            }
        }
        return INSTANCE;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches JSON from the endpoint URL + queryString using the configured auth.
     *
     * @return raw JSON string, or {@code null} on error
     */
    public @Nullable String fetchJson(@NotNull EndpointConfig endpoint, @Nullable String queryString) {
        String url = endpoint.getUrl() + (queryString != null ? queryString : "");
        try {
            HttpGet request = new HttpGet(url);
            request.setHeader("Accept", "application/json");
            request.setHeader("Content-Type", "application/json");
            request.setHeader("apollo-require-preflight", "true");

            applyAuth(endpoint, request);

            return httpClient.execute(request, response -> {
                int statusCode = response.getCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (statusCode >= 200 && statusCode < 300) {
                    return body;
                }
                LOG.errorf("REST API returned HTTP %d for URL: %s — body: %s",
                        statusCode, url, body);
                return null;
            });
        } catch (Exception e) {
            LOG.errorf(e, "HTTP call failed for endpoint %d URL: %s", endpoint.getIndex(), url);
            return null;
        }
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    private void applyAuth(@NotNull EndpointConfig endpoint, @NotNull HttpGet request) throws Exception {
        if ("oauth2".equalsIgnoreCase(endpoint.getAuthType())) {
            String token = resolveOAuth2Token(endpoint.getAuthValue());
            if (token != null) {
                request.setHeader("Authorization", "Bearer " + token);
            }
        } else if ("basic".equalsIgnoreCase(endpoint.getAuthType())) {
            String val = endpoint.getAuthValue();
            if (val != null && !val.isBlank()) {
                request.setHeader("Authorization", "Basic " + val);
            }
        } else {
            // Default: apikey
            if (endpoint.getAuthValue() != null && !endpoint.getAuthValue().isBlank()) {
                request.setHeader("X-API-Key", endpoint.getAuthValue());
            }
        }
    }

    /**
     * Resolves an OAuth2 bearer token using client credentials flow.
     * Tokens are cached in-memory until 30 seconds before expiry.
     *
     * @param authValue format: {@code clientId:clientSecret:tokenUrl}
     */
    private @Nullable String resolveOAuth2Token(@NotNull String authValue) throws Exception {
        CachedToken cached = tokenCache.get(authValue);
        if (cached != null && cached.isValid()) {
            return cached.token;
        }

        String[] parts = authValue.split(":", 3);
        if (parts.length != 3) {
            LOG.error("OAuth2 auth.value must be 'clientId:clientSecret:tokenUrl'");
            return null;
        }
        String clientId = parts[0];
        String clientSecret = parts[1];
        String tokenUrl = parts[2];

        List<NameValuePair> formParams = new ArrayList<>();
        formParams.add(new BasicNameValuePair("grant_type", "client_credentials"));
        formParams.add(new BasicNameValuePair("client_id", clientId));
        formParams.add(new BasicNameValuePair("client_secret", clientSecret));

        HttpPost post = new HttpPost(tokenUrl);
        post.setEntity(new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8));

        return httpClient.execute(post, response -> {
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (response.getCode() >= 200 && response.getCode() < 300) {
                JsonNode json = objectMapper.readTree(body);
                String token = json.path("access_token").asText(null);
                long expiresIn = json.path("expires_in").asLong(3600);
                if (token != null) {
                    tokenCache.put(authValue, new CachedToken(token, expiresIn));
                    return token;
                }
                LOG.error("OAuth2 token response did not contain access_token");
                return null;
            }
            LOG.errorf("OAuth2 token request failed with HTTP %d: %s", response.getCode(), body);
            return null;
        });
    }
}
