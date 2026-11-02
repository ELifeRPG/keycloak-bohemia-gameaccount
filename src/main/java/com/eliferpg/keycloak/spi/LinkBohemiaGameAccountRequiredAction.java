package com.eliferpg.keycloak.spi;

import com.eliferpg.keycloak.spi.bohemiagameaccount.BohemiaGameAccountService;
import com.eliferpg.keycloak.spi.bohemiagameaccount.LinkOutcome;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.protocol.oidc.endpoints.AuthorizationEndpoint;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.regex.Pattern;

public class LinkBohemiaGameAccountRequiredAction implements RequiredActionProvider {

    static final String FORM_TEMPLATE = "link-bohemia-gameaccount.ftl";
    static final String PIN_PARAM = "pin";

    /**
     * The query parameter the backend puts on the {@code kc_action} URL it hands a player in-game.
     *
     * <p>Keycloak's authorization endpoint copies any request parameter it does not recognise onto
     * the authentication session as a client note, prefixed with
     * {@link AuthorizationEndpoint#LOGIN_SESSION_NOTE_ADDITIONAL_REQ_PARAMS_PREFIX}. Verified
     * against the shipped 26.0.8 {@code keycloak-services} jar rather than assumed; the prefix is
     * referenced symbolically here so a Keycloak upgrade that changes it fails to compile instead
     * of silently dropping the prefill.
     *
     * <p>Keycloak caps this at 5 such parameters, 2000 characters each. This is one short one.
     */
    static final String LINK_PIN_PARAM = "link_pin";

    private static final Pattern PIN_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final BohemiaGameAccountService service;

    LinkBohemiaGameAccountRequiredAction(BohemiaGameAccountService service) {
        this.service = service;
    }

    /**
     * Makes this an application-initiated action, so the mod and the portal can send the player
     * here on demand with {@code kc_action=link-bohemia-gameaccount}.
     */
    @Override
    public InitiatedActionSupport initiatedActionSupport() {
        return InitiatedActionSupport.SUPPORTED;
    }

    /**
     * Deliberately does nothing.
     *
     * <p>This action must never add itself. A player who signs up on the portal to submit a
     * whitelist application may not have joined the gameserver yet and therefore has no PIN to
     * enter -- self-adding would strand them at a prompt they cannot satisfy. Linking is always
     * initiated by the player, via {@code kc_action}.
     */
    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        // no-op by design -- see javadoc
    }

    /**
     * Renders the form, pre-filling the PIN when the link URL carried one.
     *
     * <p>Pre-fill rather than bind-on-arrival: this is a GET the player reached by following a
     * link, so completing the binding here would make an unauthenticated-looking navigation
     * state-changing, and a link forwarded to someone else would bind the sender's game identity
     * to the recipient's account. The player still presses the button; they just do not have to
     * copy an 8-character code off a game screen.
     */
    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        String prefill = prefillPin(context);
        context.challenge(prefill == null
            ? context.form().createForm(FORM_TEMPLATE)
            : context.form().setAttribute(PIN_PARAM, prefill).createForm(FORM_TEMPLATE));
    }

    @Override
    public void processAction(RequiredActionContext context) {
        if (context.getHttpRequest().getDecodedFormParameters().containsKey(LoginActionsService.CANCEL_AIA)) {
            context.success();
            return;
        }

        String pin = context.getHttpRequest().getDecodedFormParameters().getFirst(PIN_PARAM);

        if (pin == null || !PIN_PATTERN.matcher(pin.trim()).matches()) {
            challengeWithError(context, "linkBohemiaGameAccountInvalidInput");
            return;
        }

        LinkOutcome outcome;
        try {
            String gameIdentityId = service.consumePin(context.getSession(), pin.trim());
            outcome = gameIdentityId == null
                ? LinkOutcome.INVALID_PIN
                : service.bind(context.getSession(), context.getRealm(), context.getUser(), gameIdentityId);
        } catch (RuntimeException e) {
            outcome = LinkOutcome.ERROR;
        }

        switch (outcome) {
            case LINKED, ALREADY_LINKED -> context.success();
            case INVALID_PIN -> challengeWithError(context, "linkBohemiaGameAccountInvalidPin");
            case CONFLICT -> challengeWithError(context, "linkBohemiaGameAccountConflict");
            case ERROR -> challengeWithError(context, "linkBohemiaGameAccountError");
        }
    }

    /**
     * Returns the PIN the link URL carried, or {@code null} if there was none or it was malformed.
     * A malformed one is dropped silently: it would be rejected on submit anyway, and leaving the
     * field empty lets the player type the PIN still on their screen.
     */
    private String prefillPin(RequiredActionContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        if (authSession == null) {
            return null;
        }

        String pin = authSession.getClientNote(
            AuthorizationEndpoint.LOGIN_SESSION_NOTE_ADDITIONAL_REQ_PARAMS_PREFIX + LINK_PIN_PARAM);
        if (pin == null) {
            return null;
        }

        String trimmed = pin.trim();
        return PIN_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    /** Re-challenges without the pre-fill: the carried PIN just failed, so offer an empty field. */
    private void challengeWithError(RequiredActionContext context, String messageKey) {
        context.challenge(context.form()
            .setError(messageKey)
            .createForm(FORM_TEMPLATE));
    }

    @Override
    public void close() {
        // no-op
    }
}
