package com.github.jowe112.keycloak.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jowe112.keycloak.mapper.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
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

    private final KeycloakSession session;

    public TestQueryResourceProvider(KeycloakSession session) {
        this.session = session;
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
        // Authenticate and authorize BEFORE any request parsing or outbound HTTP
        // call — this endpoint makes Keycloak issue live requests to a caller-supplied
        // URL and reflects the response, so it must be restricted to realm admins.
        requireRealmAdmin();

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

    // ── Authorization ─────────────────────────────────────────────────────────

    /**
     * Ensures the caller presents a valid bearer token for a user who holds the
     * realm-management {@code manage-clients} admin role (the same permission
     * required to configure this mapper). The {@code realm-admin} composite
     * includes this role, so full realm admins also pass.
     *
     * @throws NotAuthorizedException (401) if no valid bearer token is present
     * @throws ForbiddenException     (403) if the user is not a realm admin
     */
    private void requireRealmAdmin() {
        AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        if (auth == null || auth.getUser() == null) {
            throw new NotAuthorizedException("A valid admin bearer token is required");
        }

        RealmModel realm = session.getContext().getRealm();
        ClientModel realmMgmt = realm.getClientByClientId(Constants.REALM_MANAGEMENT_CLIENT_ID);
        if (realmMgmt == null) {
            LOG.errorf("realm-management client not found in realm '%s'", realm.getName());
            throw new ForbiddenException("Admin role required");
        }

        RoleModel manageClients = realmMgmt.getRole(AdminRoles.MANAGE_CLIENTS);
        UserModel user = auth.getUser();
        if (manageClients == null || !user.hasRole(manageClients)) {
            throw new ForbiddenException("Requires the realm-management '"
                    + AdminRoles.MANAGE_CLIENTS + "' role");
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
