package com.eliferpg.keycloak.spi;

import com.eliferpg.keycloak.spi.bohemiagameaccount.BohemiaGameAccountService;
import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class LinkBohemiaGameAccountRequiredActionFactory implements RequiredActionFactory {

    /**
     * Referenced verbatim by eliferpg-realm.json when wiring this required action
     * into the authentication flow — do not change without coordinating that change.
     */
    public static final String PROVIDER_ID = "link-bohemia-gameaccount";

    private final BohemiaGameAccountService service = new BohemiaGameAccountService();

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new LinkBohemiaGameAccountRequiredAction(service);
    }

    @Override
    public void init(Config.Scope config) {
        // no-op -- no external config needed anymore, this SPI makes no outbound HTTP calls
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
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayText() {
        return "Link Bohemia Game Account";
    }
}
