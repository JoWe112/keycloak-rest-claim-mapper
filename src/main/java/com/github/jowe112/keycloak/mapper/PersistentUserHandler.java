package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;
import org.keycloak.models.UserModel;

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

    /** Attribute prefix used for all cache keys written to UserModel. */
    public static final String CACHE_PREFIX = "rest_claim_mapper.";

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rest-claim-mapper-worker");
        t.setDaemon(true);
        return t;
    });

    private PersistentUserHandler() {
    }

    // ── Internal Record ───────────────────────────────────────────────────────

    // Holds the result from the background HTTP request so we can apply it safely
    // on the main thread
    private record EndpointFetchResult(EndpointConfig endpoint, Map<String, Object> claims, boolean success) {
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
    public static Map<String, Object> fetchAndCache(
            UserModel user,
            List<EndpointConfig> endpoints,
            Map<String, String> userContext,
            long ttlSeconds) {

        Map<String, Object> finalClaims = new HashMap<>();
        long now = Instant.now().getEpochSecond();
        List<CompletableFuture<EndpointFetchResult>> fetchTasks = new ArrayList<>();

        for (EndpointConfig ep : endpoints) {
            if (!ep.isConfigured()) {
                continue;
            }

            String cachedAtKey = CACHE_PREFIX + "endpoint." + ep.getIndex() + ".cached_at";
            List<String> cachedAtValues = user.getAttributeStream(cachedAtKey).toList();
            boolean cacheHit = false;

            if (!cachedAtValues.isEmpty()) {
                try {
                    long cachedAt = Long.parseLong(cachedAtValues.get(0));
                    if ((now - cachedAt) < ttlSeconds) {
                        // Within TTL — read from user attributes
                        LOG.debugf("Cache hit for endpoint %d, user %s", ep.getIndex(), user.getId());
                        Map<String, Object> cachedClaims = readFromCache(user, ep);
                        finalClaims.putAll(cachedClaims);
                        cacheHit = true;
                    }
                } catch (NumberFormatException ignored) {
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
                        String attrKey = CACHE_PREFIX + entry.getKey();
                        Object val = entry.getValue();
                        if (val instanceof List<?> list) {
                            user.setAttribute(attrKey, list.stream().map(Object::toString).toList());
                        } else {
                            user.setSingleAttribute(attrKey, val.toString());
                        }
                    }

                    // Update cached_at timestamp
                    String cachedAtKey = CACHE_PREFIX + "endpoint." + ep.getIndex() + ".cached_at";
                    user.setSingleAttribute(cachedAtKey, String.valueOf(now));
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
    private static Map<String, Object> readFromCache(UserModel user, EndpointConfig ep) {
        Map<String, Object> result = new HashMap<>();
        for (MappingRule rule : ep.getMappingRules()) {
            String attrKey = CACHE_PREFIX + rule.getClaimName();
            List<String> values = user.getAttributeStream(attrKey).toList();
            if (!values.isEmpty()) {
                result.put(rule.getClaimName(), values.size() == 1 ? values.get(0) : values);
            }
        }
        return result;
    }

    /** Builds variable map for the query script from userContext. */
    private static Map<String, String> buildScriptVars(EndpointConfig ep,
            Map<String, String> userContext) {
        Map<String, String> vars = new HashMap<>();
        for (String param : ep.getQueryParams()) {
            vars.put(param, userContext.getOrDefault(param, ""));
        }
        return vars;
    }
}
