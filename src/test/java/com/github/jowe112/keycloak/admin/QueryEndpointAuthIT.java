package com.github.jowe112.keycloak.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the SSRF auth guard on the Test Query endpoint
 * ({@code POST /realms/{realm}/rest-claim-mapper/test-query}), added in #57.
 * <p>
 * Spins up a real Keycloak with the built provider JAR and asserts that the
 * endpoint is reachable only by a realm admin. A tiny in-process HTTP stub
 * stands in for the external REST API the mapper would call.
 */
class QueryEndpointAuthIT {

    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.6.4";
    private static final String REALM = "itest";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpServer stub;
    private static int stubPort;
    private static KeycloakContainer keycloak;

    @BeforeAll
    static void startAll() throws Exception {
        // In-process stub for the "external REST API" the mapper calls.
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/api", exchange -> {
            byte[] body = "{\"role\":\"admin\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        // Make the host stub reachable from inside the container.
        Testcontainers.exposeHostPorts(stubPort);

        keycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
                .withProviderLibsFrom(List.of(findProviderJar()))
                .withRealmImportFile("/itest-realm.json");
        keycloak.start();

        createTestUsers();
    }

    /**
     * Creates the two test users via the admin client so passwords are set as
     * non-temporary with no pending required actions (realm-import users hit
     * "Account is not fully set up" on a direct-access grant otherwise).
     */
    private static void createTestUsers() {
        try (Keycloak admin = keycloak.getKeycloakAdminClient()) {
            RealmResource realm = admin.realm(REALM);

            createUser(realm, "plainuser", "plainpw");
            String adminUserId = createUser(realm, "adminuser", "adminpw");

            // Grant the realm-management manage-clients role to adminuser.
            ClientRepresentation realmMgmt = realm.clients()
                    .findByClientId("realm-management").get(0);
            RoleRepresentation manageClients = realm.clients().get(realmMgmt.getId())
                    .roles().get("manage-clients").toRepresentation();
            realm.users().get(adminUserId).roles()
                    .clientLevel(realmMgmt.getId()).add(List.of(manageClients));
        }
    }

    private static String createUser(RealmResource realm, String username, String password) {
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(false);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        user.setEmailVerified(true);
        // KC 26's declarative user profile requires a complete profile; an
        // incomplete one adds a VERIFY_PROFILE action → "Account is not fully set up".
        user.setEmail(username + "@example.com");
        user.setFirstName(username);
        user.setLastName("Test");
        user.setRequiredActions(List.of());
        user.setCredentials(List.of(cred));

        try (var resp = realm.users().create(user)) {
            assertEquals(201, resp.getStatus(), "failed to create user " + username);
            String location = resp.getHeaderString("Location");
            return location.substring(location.lastIndexOf('/') + 1);
        }
    }

    @AfterAll
    static void stopAll() {
        if (keycloak != null) {
            keycloak.stop();
        }
        if (stub != null) {
            stub.stop(0);
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void noToken_returns401() throws Exception {
        HttpResponse<String> resp = postTestQuery(null);
        assertEquals(401, resp.statusCode(), "unauthenticated request must be rejected");
    }

    @Test
    void nonAdminToken_returns403() throws Exception {
        String token = passwordGrant(REALM, "test-cli", "plainuser", "plainpw");
        HttpResponse<String> resp = postTestQuery(token);
        assertEquals(403, resp.statusCode(), "authenticated non-admin must be forbidden");
    }

    @Test
    void realmAdminToken_returns200AndMapsClaims() throws Exception {
        String token = passwordGrant(REALM, "test-cli", "adminuser", "adminpw");
        HttpResponse<String> resp = postTestQuery(token);
        assertEquals(200, resp.statusCode(), "realm admin must be allowed");

        JsonNode body = JSON.readTree(resp.body());
        assertEquals("admin", body.path("mappedClaims").path("user_role").asText(),
                "mapper should map the stub's role field into user_role");
    }

    @Test
    void tokenFromDifferentRealm_returns401() throws Exception {
        // A token issued by the master realm must not be accepted for the itest realm.
        String masterToken = passwordGrant("master", "admin-cli",
                keycloak.getAdminUsername(), keycloak.getAdminPassword());
        HttpResponse<String> resp = postTestQuery(masterToken);
        assertEquals(401, resp.statusCode(), "a token from another realm must be rejected");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static HttpResponse<String> postTestQuery(String bearerToken) throws Exception {
        String url = keycloak.getAuthServerUrl() + "/realms/" + REALM + "/rest-claim-mapper/test-query";
        String requestBody = """
                {
                  "url": "http://host.testcontainers.internal:%d/api",
                  "authType": "apikey",
                  "authValue": "",
                  "queryScript": "\\"\\"",
                  "mapping": "role->user_role",
                  "testVars": {}
                }
                """.formatted(stubPort);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
        return HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String passwordGrant(String realm, String clientId, String username, String password)
            throws Exception {
        String url = keycloak.getAuthServerUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
        String form = "grant_type=password"
                + "&client_id=" + enc(clientId)
                + "&username=" + enc(username)
                + "&password=" + enc(password);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "token request failed: " + resp.body());
        return JSON.readTree(resp.body()).path("access_token").asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Locates the shaded provider JAR built during the package phase. */
    private static File findProviderJar() throws IOException {
        File target = new File("target");
        File[] jars = target.listFiles((dir, name) ->
                name.startsWith("kc-rest-claim-mapper-")
                        && name.endsWith(".jar")
                        && !name.startsWith("original-"));
        if (jars == null || jars.length == 0) {
            throw new IOException("Provider JAR not found in target/ — run 'mvn package' first");
        }
        return Objects.requireNonNull(jars)[0];
    }
}
