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

        when(context.getUser()).thenReturn(user);
        when(context.getSession()).thenReturn(session);
        when(context.getRealm()).thenReturn(realm);
        when(context.getHttpRequest()).thenReturn(httpRequest);
        when(context.form()).thenReturn(formsProvider);
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
        when(service.consumePin(session, "ABCD2345")).thenReturn("BOHEMIA-1");
        when(service.bind(session, realm, user, "BOHEMIA-1")).thenReturn(LinkOutcome.LINKED);

        action.processAction(context);

        verify(context).success();
        verify(context, never()).challenge(any());
    }

    @Test
    void processAction_alreadyLinked_succeeds() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn("BOHEMIA-1");
        when(service.bind(session, realm, user, "BOHEMIA-1")).thenReturn(LinkOutcome.ALREADY_LINKED);

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
        when(service.consumePin(session, "ABCD2345")).thenReturn("BOHEMIA-1");
        when(service.bind(session, realm, user, "BOHEMIA-1")).thenReturn(LinkOutcome.CONFLICT);

        action.processAction(context);

        verify(formsProvider).setError("linkBohemiaGameAccountConflict");
        verify(context, never()).success();
    }

    @Test
    void processAction_serviceError_rechallengesWithGenericMessage() {
        submitPin("ABCD2345");
        when(service.consumePin(session, "ABCD2345")).thenReturn("BOHEMIA-1");
        when(service.bind(session, realm, user, "BOHEMIA-1")).thenReturn(LinkOutcome.ERROR);

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
