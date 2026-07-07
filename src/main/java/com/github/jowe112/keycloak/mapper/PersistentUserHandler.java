package com.github.jowe112.keycloak.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.models.UserModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles attribute enrichment for <strong>persistent</strong> (imported)
 * users.
 * <p>
 * Attributes are fetched from the REST APIs, stored as {@code UserModel}
 * attributes
 * for caching, and re-fetched only when the configured TTL has elapsed.
 * <p>
 * Cache attribute naming convention:
 * 
 * <pre>
 *   rest_claim_mapper.&lt;claimName&gt;           — the cached claim value (String)
 *   rest_claim_mapper.endpoint.N.cached_at  — epoch seconds of last fetch (String)
 * </pre>
 */
public final class PersistentUserHandler {

    private static final Logger LOG = Logger.getLogger(PersistentUserHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Marker prepended to a cached attribute value that holds a JSON-serialized
     * structured claim (see {@code json:} mapping prefix). The leading
     * {@code U+E000} is a Unicode Private-Use-Area character: it is valid UTF-8
     * (safe for text columns, unlike a NUL byte) yet never occurs in real REST
     * response data, so a plain scalar value can never be mistaken for a
     * structured one.
     */
    static final String STRUCTURED_PREFIX ="json:";

    /** Attribute prefix used for all cache keys written to UserModel. */
    public static final String CACHE_PREFIX = "rest_claim_mapper.";

    // Use a fixed thread pool to prevent thread exhaustion if the REST API is slow
    private static final int MAX_CONCURRENT_REQUESTS = 50;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS, r -> {
        Thread t = new Thread(r, "rest-claim-mapper-worker");
        t.setDaemon(true);
        return t;
    });

    private PersistentUserHandler() {
    }

    // ── Internal Record ───────────────────────────────────────────────────────

    // Holds the result from the background HTTP request so we can apply it safely
    // on the main thread
    private record EndpointFetchResult(@NotNull EndpointConfig endpoint, @NotNull Map<String, Object> claims,
            boolean success) {
    }

    /**
     * Fetches and caches REST attributes for a persistent user.
     *
     * @param user        the Keycloak UserModel (already in DB)
     * @param endpoints   parsed endpoint configurations
     * @param userContext map of user context fields (sub, email, username, …)
     * @param ttlSeconds  cache TTL in seconds
     * @return merged map of claim name → value
     */
    public static @NotNull Map<String, Object> fetchAndCache(
            @NotNull UserModel user,
            @NotNull String mapperId,
            @NotNull List<EndpointConfig> endpoints,
            @NotNull Map<String, String> userContext,
            long ttlSeconds) {

        Map<String, Object> finalClaims = new HashMap<>();
        long now = Instant.now().getEpochSecond();
        List<CompletableFuture<EndpointFetchResult>> fetchTasks = new ArrayList<>();

        for (EndpointConfig ep : endpoints) {
            if (!ep.isConfigured()) {
                continue;
            }

            String cachedAtKey = CACHE_PREFIX + mapperId + ".ep" + ep.getIndex() + ".cached_at";
            List<String> cachedAtValues = user.getAttributeStream(cachedAtKey).toList();
            boolean cacheHit = false;

            if (!cachedAtValues.isEmpty()) {
                try {
                    String[] parts = cachedAtValues.get(0).split("\\|");
                    long cachedAt = Long.parseLong(parts[0]);
                    String cachedHash = parts.length > 1 ? parts[1] : "";

                    if ((now - cachedAt) < ttlSeconds && cachedHash.equals(ep.getConfigHash())) {
                        // Within TTL and config hash matches — read from user attributes
                        LOG.debugf("Cache hit for endpoint %d, user %s", ep.getIndex(), user.getId());
                        Map<String, Object> cachedClaims = readFromCache(user, mapperId, ep);
                        finalClaims.putAll(cachedClaims);
                        cacheHit = true;
                    }
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                    // Corrupt cache — re-fetch
                }
            }

            if (!cacheHit) {
                // Cache miss or stale — dispatch fetch to background thread
                LOG.debugf("Cache miss for endpoint %d, user %s — fetching from REST API",
                        ep.getIndex(), user.getId());
                Map<String, String> scriptVars = buildScriptVars(ep, userContext);

                CompletableFuture<EndpointFetchResult> future = CompletableFuture.supplyAsync(() -> {
                    String queryString = QueryScriptEvaluator.evaluate(ep.getQueryScript(), scriptVars);
                    String rawJson = RestApiClient.getInstance().fetchJson(ep, queryString);

                    if (rawJson == null) {
                        LOG.warnf("Endpoint %d returned no data for user %s", ep.getIndex(), user.getId());
                        return new EndpointFetchResult(ep, Map.of(), false);
                    }

                    Map<String, Object> mapped = JsonPathMapper.map(rawJson, ep.getMappingRules());
                    return new EndpointFetchResult(ep, mapped, true);
                }, EXECUTOR);

                fetchTasks.add(future);
            }
        }

        // Wait for all fetch tasks and write results to the UserModel sequentially on
        // the main thread
        // We enforce a hard timeout (10 seconds) so we never block Keycloak token
        // issuance indefinitely
        for (CompletableFuture<EndpointFetchResult> future : fetchTasks) {
            try {
                EndpointFetchResult result = future.get(10, TimeUnit.SECONDS);
                Map<String, Object> mappedClaims = result.claims();
                EndpointConfig ep = result.endpoint();

                if (result.success()) {
                    finalClaims.putAll(mappedClaims);

                    // Persist to Keycloak UserModel attributes (JPA requires an active transaction)
                    // This MUST run on the main thread
                    for (Map.Entry<String, Object> entry : mappedClaims.entrySet()) {
                        String attrKey = CACHE_PREFIX + mapperId + "." + entry.getKey();
                        Object val = entry.getValue();
                        if (isStructuredValue(val)) {
                            user.setSingleAttribute(attrKey, encodeStructured(val));
                        } else if (val instanceof List<?> list) {
                            user.setAttribute(attrKey, list.stream().map(Object::toString).toList());
                        } else {
                            user.setSingleAttribute(attrKey, val.toString());
                        }
                    }

                    // Update cached_at timestamp with the config hash
                    String cachedAtKey = CACHE_PREFIX + mapperId + ".ep" + ep.getIndex() + ".cached_at";
                    String cacheValue = now + "|" + ep.getConfigHash();
                    user.setSingleAttribute(cachedAtKey, cacheValue);
                }
            } catch (TimeoutException e) {
                LOG.errorf(e, "Endpoint fetch timed out after 10 seconds for user %s", user.getId());
            } catch (Exception e) {
                LOG.errorf(e, "Unexpected error collecting endpoint result");
            }
        }

        return finalClaims;
    }

