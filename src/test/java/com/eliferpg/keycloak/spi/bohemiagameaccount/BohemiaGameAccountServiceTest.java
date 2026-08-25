package com.eliferpg.keycloak.spi.bohemiagameaccount;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private void noExistingHolder() {
        when(userProvider.searchForUserByUserAttributeStream(any(), anyString(), anyString()))
            .thenReturn(Stream.empty());
    }

    @Test
    void mintPin_storesBohemiaIdInSingleUseObjectsWithTtl() {
        String pin = service.mintPin(session, "BOHEMIA-1");

        assertNotNull(pin);
        assertEquals(8, pin.length());
        verify(singleUseObjects).put(
            eq(BohemiaGameAccountService.SINGLE_USE_PREFIX + pin),
            eq(BohemiaGameAccountService.PIN_TTL_SECONDS),
            eq(Map.of(BohemiaGameAccountService.BOHEMIA_ID_NOTE, "BOHEMIA-1")));
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
    void consumePin_removesExactlyOnceAndReturnsBohemiaId() {
        when(singleUseObjects.remove(BohemiaGameAccountService.SINGLE_USE_PREFIX + "ABCD2345"))
            .thenReturn(Map.of(BohemiaGameAccountService.BOHEMIA_ID_NOTE, "BOHEMIA-9"));

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
            .thenReturn(Map.of(BohemiaGameAccountService.BOHEMIA_ID_NOTE, "B1"));
        assertEquals("B1", service.consumePin(session, "  ABCD2345  "));
    }

    @Test
    void bind_writesAttributeOnTheAuthenticatedUser() {
        UserModel user = mockUser("user-1");
        noExistingHolder();

        assertEquals(LinkOutcome.LINKED, service.bind(session, realm, user, "BOHEMIA-1"));

        verify(user).setSingleAttribute(BohemiaGameAccountService.BOHEMIA_ID_ATTRIBUTE, "BOHEMIA-1");
    }

    @Test
    void bind_sameBohemiaIdAlreadyOnThisUser_isAlreadyLinkedAndDoesNotRewrite() {
        UserModel user = mockUser("user-1");
        when(user.getFirstAttribute(BohemiaGameAccountService.BOHEMIA_ID_ATTRIBUTE)).thenReturn("BOHEMIA-1");

        assertEquals(LinkOutcome.ALREADY_LINKED, service.bind(session, realm, user, "BOHEMIA-1"));

        verify(user, never()).setSingleAttribute(anyString(), anyString());
    }

    @Test
    void bind_userAlreadyBoundToDifferentBohemiaId_conflictsWithoutWriting() {
        UserModel user = mockUser("user-1");
        when(user.getFirstAttribute(BohemiaGameAccountService.BOHEMIA_ID_ATTRIBUTE)).thenReturn("BOHEMIA-OTHER");

        assertEquals(LinkOutcome.CONFLICT, service.bind(session, realm, user, "BOHEMIA-1"));

        verify(user, never()).setSingleAttribute(anyString(), anyString());
    }

    /**
     * Keycloak does not enforce this for us -- verified against a live 26.0.8 instance,
     * a duplicate binding is accepted at write time and only fails later on read. So this
     * check is the only thing preventing it.
     */
    @Test
    void bind_bohemiaIdHeldByAnotherUser_conflictsWithoutWriting() {
        UserModel user = mockUser("user-1");
        UserModel holder = mockUser("user-2");
        when(userProvider.searchForUserByUserAttributeStream(
            realm, BohemiaGameAccountService.BOHEMIA_ID_ATTRIBUTE, "BOHEMIA-1"))
            .thenReturn(Stream.of(holder));

        assertEquals(LinkOutcome.CONFLICT, service.bind(session, realm, user, "BOHEMIA-1"));

        verify(user, never()).setSingleAttribute(anyString(), anyString());
    }

    @Test
    void bind_storageThrows_returnsErrorRatherThanPropagating() {
        UserModel user = mockUser("user-1");
        noExistingHolder();
        doThrow(new RuntimeException("boom")).when(user).setSingleAttribute(anyString(), anyString());

        assertEquals(LinkOutcome.ERROR, service.bind(session, realm, user, "BOHEMIA-1"));
    }

    @Test
    void findByBohemiaId_returnsNullWhenUnbound() {
        noExistingHolder();
        assertNull(service.findByBohemiaId(session, realm, "BOHEMIA-1"));
    }

    @Test
    void findByBohemiaId_returnsTheBoundUser() {
        UserModel holder = mockUser("user-2");
        when(userProvider.searchForUserByUserAttributeStream(
            realm, BohemiaGameAccountService.BOHEMIA_ID_ATTRIBUTE, "BOHEMIA-1"))
            .thenReturn(Stream.of(holder));

        assertEquals(holder, service.findByBohemiaId(session, realm, "BOHEMIA-1"));
    }
}
