package com.github.jowe112.keycloak.mapper;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses Keycloak mapper configuration into a list of {@link EndpointConfig}
 * objects.
 * <p>
 * Config keys follow the convention:
 * 
 * <pre>
 *   endpoint.count              — integer, how many endpoints are active (1-3)
 *   endpoint.N.url
 *   endpoint.N.auth.type        — "apikey" | "basic" | "oauth2"
 *   endpoint.N.auth.value
 *   endpoint.N.query.param.K   — K in 1..5; value is a Keycloak user context field name
 *   endpoint.N.query.script
 *   endpoint.N.mapping          — comma-separated "apiField→claimName" pairs
 * </pre>
 */
public final class ConfigParser {

    private static final Logger LOG = Logger.getLogger(ConfigParser.class);

    /** Maximum number of endpoints per mapper instance. */
    public static final int MAX_ENDPOINTS = 3;

    /** Maximum number of query parameters per endpoint. */
    public static final int MAX_QUERY_PARAMS = 5;

    private ConfigParser() {
    }

    /**
     * Parses the raw Keycloak config map and returns a list of configured
     * endpoints.
     *
     * @param config the mapper config (from {@code mappingModel.getConfig()})
     * @return list of resolved, configured endpoints (may be empty if nothing is
     *         set)
     */
    public static List<EndpointConfig> parse(Map<String, String> config) {
        List<EndpointConfig> endpoints = new ArrayList<>();

        int endpointCount = parseIntOrDefault(config.get("endpoint.count"), MAX_ENDPOINTS);

        for (int n = 1; n <= endpointCount; n++) {
            String url = config.get("endpoint." + n + ".url");
            if (url == null || url.isBlank()) {
                continue; // slot not configured
            }

            String authType = config.getOrDefault("endpoint." + n + ".auth.type", "apikey");
            String authValue = config.getOrDefault("endpoint." + n + ".auth.value", "");
            String script = config.getOrDefault("endpoint." + n + ".query.script", "\"\"");
            String mapping = config.getOrDefault("endpoint." + n + ".mapping", "");

            List<String> queryParams = new ArrayList<>();
            for (int k = 1; k <= MAX_QUERY_PARAMS; k++) {
                String param = config.get("endpoint." + n + ".query.param." + k);
                if (param != null && !param.isBlank()) {
                    queryParams.add(param.trim());
                }
            }

            List<MappingRule> rules = parseMappingRules(mapping);

            endpoints.add(new EndpointConfig(n, url.trim(), authType.trim(), authValue,
                    queryParams, script, rules));
        }

        return endpoints;
    }

    /**
     * Parses a comma-separated mapping string into a list of {@link MappingRule}s.
     * Each entry must be of the form {@code apiField→claimName} or
     * {@code apiField->claimName}.
     */
    public static List<MappingRule> parseMappingRules(String mapping) {
        List<MappingRule> rules = new ArrayList<>();
        if (mapping == null || mapping.isBlank()) {
            return rules;
        }
        for (String entry : mapping.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty())
                continue;

            // Support both → (U+2192) and -> (ASCII)
            String[] parts = entry.split("→|->", 2);
            if (parts.length != 2) {
                LOG.warnf("Skipping malformed mapping rule (expected 'apiField→claimName'): %s", entry);
                continue;
            }
            String apiField = parts[0].trim();
            String claimName = parts[1].trim();
            if (apiField.isEmpty() || claimName.isEmpty()) {
                LOG.warnf("Skipping mapping rule with empty field or claim: %s", entry);
                continue;
            }
            rules.add(new MappingRule(apiField, claimName));
        }
        return rules;
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank())
            return defaultValue;
        try {
            int v = Integer.parseInt(value.trim());
            return (v < 1) ? defaultValue : Math.min(v, MAX_ENDPOINTS);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
