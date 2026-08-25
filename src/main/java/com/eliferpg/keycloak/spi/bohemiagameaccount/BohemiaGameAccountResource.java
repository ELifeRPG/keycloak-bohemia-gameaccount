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
 * Server-to-server surface for Bohemia game account linking.
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

    /** Mints a PIN for a Bohemia ID that has no Keycloak user bound to it yet. */
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

    /** Resolves whether a Bohemia ID is already bound, and to which Keycloak user. */
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
        String bohemiaId = readBohemiaId(body);
        if (bohemiaId == null) {
            return errorResponse(Response.Status.BAD_REQUEST, "bohemiaId required");
        }

        UserModel existing = service.findByBohemiaId(session, realm, bohemiaId);
        if (existing != null) {
            return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "already-linked", "keycloakUserId", existing.getId()))
                .build();
        }

        String pin = service.mintPin(session, bohemiaId);
        return Response.ok(Map.of("pin", pin, "expiresInSeconds", BohemiaGameAccountService.PIN_TTL_SECONDS)).build();
    }

    Response statusInternal(RealmModel realm, AuthenticationManager.AuthResult auth, String bohemiaId) {
        if (!hasTrustedScope(auth)) {
            return errorResponse(Response.Status.FORBIDDEN, "trusted scope required");
        }
        if (bohemiaId == null || bohemiaId.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "bohemiaId required");
        }

        UserModel user = service.findByBohemiaId(session, realm, bohemiaId.trim());
        if (user == null) {
            return Response.ok(Map.of("linked", false)).build();
        }
        return Response.ok(Map.of("linked", true, "keycloakUserId", user.getId())).build();
    }

    private String readBohemiaId(JsonNode body) {
        if (body == null) {
            return null;
        }
        String bohemiaId = body.path("bohemiaId").asText(null);
        return bohemiaId == null || bohemiaId.isBlank() ? null : bohemiaId.trim();
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
