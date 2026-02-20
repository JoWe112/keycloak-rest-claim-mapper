package com.github.jowe112.keycloak.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jowe112.keycloak.mapper.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

import java.util.List;
import java.util.Map;

/**
 * JAX-RS resource provider for the Test Query panel.
 * <p>
 * Exposed at: {@code /realms/{realm}/rest-claim-mapper/test-query}
 * <p>
 * Allows Keycloak admins to validate endpoint configuration by:
 * <ol>
 * <li>Evaluating the {@code query.script} with supplied test variable
 * values.</li>
 * <li>Making a live HTTP call to the configured endpoint.</li>
 * <li>Returning the raw JSON response and the mapped claim results.</li>
 * </ol>
 */
public class TestQueryResourceProvider implements RealmResourceProvider {

    private static final Logger LOG = Logger.getLogger(TestQueryResourceProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public TestQueryResourceProvider(KeycloakSession session) {
        // session not needed for this stateless resource
    }

    @Override
    public Object getResource() {
        return this;
    }

    @Override
    public void close() {
        // nothing to close
    }

    // ── REST endpoint ─────────────────────────────────────────────────────────

    /**
     * Test a single endpoint configuration.
     * <p>
     * Request body (JSON):
     * 
     * <pre>
     * {
     *   "url":         "https://api.example.com/users",
     *   "authType":    "apikey",
     *   "authValue":   "my-secret-key",
     *   "queryParams": ["username", "email"],
     *   "queryScript": "\"?user=\" + username + \"&mail=\" + email",
     *   "mapping":     "role→user_role,department→user_dept",
     *   "testVars": {
     *     "username": "jdoe",
     *     "email":    "jdoe@example.com"
     *   }
     * }
     * </pre>
     *
     * Response body (JSON):
     * 
     * <pre>
     * {
     *   "queryString":  "?user=jdoe&mail=jdoe@example.com",
     *   "rawResponse":  "{ ... }",
     *   "mappedClaims": { "user_role": "admin" },
     *   "error":        null
     * }
     * </pre>
     */
    @POST
    @Path("test-query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response testQuery(TestQueryRequest req) {
        TestQueryResponse resp = new TestQueryResponse();

        try {
            if (req == null || req.url == null || req.url.isBlank()) {
                resp.error = "URL must not be empty";
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(JSON.writeValueAsString(resp)).build();
            }

            // Parse mapping rules
            List<MappingRule> rules = ConfigParser.parseMappingRules(
                    req.mapping != null ? req.mapping : "");

            // Build EndpointConfig (index=1, used only for logging)
            EndpointConfig ep = new EndpointConfig(
                    1,
                    req.url,
                    req.authType != null ? req.authType : "apikey",
                    req.authValue != null ? req.authValue : "",
                    req.queryParams != null ? req.queryParams : List.of(),
                    req.queryScript != null ? req.queryScript : "\"\"",
                    rules);

            // Evaluate query script
            Map<String, String> testVars = req.testVars != null ? req.testVars : Map.of();
            resp.queryString = QueryScriptEvaluator.evaluate(ep.getQueryScript(), testVars);

            // Live HTTP call
            String rawJson = RestApiClient.getInstance().fetchJson(ep, resp.queryString);
            resp.rawResponse = rawJson;

            if (rawJson == null) {
                resp.error = "REST API call returned no response (check URL, auth, and server logs)";
                return Response.status(Response.Status.OK)
                        .entity(JSON.writeValueAsString(resp)).build();
            }

            // Apply mapping
            resp.mappedClaims = JsonPathMapper.map(rawJson, rules);

        } catch (Exception e) {
            LOG.errorf(e, "Test query failed");
            resp.error = "Internal error: " + e.getMessage();
        }

        try {
            return Response.ok(JSON.writeValueAsString(resp)).build();
        } catch (Exception e) {
            return Response.serverError().entity("{\"error\":\"Serialization failed\"}").build();
        }
    }

    // ── Request / Response DTOs ───────────────────────────────────────────────

    public static class TestQueryRequest {
        public String url;
        public String authType;
        public String authValue;
        public List<String> queryParams;
        public String queryScript;
        public String mapping;
        public Map<String, String> testVars;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TestQueryResponse {
        public String queryString;
        public String rawResponse;
        public Map<String, Object> mappedClaims;
        public String error;
    }
}
