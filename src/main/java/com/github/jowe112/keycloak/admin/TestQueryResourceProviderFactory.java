package com.github.jowe112.keycloak.admin;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * Factory that registers the Test Query REST resource under each realm.
 * <p>
 * After deployment, the endpoint is available at:
 * 
 * <pre>
 *   POST /realms/{realm}/rest-claim-mapper/test-query
 * </pre>
 * 
 * Registered via
 * {@code META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory}.
 */
public class TestQueryResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String PROVIDER_ID = "rest-claim-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new TestQueryResourceProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
