package com.eliferpg.keycloak.spi.bohemiagameaccount;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Binds in-game Game Identities to an already-authenticated Keycloak user.
 *
 * <p><b>On the naming.</b> The class, the provider id and the REST path still say
 * "bohemia game account", and they stay that way: the provider id is referenced verbatim by the
 * realm's required-action registration and by the {@code kc_action} URL the backend hands the
 * player, so renaming it is a realm migration for no behavioural gain. What the identifiers
 * <em>mean</em> has been corrected throughout. Arma Reforger mints a <b>Game Identity per
 * game-platform combination</b>, so one player on PC, Xbox and PS5 has three, and none of them is
 * their Bohemia account. The old {@code bohemiaId} attribute named the web identity while holding
 * a per-platform game identity; it is now {@link #GAME_IDENTITY_IDS_ATTRIBUTE}, and it is
 * <b>multi-valued</b>.
 *
 * <p>The player's Keycloak user is created by ordinary web signup (Discord broker or local
 * registration) or does not exist at all -- a join-first player plays before they have any web
 * identity. Either way the user authenticated here is the only user involved, so linking stays a
 * single attribute write: there is no second user, no merge and no deletion.
 *
 * <p>PINs live in {@link org.keycloak.models.SingleUseObjectProvider}, not on a user. That is
 * deliberate: {@code remove()} returns the previous value, which makes consumption atomic, and it
 * keeps PIN state off the user entity entirely.
 */
public class BohemiaGameAccountService {

    private static final Logger LOGGER = Logger.getLogger(BohemiaGameAccountService.class);

    /**
     * The Keycloak user attribute holding the bound Game Identity IDs. Multi-valued: Keycloak
     * stores each value as its own {@code USER_ATTRIBUTE} row, which is what lets
     * {@link #findByGameIdentityId} match a user by <em>any</em> of their identities.
     *
     * <p>Shared verbatim with the realm's {@code game-identity} client scope, whose protocol
     * mapper projects it into the multi-valued {@code game_identity_ids} claim the backend reads.
     */
    public static final String GAME_IDENTITY_IDS_ATTRIBUTE = "gameIdentityIds";

    static final String SINGLE_USE_PREFIX = "eliferpg.bohemia-gameaccount-pin.";

    /**
     * The key the minted Game Identity ID is stored under inside the single-use object.
     *
     * <p>Pinned by a test on purpose. Mint and consume are the same deployment in normal operation,
     * but they are not the same <em>instant</em>: a PIN minted just before a rolling upgrade is
     * consumed by the new pod. Changing this string silently invalidates every PIN in flight, so it
     * must be a deliberate act, not a drive-by rename.
     */
    static final String GAME_IDENTITY_ID_NOTE = "gameIdentityId";

    /**
     * The clock starts when an unlinked player <em>joins the gameserver</em> -- not at portal
     * signup. A join-first player's identity is permanently recorded on their account the moment
     * they join, so this only has to cover game-join -> following the link URL.
     *
     * <p>30 minutes rather than 10 because a player may join, play for a while, and only then
     * decide to link -- by which point a shorter-lived PIN would be dead. A fresh one arrives on
     * every join, so recovery is a reconnect; the window is sized to rarely need it.
     */
    static final long PIN_TTL_SECONDS = 1800;

    /** Unambiguous alphabet -- no O/0, I/1, L. A player may still read this off a game screen. */
    private static final String PIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int PIN_LENGTH = 8;
    private static final Pattern PIN_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final SecureRandom random = new SecureRandom();

    /**
     * Mints a PIN for a Game Identity ID that no Keycloak user holds yet. Called by a trusted
     * server-to-server caller when a player with no web account joins the gameserver.
     */
    public String mintPin(KeycloakSession session, String gameIdentityId) {
        StringBuilder pin = new StringBuilder(PIN_LENGTH);
        for (int i = 0; i < PIN_LENGTH; i++) {
            pin.append(PIN_ALPHABET.charAt(random.nextInt(PIN_ALPHABET.length())));
        }
        String generated = pin.toString();
        session.singleUseObjects().put(
            SINGLE_USE_PREFIX + generated, PIN_TTL_SECONDS, Map.of(GAME_IDENTITY_ID_NOTE, gameIdentityId));
        return generated;
    }

    /**
     * Atomically consumes a PIN and returns the Game Identity ID it stood for, or {@code null} if
     * the PIN was unknown, malformed, expired, or already consumed. All four collapse to the same
     * answer on purpose -- distinguishing them would leak a probing oracle.
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
        return notes == null ? null : notes.get(GAME_IDENTITY_ID_NOTE);
    }

    /**
     * Appends {@code gameIdentityId} to {@code user}'s identities.
     *
     * <p><b>Appends, never replaces.</b> A player linking their Xbox identity to the account that
     * already holds their PC identity is the ordinary case, not a conflict -- that is the whole
     * point of the per-platform identity model. The only conflict left is an identity that
     * <em>another</em> user already holds.
     *
     * <p>Keycloak does not enforce uniqueness across users for us -- verified against a live 26.0.8
     * instance, the equivalent duplicate is accepted at write time and only blows up later on read.
     * So the uniqueness check here is load-bearing, not defensive.
     */
    public LinkOutcome bind(KeycloakSession session, RealmModel realm, UserModel user, String gameIdentityId) {
        try {
            List<String> existing = currentIdentities(user);
            if (existing.contains(gameIdentityId)) {
                return LinkOutcome.ALREADY_LINKED;
            }

            UserModel holder = findByGameIdentityId(session, realm, gameIdentityId);
            if (holder != null && !holder.getId().equals(user.getId())) {
                LOGGER.warnf("Game Identity %s is already held by user %s; refusing to bind it to %s",
                    gameIdentityId, holder.getId(), user.getId());
                return LinkOutcome.CONFLICT;
            }

            List<String> updated = new ArrayList<>(existing);
            updated.add(gameIdentityId);
            user.setAttribute(GAME_IDENTITY_IDS_ATTRIBUTE, updated);
            LOGGER.infof("Bound Game Identity %s to user %s (now holds %d)",
                gameIdentityId, user.getId(), updated.size());
            return LinkOutcome.LINKED;
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected error binding game account", e);
            return LinkOutcome.ERROR;
        }
    }

    /** Resolves the Keycloak user holding a Game Identity ID, or {@code null} if unheld. */
    public UserModel findByGameIdentityId(KeycloakSession session, RealmModel realm, String gameIdentityId) {
        return session.users()
            .searchForUserByUserAttributeStream(realm, GAME_IDENTITY_IDS_ATTRIBUTE, gameIdentityId)
            .findFirst()
            .orElse(null);
    }

    /**
     * A blank value would make the attribute unreadable as a set and would be projected into the
     * claim, so they are dropped rather than carried forward.
     */
    private List<String> currentIdentities(UserModel user) {
        return user.getAttributeStream(GAME_IDENTITY_IDS_ATTRIBUTE)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }
}
