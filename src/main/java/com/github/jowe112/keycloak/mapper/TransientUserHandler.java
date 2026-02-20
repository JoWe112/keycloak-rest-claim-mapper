package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles attribute enrichment for <strong>transient</strong> (non-imported)
 * users.
 * <p>
 * Claims are fetched live from the REST APIs on every token issuance. Nothing
 * is persisted — the user has no Keycloak local storage. All endpoints are
 * called in parallel.
 */
public final class TransientUserHandler {

    private static final Logger LOG = Logger.getLogger(TransientUserHandler.class);

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rest-claim-mapper-transient-worker");
        t.setDaemon(true);
        return t;
    });

    private TransientUserHandler() {
    }

    /**
     * Fetches REST attributes live for a transient user and returns the merged
     * claims map. No data is written anywhere.
     *
     * @param endpoints   parsed endpoint configurations
     * @param userContext map of user context fields (sub, email, username, …)
     * @return merged map of claim name → value (String or List&lt;String&gt;)
     */
    public static Map<String, Object> fetchLive(
            List<EndpointConfig> endpoints,
            Map<String, String> userContext) {

        Map<String, Object> claims = new HashMap<>();

        List<CompletableFuture<Map<String, Object>>> futures = endpoints.stream()
                .filter(EndpointConfig::isConfigured)
                .map(ep -> CompletableFuture.supplyAsync(
                        () -> fetchForEndpoint(ep, userContext), EXECUTOR))
                .toList();

        for (CompletableFuture<Map<String, Object>> future : futures) {
            try {
                claims.putAll(future.join());
            } catch (Exception e) {
                LOG.errorf(e, "Transient: unexpected error collecting endpoint result");
            }
        }

        return claims;
    }

    // ── Per-endpoint logic ────────────────────────────────────────────────────

    private static Map<String, Object> fetchForEndpoint(
            EndpointConfig ep,
            Map<String, String> userContext) {

        Map<String, String> scriptVars = new HashMap<>();
        for (String param : ep.getQueryParams()) {
            scriptVars.put(param, userContext.getOrDefault(param, ""));
        }

        String queryString = QueryScriptEvaluator.evaluate(ep.getQueryScript(), scriptVars);
        String rawJson = RestApiClient.getInstance().fetchJson(ep, queryString);

        if (rawJson == null) {
            LOG.warnf("Transient: endpoint %d returned no data", ep.getIndex());
            return Map.of();
        }

        return JsonPathMapper.map(rawJson, ep.getMappingRules());
    }
}
