package com.eliferpg.keycloak.spi.bohemiagameaccount;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.security.SecureRandom;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Binds an in-game Bohemia identity to an already-authenticated Keycloak user.
 *
 * <p>The player's Keycloak user is created by ordinary web signup (Discord broker or
 * local registration) and is the only user involved. The game side never gets a
 * Keycloak user of its own, so linking is a single attribute write on the caller --
 * there is no second user, no merge and no deletion.
 *
 * <p>PINs live in {@link org.keycloak.models.SingleUseObjectProvider}, not on a user.
 * That is deliberate: {@code remove()} returns the previous value, which makes
 * consumption atomic, and it keeps PIN state off the user entity entirely.
 */
public class BohemiaGameAccountService {

    private static final Logger LOGGER = Logger.getLogger(BohemiaGameAccountService.class);

    /** The Keycloak user attribute holding the bound Bohemia ID. */
    public static final String BOHEMIA_ID_ATTRIBUTE = "bohemiaId";

    static final String SINGLE_USE_PREFIX = "eliferpg.bohemia-gameaccount-pin.";
    static final String BOHEMIA_ID_NOTE = "bohemiaId";
    /**
     * The clock starts when an unlinked player <em>joins the gameserver</em> -- not at
     * portal signup. The gap between signing up on the portal and first joining is
     * spanned by the permanent {@link #BOHEMIA_ID_ATTRIBUTE}, never by a PIN, so this
     * only has to cover game-join -> typing it into the browser.
     *
     * <p>30 minutes rather than 10 because a player may join, play for a while, and only
     * then decide to link -- by which point the PIN still on their screen would be dead,
     * and nothing can hand them a fresh one mid-session (it arrives once, in the
     * session-bootstrap response). Recovery is a disconnect/reconnect, so the window is
     * sized to avoid needing it.
     */
    static final long PIN_TTL_SECONDS = 1800;

    /** Unambiguous alphabet -- no O/0, I/1, L. A player reads this off a game screen. */
    private static final String PIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int PIN_LENGTH = 8;
    private static final Pattern PIN_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final SecureRandom random = new SecureRandom();

    /**
     * Mints a PIN for a Bohemia ID that has no Keycloak user yet. Called by a trusted
     * server-to-server caller when an unlinked player joins the gameserver.
     */
    public String mintPin(KeycloakSession session, String bohemiaId) {
        StringBuilder pin = new StringBuilder(PIN_LENGTH);
        for (int i = 0; i < PIN_LENGTH; i++) {
            pin.append(PIN_ALPHABET.charAt(random.nextInt(PIN_ALPHABET.length())));
        }
        String generated = pin.toString();
        session.singleUseObjects().put(
            SINGLE_USE_PREFIX + generated, PIN_TTL_SECONDS, Map.of(BOHEMIA_ID_NOTE, bohemiaId));
        return generated;
    }

    /**
     * Atomically consumes a PIN and returns the Bohemia ID it stood for, or {@code null}
     * if the PIN was unknown, malformed, expired, or already consumed. All four collapse
     * to the same answer on purpose -- distinguishing them would leak a probing oracle.
     */
    public String consumePin(KeycloakSession session, String rawPin) {
        if (rawPin == null) {
            return null;
        }
        String pin = rawPin.trim();
        if (!PIN_PATTERN.matcher(pin).matches()) {
            return null;
        }
        Map<String, String> notes = session.singleUseObjects().remove(SINGLE_USE_PREFIX + pin);
        return notes == null ? null : notes.get(BOHEMIA_ID_NOTE);
    }

    /**
     * Binds {@code bohemiaId} to {@code user}.
     *
     * <p>Keycloak does not enforce uniqueness across users for us -- verified against a
     * live 26.0.8 instance, the equivalent duplicate is accepted at write time and only
     * blows up later on read. So the uniqueness check here is load-bearing, not defensive.
     */
    public LinkOutcome bind(KeycloakSession session, RealmModel realm, UserModel user, String bohemiaId) {
        try {
            String existing = user.getFirstAttribute(BOHEMIA_ID_ATTRIBUTE);
            if (bohemiaId.equals(existing)) {
                return LinkOutcome.ALREADY_LINKED;
            }
            if (existing != null && !existing.isBlank()) {
                LOGGER.warnf("User %s is already bound to bohemiaId %s; refusing to rebind to %s",
                    user.getId(), existing, bohemiaId);
                return LinkOutcome.CONFLICT;
            }

            UserModel holder = findByBohemiaId(session, realm, bohemiaId);
            if (holder != null && !holder.getId().equals(user.getId())) {
                LOGGER.warnf("bohemiaId %s is already bound to user %s; refusing to bind it to %s",
                    bohemiaId, holder.getId(), user.getId());
                return LinkOutcome.CONFLICT;
            }

            user.setSingleAttribute(BOHEMIA_ID_ATTRIBUTE, bohemiaId);
            LOGGER.infof("Bound bohemiaId %s to user %s", bohemiaId, user.getId());
            return LinkOutcome.LINKED;
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected error binding Bohemia game account", e);
            return LinkOutcome.ERROR;
        }
    }

    /** Resolves the Keycloak user bound to a Bohemia ID, or {@code null} if unbound. */
    public UserModel findByBohemiaId(KeycloakSession session, RealmModel realm, String bohemiaId) {
        return session.users()
            .searchForUserByUserAttributeStream(realm, BOHEMIA_ID_ATTRIBUTE, bohemiaId)
            .findFirst()
            .orElse(null);
    }
}
