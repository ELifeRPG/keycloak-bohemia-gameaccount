package com.eliferpg.keycloak.spi;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import com.eliferpg.keycloak.spi.bohemiagameaccount.BohemiaGameAccountService;
import com.eliferpg.keycloak.spi.bohemiagameaccount.LinkOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.endpoints.AuthorizationEndpoint;
import org.keycloak.sessions.AuthenticationSessionModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LinkBohemiaGameAccountRequiredActionTest {

    private BohemiaGameAccountService service;
    private RequiredActionContext context;
    private UserModel user;
    private KeycloakSession session;
    private RealmModel realm;
    private HttpRequest httpRequest;
    private LoginFormsProvider formsProvider;
    private AuthenticationSessionModel authSession;
    private LinkBohemiaGameAccountRequiredAction action;

    @BeforeEach
    void setUp() {
        service = mock(BohemiaGameAccountService.class);
        context = mock(RequiredActionContext.class);
        user = mock(UserModel.class);
        session = mock(KeycloakSession.class);
        realm = mock(RealmModel.class);
        httpRequest = mock(HttpRequest.class);
        formsProvider = mock(LoginFormsProvider.class, org.mockito.Answers.RETURNS_SELF);
        authSession = mock(AuthenticationSessionModel.class);

        when(context.getUser()).thenReturn(user);
        when(context.getSession()).thenReturn(session);
        when(context.getRealm()).thenReturn(realm);
        when(context.getHttpRequest()).thenReturn(httpRequest);
        when(context.form()).thenReturn(formsProvider);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(formsProvider.createForm(anyString())).thenReturn(mock(Response.class));

        action = new LinkBohemiaGameAccountRequiredAction(service);
    }

    private void submitForm(String key, String value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        if (value != null) {
            params.putSingle(key, value);
        }
        when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    }

    private void submitPin(String pin) {
        submitForm("pin", pin);
    }

    /** Stands in for the link_pin query parameter Keycloak copied onto the auth session. */
    private void linkUrlCarried(String pin) {
        when(authSession.getClientNote(
            AuthorizationEndpoint.LOGIN_SESSION_NOTE_ADDITIONAL_REQ_PARAMS_PREFIX + "link_pin"))
            .thenReturn(pin);
    }

    /** Without this, kc_action=link-bohemia-gameaccount cannot reach the form at all. */
    @Test
    void isApplicationInitiated() {
        assertEquals(InitiatedActionSupport.SUPPORTED, action.initiatedActionSupport());
    }

    /**
     * Must never self-add: a portal-first player who has not joined the gameserver has no
     * PIN, and a self-added action would strand them at a prompt they cannot satisfy.
     */
    @Test
    void evaluateTriggers_neverAddsItself() {
        action.evaluateTriggers(context);
        verify(user, never()).addRequiredAction(anyString());
        verifyNoInteractions(service);
    }

    @Test
    void requiredActionChallenge_rendersForm() {
        action.requiredActionChallenge(context);
        verify(formsProvider).createForm("link-bohemia-gameaccount.ftl");
        verify(context).challenge(any());
    }

    /**
     * The point of the whole kc_action link URL: the player follows it and the PIN is already in
     * the field, so they never copy an 8-character code off a game screen.
     */
    @Test
    void requiredActionChallenge_linkUrlCarriedAPin_prefillsTheField() {
        linkUrlCarried("ABCD2345");

        action.requiredActionChallenge(context);

        verify(formsProvider).setAttribute("pin", "ABCD2345");
        verify(context).challenge(any());
    }

    /**
     * Pre-fill only -- never bind on arrival. This is a GET reached by following a link, so
     * binding here would let a forwarded link attach the sender's game identity to whoever opens
     * it.
     */
    @Test
    void requiredActionChallenge_neverConsumesOrBinds() {
        linkUrlCarried("ABCD2345");

        action.requiredActionChallenge(context);

        verifyNoInteractions(service);
    }

    @Test
    void requiredActionChallenge_noPinOnTheLinkUrl_leavesTheFieldEmpty() {
        linkUrlCarried(null);

        action.requiredActionChallenge(context);

        verify(formsProvider, never()).setAttribute(eq("pin"), any());
    }

    @Test
    void requiredActionChallenge_malformedPinOnTheLinkUrl_leavesTheFieldEmpty() {
        linkUrlCarried("not a pin");

        action.requiredActionChallenge(context);

        verify(formsProvider, never()).setAttribute(eq("pin"), any());
    }

    /** Portal "Enter my PIN" button: no auth session note at all, just the bare form. */
    @Test
    void requiredActionChallenge_noAuthenticationSession_stillRendersTheForm() {
        when(context.getAuthenticationSession()).thenReturn(null);

        action.requiredActionChallenge(context);

        verify(formsProvider).createForm("link-bohemia-gameaccount.ftl");
        verify(context).challenge(any());
    }

    /** The carried PIN just failed, so re-offer an empty field rather than the same bad value. */
    @Test
    void processAction_rechallengeAfterAFailure_doesNotPrefill() {
        linkUrlCarried("ABCD2345");
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn(null);

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountInvalidPin");
        verify(formsProvider, never()).setAttribute(eq("pin"), any());
    }

    @Test
    void processAction_cancelAia_succeedsWithoutConsumingAnything() {
        submitForm("cancel-aia", "true");

        action.processAction(context);

        verify(context).success();
        verifyNoInteractions(service);
    }

    @Test
    void processAction_validPin_bindsAndSucceeds() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn("GI-PC");
        when(service.bind(session, realm, user, "GI-PC")).thenReturn(LinkOutcome.LINKED);

        action.processAction(context);

        verify(context).success();
        verify(context, never()).challenge(any());
    }

    @Test
    void processAction_alreadyLinked_succeeds() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn("GI-PC");
        when(service.bind(session, realm, user, "GI-PC")).thenReturn(LinkOutcome.ALREADY_LINKED);

        action.processAction(context);

        verify(context).success();
    }

    @Test
    void processAction_unknownPin_rechallengesAndNeverBinds() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn(null);

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountInvalidPin");
        verify(context).challenge(any());
        verify(context, never()).success();
        verify(service, never()).bind(any(), any(), any(), anyString());
    }

    @Test
    void processAction_conflict_rechallengesWithConflictMessage() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn("GI-PC");
        when(service.bind(session, realm, user, "GI-PC")).thenReturn(LinkOutcome.CONFLICT);

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountConflict");
        verify(context, never()).success();
    }

    @Test
    void processAction_serviceError_rechallengesWithGenericMessage() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn("GI-PC");
        when(service.bind(session, realm, user, "GI-PC")).thenReturn(LinkOutcome.ERROR);

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountError");
        verify(context, never()).success();
    }

    @Test
    void processAction_serviceThrows_isSwallowedIntoARechallenge() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenThrow(new RuntimeException("boom"));

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountError");
        verify(context, never()).success();
    }

    @Test
    void processAction_missingPin_rejectsWithoutTouchingTheService() {
        submitPin(null);

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountInvalidInput");
        verifyNoInteractions(service);
    }

    @Test
    void processAction_malformedPin_rejectsWithoutTouchingTheService() {
        submitPin("has space");

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountInvalidInput");
        verifyNoInteractions(service);
    }

    @Test
    void providerIdIsStable() {
        assertEquals("link-bohemia-gameaccount", new LinkBohemiaGameAccountRequiredActionFactory().getId());
    }
}
