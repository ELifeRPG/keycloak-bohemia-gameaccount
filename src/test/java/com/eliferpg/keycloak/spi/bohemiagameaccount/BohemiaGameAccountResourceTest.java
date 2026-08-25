package com.eliferpg.keycloak.spi.bohemiagameaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AuthenticationManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BohemiaGameAccountResourceTest {

    private static final String TRUSTED = "openid " + BohemiaGameAccountResource.TRUSTED_SCOPE;

    private KeycloakSession session;
    private RealmModel realm;
    private BohemiaGameAccountService service;
    private BohemiaGameAccountResource resource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        realm = mock(RealmModel.class);
        service = mock(BohemiaGameAccountService.class);
        resource = new BohemiaGameAccountResource(session, service);
    }

    private AuthenticationManager.AuthResult auth(String scope, boolean serviceAccount) {
        UserModel user = mock(UserModel.class);
        when(user.getServiceAccountClientLink()).thenReturn(serviceAccount ? "some-client" : null);
        AccessToken token = mock(AccessToken.class);
        when(token.getScope()).thenReturn(scope);
        return new AuthenticationManager.AuthResult(user, null, token, null);
    }

    private JsonNode body(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entity(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    // --- POST /pin -----------------------------------------------------------------

    @Test
    void mintPin_trustedCaller_mintsPinForBohemiaId() {
        when(service.mintPin(session, "BOHEMIA-1")).thenReturn("ABCD2345");

        Response response = resource.mintPinInternal(
            realm, auth(TRUSTED, true), body("{\"bohemiaId\":\"BOHEMIA-1\"}"));

        assertEquals(200, response.getStatus());
        assertEquals("ABCD2345", entity(response).get("pin"));
        assertEquals(BohemiaGameAccountService.PIN_TTL_SECONDS, entity(response).get("expiresInSeconds"));
    }

    /** Minting a PIN for an arbitrary bohemiaId must never be reachable by a player token. */
    @Test
    void mintPin_nonTrustedCaller_isForbiddenAndMintsNothing() {
        Response response = resource.mintPinInternal(
            realm, auth("openid profile", false), body("{\"bohemiaId\":\"BOHEMIA-1\"}"));

        assertEquals(403, response.getStatus());
        verify(service, never()).mintPin(any(), anyString());
    }

    @Test
    void mintPin_trustedScopeButNotAServiceAccount_isForbidden() {
        Response response = resource.mintPinInternal(
            realm, auth(TRUSTED, false), body("{\"bohemiaId\":\"BOHEMIA-1\"}"));

        assertEquals(403, response.getStatus());
        verify(service, never()).mintPin(any(), anyString());
    }

    @Test
    void mintPin_missingBohemiaId_isBadRequest() {
        assertEquals(400, resource.mintPinInternal(realm, auth(TRUSTED, true), body("{}")).getStatus());
        assertEquals(400, resource.mintPinInternal(realm, auth(TRUSTED, true), null).getStatus());
        assertEquals(400, resource.mintPinInternal(
            realm, auth(TRUSTED, true), body("{\"bohemiaId\":\"  \"}")).getStatus());
        verify(service, never()).mintPin(any(), anyString());
    }

    @Test
    void mintPin_alreadyBound_conflictsInsteadOfMintingAUselessPin() {
        UserModel holder = mock(UserModel.class);
        when(holder.getId()).thenReturn("user-2");
        when(service.findByBohemiaId(session, realm, "BOHEMIA-1")).thenReturn(holder);

        Response response = resource.mintPinInternal(
            realm, auth(TRUSTED, true), body("{\"bohemiaId\":\"BOHEMIA-1\"}"));

        assertEquals(409, response.getStatus());
        assertEquals("user-2", entity(response).get("keycloakUserId"));
        verify(service, never()).mintPin(any(), anyString());
    }

    // --- GET /status ----------------------------------------------------------------

    @Test
    void status_unbound_reportsNotLinked() {
        when(service.findByBohemiaId(session, realm, "BOHEMIA-1")).thenReturn(null);

        Response response = resource.statusInternal(realm, auth(TRUSTED, true), "BOHEMIA-1");

        assertEquals(200, response.getStatus());
        assertEquals(false, entity(response).get("linked"));
    }

    @Test
    void status_bound_reportsTheKeycloakUserId() {
        UserModel holder = mock(UserModel.class);
        when(holder.getId()).thenReturn("user-2");
        when(service.findByBohemiaId(session, realm, "BOHEMIA-1")).thenReturn(holder);

        Response response = resource.statusInternal(realm, auth(TRUSTED, true), "BOHEMIA-1");

        assertEquals(200, response.getStatus());
        assertEquals(true, entity(response).get("linked"));
        assertEquals("user-2", entity(response).get("keycloakUserId"));
    }

    @Test
    void status_nonTrustedCaller_isForbidden() {
        Response response = resource.statusInternal(realm, auth("openid profile", false), "BOHEMIA-1");

        assertEquals(403, response.getStatus());
        verify(service, never()).findByBohemiaId(any(), any(), anyString());
    }

    @Test
    void status_missingBohemiaId_isBadRequest() {
        assertEquals(400, resource.statusInternal(realm, auth(TRUSTED, true), null).getStatus());
        assertEquals(400, resource.statusInternal(realm, auth(TRUSTED, true), "  ").getStatus());
    }
}