    /** Reads cached claims from UserModel attributes. */
    private static @NotNull Map<String, Object> readFromCache(@NotNull UserModel user, @NotNull String mapperId,
            @NotNull EndpointConfig ep) {
        Map<String, Object> result = new HashMap<>();
        for (MappingRule rule : ep.getMappingRules()) {
            String attrKey = CACHE_PREFIX + mapperId + "." + rule.getClaimName();
            List<String> values = user.getAttributeStream(attrKey).toList();
            Object decoded = decodeCached(values);
            if (decoded != null) {
                result.put(rule.getClaimName(), decoded);
            }
        }
        return result;
    }

    /**
     * A value is "structured" when it carries JSON object shape that must be
     * preserved in the cache — a {@link Map}, or a {@link List} whose first
     * element is a {@link Map}. Plain scalars and lists of scalars are not.
     */
    static boolean isStructuredValue(Object val) {
        if (val instanceof Map) return true;
        if (val instanceof List<?> list && !list.isEmpty()) {
            return list.get(0) instanceof Map;
        }
        return false;
    }

    /**
     * Encodes a structured value for single-attribute storage: the JSON
     * serialization prefixed with {@link #STRUCTURED_PREFIX}.
     */
    static @NotNull String encodeStructured(@NotNull Object structuredValue) throws JsonProcessingException {
        return STRUCTURED_PREFIX + OBJECT_MAPPER.writeValueAsString(structuredValue);
    }

    /**
     * Decodes cached attribute values back into a claim value, reversing the
     * write-side encoding:
     * <ul>
     * <li>a single value carrying the {@link #STRUCTURED_PREFIX} marker → the
     * deserialized JSON structure;</li>
     * <li>a single plain value → that {@code String} (even if it happens to
     * start with {@code "json:"} — only the marker triggers structured
     * decoding);</li>
     * <li>multiple values → the {@code List<String>} as stored.</li>
     * </ul>
     *
     * @return the decoded value, or {@code null} if there is nothing cached or
     *         a structured value fails to deserialize
     */
    static @Nullable Object decodeCached(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String first = values.get(0);
        if (first.startsWith(STRUCTURED_PREFIX)) {
            try {
                return OBJECT_MAPPER.readValue(first.substring(STRUCTURED_PREFIX.length()), Object.class);
            } catch (JsonProcessingException e) {
                LOG.warnf("Failed to deserialize structured cache value: %s", e.getMessage());
                return null;
            }
        }
        return values.size() == 1 ? first : values;
    }

    /** Builds variable map for the query script from userContext. */
    private static @NotNull Map<String, String> buildScriptVars(@NotNull EndpointConfig ep,
            @NotNull Map<String, String> userContext) {
        Map<String, String> vars = new HashMap<>();
        for (String param : ep.getQueryParams()) {
            vars.put(param, userContext.getOrDefault(param, ""));
        }
        return vars;
    }
}
