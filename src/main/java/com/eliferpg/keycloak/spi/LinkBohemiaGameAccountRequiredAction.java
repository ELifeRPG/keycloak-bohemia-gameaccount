package com.eliferpg.keycloak.spi;

import com.eliferpg.keycloak.spi.bohemiagameaccount.BohemiaGameAccountService;
import com.eliferpg.keycloak.spi.bohemiagameaccount.LinkOutcome;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.services.resources.LoginActionsService;

import java.util.regex.Pattern;

public class LinkBohemiaGameAccountRequiredAction implements RequiredActionProvider {

    static final String FORM_TEMPLATE = "link-bohemia-gameaccount.ftl";
    static final String PIN_PARAM = "pin";
    private static final Pattern PIN_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final BohemiaGameAccountService service;

    LinkBohemiaGameAccountRequiredAction(BohemiaGameAccountService service) {
        this.service = service;
    }

    /**
     * Makes this an application-initiated action, so the portal can send the player here
     * on demand with {@code kc_action=link-bohemia-gameaccount}.
     */
    @Override
    public InitiatedActionSupport initiatedActionSupport() {
        return InitiatedActionSupport.SUPPORTED;
    }

    /**
     * Deliberately does nothing.
     *
     * <p>This action must never add itself. A player who signs up on the portal to submit
     * a whitelist application has not joined the gameserver yet and therefore has no PIN
     * to enter -- self-adding would strand them at a prompt they cannot satisfy. Linking
     * is always initiated by the player, via {@code kc_action}.
     */
    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        // no-op by design -- see javadoc
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        context.challenge(context.form().createForm(FORM_TEMPLATE));
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
            String bohemiaId = service.consumePin(context.getSession(), pin.trim());
            outcome = bohemiaId == null
                ? LinkOutcome.INVALID_PIN
                : service.bind(context.getSession(), context.getRealm(), context.getUser(), bohemiaId);
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
