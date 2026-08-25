package com.eliferpg.keycloak.spi.bohemiagameaccount;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public class BohemiaGameAccountResourceFactory implements RealmResourceProviderFactory {

    public static final String ID = "bohemia-gameaccount";

    private final BohemiaGameAccountService service = new BohemiaGameAccountService();

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new BohemiaGameAccountResource(session, service);
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

    @Override
    public String getId() {
        return ID;
    }
}
