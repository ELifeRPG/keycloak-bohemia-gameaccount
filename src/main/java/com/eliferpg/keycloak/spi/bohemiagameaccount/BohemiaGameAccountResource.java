package com.eliferpg.keycloak.spi.bohemiagameaccount;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resource.RealmResourceProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Server-to-server surface for game account linking.
 *
 * <p>Both endpoints are trusted-caller only. There is deliberately no self-service
 * redemption endpoint: a player redeems a PIN by typing it into Keycloak's own form
 * (the {@code link-bohemia-gameaccount} required action), never by calling an API.
 */
public class BohemiaGameAccountResource implements RealmResourceProvider {

    static final String TRUSTED_SCOPE = "accounts:bohemia-gameaccount:manage";

    private final KeycloakSession session;
    private final BohemiaGameAccountService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BohemiaGameAccountResource(KeycloakSession session, BohemiaGameAccountService service) {
        this.session = session;
        this.service = service;
    }

    @Override
    public Object getResource() {
        return this;
    }

    /** Mints a PIN for a Game Identity ID that no Keycloak user holds yet. */
    @POST
    @Path("pin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mintPin(InputStream body) {
        AuthenticationManager.AuthResult auth = authenticate();
        if (auth == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "unauthenticated");
        }
        return mintPinInternal(session.getContext().getRealm(), auth, readJson(body));
    }

    /** Resolves whether a Game Identity ID is already held, and by which Keycloak user. */
    @GET
    @Path("status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@QueryParam("bohemiaId") String bohemiaId) {
        AuthenticationManager.AuthResult auth = authenticate();
        if (auth == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "unauthenticated");
        }
        return statusInternal(session.getContext().getRealm(), auth, bohemiaId);
    }

    Response mintPinInternal(RealmModel realm, AuthenticationManager.AuthResult auth, JsonNode body) {
        if (!hasTrustedScope(auth)) {
            return errorResponse(Response.Status.FORBIDDEN, "trusted scope required");
        }
        String gameIdentityId = readGameIdentityId(body);
        if (gameIdentityId == null) {
            return errorResponse(Response.Status.BAD_REQUEST, "bohemiaId required");
        }

        UserModel existing = service.findByGameIdentityId(session, realm, gameIdentityId);
        if (existing != null) {
            return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "already-linked", "keycloakUserId", existing.getId()))
                .build();
        }

        String pin = service.mintPin(session, gameIdentityId);
        return Response.ok(Map.of("pin", pin, "expiresInSeconds", BohemiaGameAccountService.PIN_TTL_SECONDS)).build();
    }

    Response statusInternal(RealmModel realm, AuthenticationManager.AuthResult auth, String bohemiaId) {
        if (!hasTrustedScope(auth)) {
            return errorResponse(Response.Status.FORBIDDEN, "trusted scope required");
        }
        if (bohemiaId == null || bohemiaId.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "bohemiaId required");
        }

        UserModel user = service.findByGameIdentityId(session, realm, bohemiaId.trim());
        if (user == null) {
            return Response.ok(Map.of("linked", false)).build();
        }
        return Response.ok(Map.of("linked", true, "keycloakUserId", user.getId())).build();
    }

    /**
     * The request field is still {@code bohemiaId} although the value is a Game Identity ID.
     *
     * <p>This is a trusted server-to-server wire between the backend and this provider -- it is not
     * the public API, where the rename to {@code gameIdentityId} has already happened. Renaming it
     * here would break the deployed backend the moment this provider ships, for no player-visible
     * gain, so it is changed when the backend changes it, not before.
     */
    private String readGameIdentityId(JsonNode body) {
        if (body == null) {
            return null;
        }
        String gameIdentityId = body.path("bohemiaId").asText(null);
        return gameIdentityId == null || gameIdentityId.isBlank() ? null : gameIdentityId.trim();
    }

    private AuthenticationManager.AuthResult authenticate() {
        return new AppAuthManager.BearerTokenAuthenticator(session)
            .setRealm(session.getContext().getRealm())
            .setConnection(session.getContext().getConnection())
            .setHeaders(session.getContext().getRequestHeaders())
            .setUriInfo(session.getContext().getUri())
            .authenticate();
    }

    private boolean hasTrustedScope(AuthenticationManager.AuthResult auth) {
        if (auth.getUser().getServiceAccountClientLink() == null) {
            return false;
        }
        String scope = auth.getToken().getScope();
        if (scope == null) {
            return false;
        }
        for (String s : scope.split(" ")) {
            if (TRUSTED_SCOPE.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode readJson(InputStream body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private Response errorResponse(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

    @Override
    public void close() {
        // no-op
    }
}
