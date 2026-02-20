package com.github.jowe112.keycloak.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps fields from a JSON API response to OIDC claim values according to
 * a list of {@link MappingRule}s.
 * <p>
 * Simple field names are resolved with Jackson; JSONPath expressions (starting
 * with {@code "$"}) are resolved with Jayway JSONPath. Scalar values become
 * {@code String}; arrays become {@code List<String>}.
 */
public final class JsonPathMapper {

    private static final Logger LOG = Logger.getLogger(JsonPathMapper.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonPathMapper() {
    }

    /**
     * Apply mapping rules to a raw JSON string.
     *
     * @param rawJson      the full JSON response body from the REST API
     * @param mappingRules the ordered list of field→claim rules
     * @return a map of {@code claimName → value} (String or List&lt;String&gt;)
     */
    public static Map<String, Object> map(String rawJson, List<MappingRule> mappingRules) {
        Map<String, Object> claims = new HashMap<>();

        if (rawJson == null || rawJson.isBlank() || mappingRules == null || mappingRules.isEmpty()) {
            return claims;
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rawJson);
        } catch (Exception e) {
            LOG.errorf("Failed to parse JSON response: %s", e.getMessage());
            return claims;
        }

        for (MappingRule rule : mappingRules) {
            try {
                Object value = rule.isJsonPath()
                        ? resolveJsonPath(rawJson, rule.getApiField())
                        : resolveSimpleField(root, rule.getApiField());

                if (value != null) {
                    claims.put(rule.getClaimName(), value);
                } else {
                    LOG.debugf("Mapping rule '%s' returned null for field '%s'",
                            rule, rule.getApiField());
                }
            } catch (Exception e) {
                LOG.warnf("Error applying mapping rule '%s': %s", rule, e.getMessage());
            }
        }

        return claims;
    }

    // -------------------------------------------------------------------------

    private static Object resolveSimpleField(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull())
            return null;
        return nodeToValue(node);
    }

    private static Object resolveJsonPath(String rawJson, String expression) {
        try {
            Object value = JsonPath.read(rawJson, expression);
            if (value == null)
                return null;
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    result.add(item == null ? "" : item.toString());
                }
                return result.size() == 1 ? result.get(0) : result;
            }
            return value.toString();
        } catch (PathNotFoundException e) {
            LOG.debugf("JSONPath '%s' not found in response", expression);
            return null;
        }
    }

    private static Object nodeToValue(JsonNode node) {
        if (node.isArray()) {
            List<String> list = new ArrayList<>();
            for (JsonNode element : node) {
                list.add(element.isNull() ? "" : element.asText());
            }
            return list.isEmpty() ? null : (list.size() == 1 ? list.get(0) : list);
        }
        return node.asText();
    }
}
