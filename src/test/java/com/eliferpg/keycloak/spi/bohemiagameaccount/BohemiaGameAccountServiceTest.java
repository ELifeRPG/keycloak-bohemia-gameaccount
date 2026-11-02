package com.eliferpg.keycloak.spi.bohemiagameaccount;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BohemiaGameAccountServiceTest {

    private KeycloakSession session;
    private SingleUseObjectProvider singleUseObjects;
    private UserProvider userProvider;
    private RealmModel realm;
    private BohemiaGameAccountService service;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        singleUseObjects = mock(SingleUseObjectProvider.class);
        userProvider = mock(UserProvider.class);
        realm = mock(RealmModel.class);
        when(session.singleUseObjects()).thenReturn(singleUseObjects);
        when(session.users()).thenReturn(userProvider);
        service = new BohemiaGameAccountService();
    }

    private UserModel mockUser(String id) {
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    /** Stands in for the user's current multi-valued attribute. */
    private void holds(UserModel user, String... gameIdentityIds) {
        when(user.getAttributeStream(BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE))
            .thenAnswer(invocation -> Stream.of(gameIdentityIds));
    }

    private void noExistingHolder() {
        when(userProvider.searchForUserByUserAttributeStream(any(), anyString(), anyString()))
            .thenReturn(Stream.empty());
    }

    @Test
    void mintPin_storesTheGameIdentityIdInSingleUseObjectsWithTtl() {
        String pin = service.mintPin(session, "GI-PC");

        assertNotNull(pin);
        assertEquals(8, pin.length());
        verify(singleUseObjects).put(
            eq(BohemiaGameAccountService.SINGLE_USE_PREFIX + pin),
            eq(BohemiaGameAccountService.PIN_TTL_SECONDS),
            eq(Map.of(BohemiaGameAccountService.GAME_IDENTITY_ID_NOTE, "GI-PC")));
    }

    @Test
    void mintPin_usesUnambiguousAlphabetOnly() {
        for (int i = 0; i < 200; i++) {
            String pin = service.mintPin(session, "B");
            assertTrue(pin.matches("^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$"),
                "unexpected characters in PIN: " + pin);
        }
    }

    /**
     * The consume-once guarantee. remove() returning the previous value is what makes
     * two concurrent redemptions of the same PIN safe -- exactly one gets a non-null map.
     */
    @Test
    void consumePin_removesExactlyOnceAndReturnsTheGameIdentityId() {
        when(singleUseObjects.remove(BohemiaGameAccountService.SINGLE_USE_PREFIX + "ABCD2345"))
            .thenReturn(Map.of(BohemiaGameAccountService.GAME_IDENTITY_ID_NOTE, "BOHEMIA-9"));

        assertEquals("BOHEMIA-9", service.consumePin(session, "ABCD2345"));

        verify(singleUseObjects, times(1)).remove(BohemiaGameAccountService.SINGLE_USE_PREFIX + "ABCD2345");
        verify(singleUseObjects, never()).get(anyString());
    }

    @Test
    void consumePin_unknownOrAlreadyConsumed_returnsNull() {
        when(singleUseObjects.remove(anyString())).thenReturn(null);
        assertNull(service.consumePin(session, "ABCD2345"));
    }

    @Test
    void consumePin_nullOrMalformed_returnsNullWithoutTouchingStorage() {
        assertNull(service.consumePin(session, null));
        assertNull(service.consumePin(session, "   "));
        assertNull(service.consumePin(session, "has space"));
        assertNull(service.consumePin(session, "way-too-long-".repeat(10)));
        verify(singleUseObjects, never()).remove(anyString());
    }

    @Test
    void consumePin_trimsSurroundingWhitespace() {
        when(singleUseObjects.remove(BohemiaGameAccountService.SINGLE_USE_PREFIX + "ABCD2345"))
            .thenReturn(Map.of(BohemiaGameAccountService.GAME_IDENTITY_ID_NOTE, "B1"));
        assertEquals("B1", service.consumePin(session, "  ABCD2345  "));
    }

    @Test
    void bind_firstIdentity_writesItAsASingleValuedList() {
        UserModel user = mockUser("user-1");
        holds(user);
        noExistingHolder();

        assertEquals(LinkOutcome.LINKED, service.bind(session, realm, user, "GI-PC"));

        verify(user).setAttribute(BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE, List.of("GI-PC"));
    }

    /**
     * The central behaviour change. A player who links their Xbox identity to the account already
     * holding their PC identity must end up with both -- Reforger mints one Game Identity per
     * game-platform combination, so this is the ordinary case, not a conflict.
     */
    @Test
    void bind_secondIdentityOnTheSameUser_appendsRatherThanReplacing() {
        UserModel user = mockUser("user-1");
        holds(user, "GI-PC");
        noExistingHolder();

        assertEquals(LinkOutcome.LINKED, service.bind(session, realm, user, "GI-XBOX"));

        verify(user).setAttribute(
            BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE, List.of("GI-PC", "GI-XBOX"));
    }

    @Test
    void bind_thirdIdentity_keepsAllPreviousOnes() {
        UserModel user = mockUser("user-1");
        holds(user, "GI-PC", "GI-XBOX");
        noExistingHolder();

        assertEquals(LinkOutcome.LINKED, service.bind(session, realm, user, "GI-PSN"));

        verify(user).setAttribute(
            BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE, List.of("GI-PC", "GI-XBOX", "GI-PSN"));
    }

    @Test
    void bind_identityAlreadyOnThisUser_isAlreadyLinkedAndDoesNotRewrite() {
        UserModel user = mockUser("user-1");
        holds(user, "GI-PC", "GI-XBOX");

        assertEquals(LinkOutcome.ALREADY_LINKED, service.bind(session, realm, user, "GI-XBOX"));

        verify(user, never()).setAttribute(anyString(), anyList());
    }

    /** A blank value would be projected into the claim, so it must not survive an append. */
    @Test
    void bind_dropsBlankValuesAlreadyOnTheAttribute() {
        UserModel user = mockUser("user-1");
        holds(user, "GI-PC", "   ");
        noExistingHolder();

        assertEquals(LinkOutcome.LINKED, service.bind(session, realm, user, "GI-XBOX"));

        verify(user).setAttribute(
            BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE, List.of("GI-PC", "GI-XBOX"));
    }

    /**
     * Keycloak does not enforce this for us -- verified against a live 26.0.8 instance,
     * a duplicate binding is accepted at write time and only fails later on read. So this
     * check is the only thing preventing one game identity reaching two accounts.
     */
    @Test
    void bind_identityHeldByAnotherUser_conflictsWithoutWriting() {
        UserModel user = mockUser("user-1");
        UserModel holder = mockUser("user-2");
        holds(user);
        when(userProvider.searchForUserByUserAttributeStream(
            realm, BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE, "GI-PC"))
            .thenReturn(Stream.of(holder));

        assertEquals(LinkOutcome.CONFLICT, service.bind(session, realm, user, "GI-PC"));

        verify(user, never()).setAttribute(anyString(), anyList());
    }

    @Test
    void bind_storageThrows_returnsErrorRatherThanPropagating() {
        UserModel user = mockUser("user-1");
        holds(user);
        noExistingHolder();
        doThrow(new RuntimeException("boom")).when(user).setAttribute(anyString(), anyList());

        assertEquals(LinkOutcome.ERROR, service.bind(session, realm, user, "GI-PC"));
    }

    @Test
    void findByGameIdentityId_returnsNullWhenUnheld() {
        noExistingHolder();
        assertNull(service.findByGameIdentityId(session, realm, "GI-PC"));
    }

    @Test
    void findByGameIdentityId_returnsTheHoldingUser() {
        UserModel holder = mockUser("user-2");
        when(userProvider.searchForUserByUserAttributeStream(
            realm, BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE, "GI-PC"))
            .thenReturn(Stream.of(holder));

        assertEquals(holder, service.findByGameIdentityId(session, realm, "GI-PC"));
    }

    /**
     * The attribute name is shared verbatim with the realm's {@code game-identity} client scope,
     * whose mapper projects it into the {@code game_identity_ids} claim the backend reads. Renaming
     * it here without the realm silently empties that claim for every player, so pin it.
     */
    @Test
    void attributeNameIsTheOneTheRealmMapperReads() {
        assertEquals("gameIdentityIds", BohemiaGameAccountService.GAME_IDENTITY_IDS_ATTRIBUTE);
    }

    /**
     * The upgrade canary. Mint and consume are normally the same deployment but never the same
     * instant: a PIN minted just before a rolling upgrade is consumed by the new pod. Changing this
     * key invalidates every PIN in flight, so it has to be a deliberate act.
     */
    @Test
    void singleUseNoteKeyIsStableAcrossUpgrades() {
        assertEquals("gameIdentityId", BohemiaGameAccountService.GAME_IDENTITY_ID_NOTE);

        service.mintPin(session, "GI-PC");

        verify(singleUseObjects).put(anyString(), anyLong(), eq(Map.of("gameIdentityId", "GI-PC")));
    }
}
