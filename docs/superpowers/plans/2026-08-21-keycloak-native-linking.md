# Keycloak-Native Account Linking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move PIN generation, storage, and account-linking/merge mechanics out of eliferpg's Central API and directly into this Keycloak SPI, so linking works via Keycloak Admin API operations (attach federated identity, delete a redundant user) instead of an HTTP round-trip to an external service. Central API's role shrinks to (later, not part of this plan) a thin proxy for the one caller that can't reach Keycloak directly: the Bridge.

**Architecture:** A new internal service, `LinkedIdentityService`, holds the only copy of the merge logic and is called from two entry points inside this same JAR: the existing `LinkGameAccountRequiredAction` (web-initiated, during Discord first-broker-login) and a new `LinkGameAccountResource`/`Factory` pair (a `RealmResourceProvider` — a different Keycloak SPI extension point than `RequiredActionProvider` — exposing REST endpoints for self-service code generation/redemption and for a future Bridge-facing proxy). Pending codes are stored as attributes directly on whichever Keycloak user generates them; redemption resolves the *other* side symmetrically and always keeps the gameserver-provisioned (`bohemia_*`) identity as the survivor. `CentralApiClient` and its HTTP-based redeem contract are retired entirely — this repo no longer makes any outbound network calls.

**Tech Stack:** Same as the existing SPI (Java 21, Maven, Keycloak SPI 26.0.8, JUnit 5 + Mockito). No new external dependencies — Jackson (already transitive) is reused for the REST endpoints' JSON bodies.

**Spec:** `/home/kevin/.claude/plans/how-will-it-work-polished-wozniak.md` (the approved architecture-direction plan this implements) and the existing `IMPLEMENTATION_PLAN.md`/`README.md` in this repo for everything about the *existing* SPI that stays unchanged (provider id, FTL/theme conventions, JDK/Keycloak version, devcontainer). This plan supersedes IMPLEMENTATION_PLAN.md's non-goal against implementing PIN generation/storage/merge logic — that reversal is deliberate, see the architecture-direction plan's Context section for why.

## Global Constraints

- **Attribute names** (on `UserModel`, via `setSingleAttribute`/`getFirstAttribute`/`removeAttribute`):
  `pendingLinkCode` (the code string), `pendingLinkCodeExpiresAt` (epoch seconds, as a string), `gameAccountLinked` (unchanged from the existing SPI — still `"true"` once linked).
- **Code format:** 8 characters, alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (excludes `0`/`O`, `1`/`I`/`L` to avoid transcription errors when read off a screen and typed elsewhere), generated via `java.security.SecureRandom`. Validated on input with the *existing* pattern already used by the required action: `^[A-Za-z0-9-]{1,32}$` (kept broad on the input side so a human retyping a code with e.g. a stray hyphen doesn't get an extra rejection layer beyond what's actually needed).
- **Code TTL:** 600 seconds (10 minutes) — matches the original spec's "~10 min".
- **Gameserver identity detection:** a user is the survivor-eligible "game identity" iff `user.getUsername()` starts with `bohemia_` — matches the existing convention (`KeycloakUsername.For(bohemiaId)` in the sibling `backend` repo's `KeycloakUserProvisioner.cs`, not touched by this plan).
- **Survivor policy:** unchanged from the original spec — the `bohemia_*` (gameserver) identity always survives; the other side's federated identities get moved onto it, then the other side's user row is deleted. If *neither* side is a `bohemia_*` user, that's an invalid state — return `ERROR`, log a warning, don't guess.
- **Trusted-caller scope name:** `accounts:links:redeem-trusted` (reused verbatim from the original design — still the scope that will need to be granted to a `keycloak-spi`-equivalent trusted client on the realm-config side, later, not part of this plan). A caller's Bearer token carries this scope in its `AccessToken.getScope()` (space-separated string) iff their client was granted it.
- **Critical safety fix, don't skip this:** when the *currently-authenticating* user in a live Keycloak login flow (required-action path) turns out to be the "loser" side of a merge, it must **never** be deleted directly — that would delete a user row out from under the in-progress `AuthenticationSessionModel`, corrupting the session's own bookkeeping in ways that surface as a broken/500'd login. Verified fix (via `javap` against the real 26.0.8 jar): `AuthenticationSessionModel.setAuthenticatedUser(UserModel)` exists and is exactly Keycloak's own mechanism for reassigning an in-progress session to a different user (the same pattern Keycloak's own built-in "link to existing broker user" authenticators use). `LinkedIdentityService.redeem(...)` takes an optional (nullable) `AuthenticationSessionModel` parameter; when non-null and the loser matches that session's current authenticated user, call `setAuthenticatedUser(survivor)` *before* deleting the loser. The REST-resource entry point (no live browser session) always passes `null`.
- **This SPI makes zero outbound HTTP calls after this plan lands.** `CentralApiClient`/`CentralApiClientException`/the old `RedeemOutcome` enum and all four `ELIFERPG_*` env vars are retired entirely — nothing to configure at Keycloak startup for this feature anymore.
- **Verified Keycloak 26.0.8 API surface this plan depends on** (all confirmed via `javap` against the real jars during planning, not assumed):
  - `org.keycloak.services.resource.RealmResourceProvider`/`RealmResourceProviderFactory` (in `keycloak-server-spi-private`) — `Factory.getId()` becomes the URL path segment (`/realms/{realm}/{id}/...`); `Provider.getResource()` returns a plain object whose `jakarta.ws.rs.*`-annotated public methods become the actual endpoints (confirmed via the real `DeviceEndpointFactory`/`DeviceEndpoint` built-in example, `getId()` returns `"device"`, methods carry `@Path`/`@GET`/`@POST`/`@Consumes`/`@Produces` directly).
  - `org.keycloak.services.managers.AppAuthManager.BearerTokenAuthenticator` (in `keycloak-services`, already a `provided` dependency) — fluent builder (`setSession`/`setRealm`/`setConnection`/`setHeaders`/`setUriInfo`) with `.authenticate()` returning `AuthenticationManager.AuthResult` (`getUser()`, `getToken()` → `AccessToken.getScope()`, `getClient()`).
  - `UserProvider` (via `session.users()`): `searchForUserByUserAttributeStream(RealmModel, String, String)`, `addFederatedIdentity`/`removeFederatedIdentity`/`getFederatedIdentitiesStream`/`getFederatedIdentity`, `removeUser(RealmModel, UserModel)`, `getUserByUsername(RealmModel, String)`.
  - `FederatedIdentityModel(String identityProvider, String userId, String userName)` — 3-arg constructor.
  - `KeycloakContext.getRealm()`/`getConnection()`/`getRequestHeaders()`/`getUri()` (a `KeycloakUriInfo`, confirmed `implements jakarta.ws.rs.core.UriInfo`) — everything `BearerTokenAuthenticator` needs.
- Every task's steps implicitly include these constraints.

---

### Task 1: `LinkedIdentityService` — the shared merge/generate logic

**Files:**
- Create: `src/main/java/net/eliferpg/keycloak/spi/link/LinkOutcome.java`
- Create: `src/main/java/net/eliferpg/keycloak/spi/link/LinkedIdentityService.java`
- Test: `src/test/java/net/eliferpg/keycloak/spi/link/LinkedIdentityServiceTest.java`

**Interfaces:**
- Produces (Tasks 2 and 3 depend on these exact names/signatures):
  - `enum LinkOutcome { LINKED, ALREADY_LINKED, INVALID_CODE, EXPIRED_CODE, ERROR }`
  - `class LinkedIdentityService` with:
    - `public String generateCode(RealmModel realm, UserModel caller)` — stores the code + expiry as attributes on `caller`, returns the generated code.
    - `public LinkOutcome redeem(KeycloakSession session, RealmModel realm, UserModel targetUser, String rawCode, AuthenticationSessionModel authSessionOrNull)` — never throws; every failure path returns a `LinkOutcome` value (never lets an exception escape — catch broadly around the merge operation itself).

- [ ] **Step 1: Write `LinkOutcome.java`**

```java
package net.eliferpg.keycloak.spi.link;

public enum LinkOutcome {
    LINKED,
    ALREADY_LINKED,
    INVALID_CODE,
    EXPIRED_CODE,
    ERROR
}
```

- [ ] **Step 2: Write the failing tests first — `LinkedIdentityServiceTest.java`**

Uses Mockito to mock `KeycloakSession`, `RealmModel`, `UserProvider`, `UserModel`, and `AuthenticationSessionModel` — no real Keycloak needed.

```java
package net.eliferpg.keycloak.spi.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkedIdentityServiceTest {

    private KeycloakSession session;
    private UserProvider userProvider;
    private RealmModel realm;
    private LinkedIdentityService service;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        userProvider = mock(UserProvider.class);
        realm = mock(RealmModel.class);
        when(session.users()).thenReturn(userProvider);
        service = new LinkedIdentityService();
    }

    private UserModel mockUser(String id, String username) {
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(username);
        return user;
    }

    @Test
    void generateCode_setsAttributesOnCaller() {
        UserModel caller = mockUser("u1", "bohemia_42");

        String code = service.generateCode(realm, caller);

        assertEquals(8, code.length());
        verify(caller).setSingleAttribute(eq("pendingLinkCode"), eq(code));
        verify(caller).setSingleAttribute(eq("pendingLinkCodeExpiresAt"), anyString());
    }

    @Test
    void redeem_gameTargetPortalInitiator_movesFederatedIdentityAndDeletesLoser() {
        UserModel target = mockUser("game-1", "bohemia_42"); // survivor
        UserModel initiator = mockUser("portal-1", "discord-temp-user"); // loser

        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "ABCD2345"))
            .thenReturn(Stream.of(initiator));
        when(initiator.getFirstAttribute("pendingLinkCodeExpiresAt"))
            .thenReturn(String.valueOf(Instant.now().plusSeconds(300).getEpochSecond()));
        when(initiator.getFirstAttribute("pendingLinkCode")).thenReturn("ABCD2345");

        FederatedIdentityModel discordIdentity = new FederatedIdentityModel("discord", "ext-1", "someone#1234");
        when(userProvider.getFederatedIdentitiesStream(realm, initiator)).thenReturn(Stream.of(discordIdentity));
        when(userProvider.getFederatedIdentity(realm, target, "discord")).thenReturn(null);
        when(userProvider.removeUser(realm, initiator)).thenReturn(true);

        LinkOutcome outcome = service.redeem(session, realm, target, "ABCD2345", null);

        assertEquals(LinkOutcome.LINKED, outcome);
        verify(userProvider).addFederatedIdentity(eq(realm), eq(target), argThat(fim ->
            fim.getIdentityProvider().equals("discord") && fim.getUserId().equals("ext-1")));
        verify(userProvider).removeUser(realm, initiator);
        verify(target).setSingleAttribute("gameAccountLinked", "true");
        verify(initiator).removeAttribute("pendingLinkCode");
        verify(initiator).removeAttribute("pendingLinkCodeExpiresAt");
    }

    @Test
    void redeem_portalTargetGameInitiator_survivorIsAlwaysTheGameIdentity() {
        UserModel target = mockUser("portal-1", "discord-temp-user"); // loser this time
        UserModel initiator = mockUser("game-1", "bohemia_42"); // survivor, code was Bridge-generated

        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "WXYZ6789"))
            .thenReturn(Stream.of(initiator));
        when(initiator.getFirstAttribute("pendingLinkCodeExpiresAt"))
            .thenReturn(String.valueOf(Instant.now().plusSeconds(300).getEpochSecond()));
        when(initiator.getFirstAttribute("pendingLinkCode")).thenReturn("WXYZ6789");
        when(userProvider.getFederatedIdentitiesStream(realm, target)).thenReturn(Stream.empty());
        when(userProvider.removeUser(realm, target)).thenReturn(true);

        LinkOutcome outcome = service.redeem(session, realm, target, "WXYZ6789", null);

        assertEquals(LinkOutcome.ALREADY_LINKED, outcome); // no federated identity moved, since target (loser) had none
        verify(userProvider).removeUser(realm, target); // the game identity (initiator) survives
        verify(userProvider, never()).removeUser(realm, initiator);
    }

    @Test
    void redeem_survivorAlreadyHasTheFederatedIdentity_returnsAlreadyLinked() {
        UserModel target = mockUser("game-1", "bohemia_42");
        UserModel initiator = mockUser("portal-1", "discord-temp-user");

        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "CODE0001"))
            .thenReturn(Stream.of(initiator));
        when(initiator.getFirstAttribute("pendingLinkCodeExpiresAt"))
            .thenReturn(String.valueOf(Instant.now().plusSeconds(300).getEpochSecond()));
        when(initiator.getFirstAttribute("pendingLinkCode")).thenReturn("CODE0001");

        FederatedIdentityModel discordIdentity = new FederatedIdentityModel("discord", "ext-1", "someone#1234");
        when(userProvider.getFederatedIdentitiesStream(realm, initiator)).thenReturn(Stream.of(discordIdentity));
        when(userProvider.getFederatedIdentity(realm, target, "discord")).thenReturn(discordIdentity); // already has it
        when(userProvider.removeUser(realm, initiator)).thenReturn(true);

        LinkOutcome outcome = service.redeem(session, realm, target, "CODE0001", null);

        assertEquals(LinkOutcome.ALREADY_LINKED, outcome);
        verify(userProvider, never()).addFederatedIdentity(any(), any(), any());
    }

    @Test
    void redeem_noMatchingCode_returnsInvalidCode() {
        UserModel target = mockUser("game-1", "bohemia_42");
        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "NOPE0000"))
            .thenReturn(Stream.empty());

        assertEquals(LinkOutcome.INVALID_CODE, service.redeem(session, realm, target, "NOPE0000", null));
    }

    @Test
    void redeem_blankOrMalformedCode_returnsInvalidCodeWithoutSearching() {
        UserModel target = mockUser("game-1", "bohemia_42");

        assertEquals(LinkOutcome.INVALID_CODE, service.redeem(session, realm, target, "", null));
        assertEquals(LinkOutcome.INVALID_CODE, service.redeem(session, realm, target, null, null));
        verifyNoInteractions(userProvider);
    }

    @Test
    void redeem_expiredCode_returnsExpiredAndClearsAttributes() {
        UserModel target = mockUser("game-1", "bohemia_42");
        UserModel initiator = mockUser("portal-1", "discord-temp-user");

        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "OLD00001"))
            .thenReturn(Stream.of(initiator));
        when(initiator.getFirstAttribute("pendingLinkCodeExpiresAt"))
            .thenReturn(String.valueOf(Instant.now().minusSeconds(60).getEpochSecond()));

        LinkOutcome outcome = service.redeem(session, realm, target, "OLD00001", null);

        assertEquals(LinkOutcome.EXPIRED_CODE, outcome);
        verify(initiator).removeAttribute("pendingLinkCode");
        verify(initiator).removeAttribute("pendingLinkCodeExpiresAt");
        verify(userProvider, never()).removeUser(any(), any());
    }

    @Test
    void redeem_neitherSideIsGameIdentity_returnsError() {
        UserModel target = mockUser("portal-1", "discord-temp-user");
        UserModel initiator = mockUser("portal-2", "other-discord-user");

        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "BAD00001"))
            .thenReturn(Stream.of(initiator));
        when(initiator.getFirstAttribute("pendingLinkCodeExpiresAt"))
            .thenReturn(String.valueOf(Instant.now().plusSeconds(300).getEpochSecond()));
        when(initiator.getFirstAttribute("pendingLinkCode")).thenReturn("BAD00001");

        assertEquals(LinkOutcome.ERROR, service.redeem(session, realm, target, "BAD00001", null));
        verify(userProvider, never()).removeUser(any(), any());
    }

    @Test
    void redeem_loserIsTheLiveAuthSessionUser_reassignsSessionBeforeDeleting() {
        UserModel target = mockUser("portal-1", "discord-temp-user"); // loser, currently authenticating
        UserModel initiator = mockUser("game-1", "bohemia_42"); // survivor

        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "SESS0001"))
            .thenReturn(Stream.of(initiator));
        when(initiator.getFirstAttribute("pendingLinkCodeExpiresAt"))
            .thenReturn(String.valueOf(Instant.now().plusSeconds(300).getEpochSecond()));
        when(initiator.getFirstAttribute("pendingLinkCode")).thenReturn("SESS0001");
        when(userProvider.getFederatedIdentitiesStream(realm, target)).thenReturn(Stream.empty());
        when(userProvider.removeUser(realm, target)).thenReturn(true);

        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(authSession.getAuthenticatedUser()).thenReturn(target);

        service.redeem(session, realm, target, "SESS0001", authSession);

        var inOrder = inOrder(authSession, userProvider);
        inOrder.verify(authSession).setAuthenticatedUser(initiator);
        inOrder.verify(userProvider).removeUser(realm, target);
    }

    @Test
    void redeem_liveAuthSessionButLoserIsNotTheSessionUser_doesNotReassign() {
        UserModel target = mockUser("game-1", "bohemia_42"); // survivor
        UserModel initiator = mockUser("portal-1", "discord-temp-user"); // loser
        UserModel someoneElse = mockUser("other-1", "unrelated");

        when(userProvider.searchForUserByUserAttributeStream(realm, "pendingLinkCode", "SESS0002"))
            .thenReturn(Stream.of(initiator));
        when(initiator.getFirstAttribute("pendingLinkCodeExpiresAt"))
            .thenReturn(String.valueOf(Instant.now().plusSeconds(300).getEpochSecond()));
        when(initiator.getFirstAttribute("pendingLinkCode")).thenReturn("SESS0002");
        when(userProvider.getFederatedIdentitiesStream(realm, initiator)).thenReturn(Stream.empty());
        when(userProvider.removeUser(realm, initiator)).thenReturn(true);

        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(authSession.getAuthenticatedUser()).thenReturn(someoneElse);

        service.redeem(session, realm, target, "SESS0002", authSession);

        verify(authSession, never()).setAuthenticatedUser(any());
    }
}
```

- [ ] **Step 3: Run the tests, confirm they fail to compile** (classes don't exist yet)

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkedIdentityServiceTest
```

- [ ] **Step 4: Write `LinkedIdentityService.java`**

```java
package net.eliferpg.keycloak.spi.link;

import org.jboss.logging.Logger;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LinkedIdentityService {

    private static final Logger LOGGER = Logger.getLogger(LinkedIdentityService.class);

    static final String CODE_ATTRIBUTE = "pendingLinkCode";
    static final String CODE_EXPIRES_AT_ATTRIBUTE = "pendingLinkCodeExpiresAt";
    static final String LINKED_ATTRIBUTE = "gameAccountLinked";
    static final String BOHEMIA_USERNAME_PREFIX = "bohemia_";

    private static final long CODE_TTL_SECONDS = 600;
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final SecureRandom random = new SecureRandom();

    public String generateCode(RealmModel realm, UserModel caller) {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        String generated = code.toString();
        caller.setSingleAttribute(CODE_ATTRIBUTE, generated);
        caller.setSingleAttribute(CODE_EXPIRES_AT_ATTRIBUTE,
            String.valueOf(Instant.now().plusSeconds(CODE_TTL_SECONDS).getEpochSecond()));
        return generated;
    }

    public LinkOutcome redeem(KeycloakSession session, RealmModel realm, UserModel targetUser,
                               String rawCode, AuthenticationSessionModel authSessionOrNull) {
        try {
            return doRedeem(session, realm, targetUser, rawCode, authSessionOrNull);
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected error during link redeem", e);
            return LinkOutcome.ERROR;
        }
    }

    private LinkOutcome doRedeem(KeycloakSession session, RealmModel realm, UserModel targetUser,
                                  String rawCode, AuthenticationSessionModel authSessionOrNull) {
        if (rawCode == null) {
            return LinkOutcome.INVALID_CODE;
        }
        String code = rawCode.trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            return LinkOutcome.INVALID_CODE;
        }

        UserModel initiator = session.users().searchForUserByUserAttributeStream(realm, CODE_ATTRIBUTE, code)
            .findFirst()
            .orElse(null);
        if (initiator == null) {
            return LinkOutcome.INVALID_CODE;
        }

        long expiresAt = parseEpochSeconds(initiator.getFirstAttribute(CODE_EXPIRES_AT_ATTRIBUTE));
        if (expiresAt <= 0 || Instant.now().getEpochSecond() > expiresAt) {
            clearPendingCode(initiator);
            return LinkOutcome.EXPIRED_CODE;
        }

        // Re-check immediately before consuming, narrowing (not fully eliminating) the
        // race window against a concurrent redeemer of the same code.
        if (!code.equals(initiator.getFirstAttribute(CODE_ATTRIBUTE))) {
            return LinkOutcome.INVALID_CODE;
        }
        clearPendingCode(initiator);

        UserModel survivor;
        UserModel loser;
        if (isGameIdentity(targetUser)) {
            survivor = targetUser;
            loser = initiator;
        } else if (isGameIdentity(initiator)) {
            survivor = initiator;
            loser = targetUser;
        } else {
            LOGGER.warnf("Link redeem matched a code but neither side (%s, %s) is a %s* identity",
                targetUser.getUsername(), initiator.getUsername(), BOHEMIA_USERNAME_PREFIX);
            return LinkOutcome.ERROR;
        }

        if (survivor.getId().equals(loser.getId())) {
            return LinkOutcome.ALREADY_LINKED;
        }

        return mergeInto(session, realm, survivor, loser, authSessionOrNull);
    }

    private LinkOutcome mergeInto(KeycloakSession session, RealmModel realm, UserModel survivor,
                                   UserModel loser, AuthenticationSessionModel authSessionOrNull) {
        boolean movedAny = false;
        List<FederatedIdentityModel> loserIdentities =
            session.users().getFederatedIdentitiesStream(realm, loser).collect(Collectors.toList());
        for (FederatedIdentityModel identity : loserIdentities) {
            if (session.users().getFederatedIdentity(realm, survivor, identity.getIdentityProvider()) != null) {
                continue;
            }
            session.users().addFederatedIdentity(realm, survivor,
                new FederatedIdentityModel(identity.getIdentityProvider(), identity.getUserId(), identity.getUserName()));
            movedAny = true;
        }

        if (authSessionOrNull != null
            && authSessionOrNull.getAuthenticatedUser() != null
            && loser.getId().equals(authSessionOrNull.getAuthenticatedUser().getId())) {
            // Never delete a user out from under a live authentication session -- reassign
            // the in-progress session to the survivor first. See Global Constraints.
            authSessionOrNull.setAuthenticatedUser(survivor);
        }

        boolean removed = session.users().removeUser(realm, loser);
        if (!removed) {
            LOGGER.warnf("Failed to remove loser user %s after merging into %s", loser.getId(), survivor.getId());
        }

        survivor.setSingleAttribute(LINKED_ATTRIBUTE, "true");
        // The survivor may already carry "link-game-account" in its own persistent
        // required-actions list (e.g. evaluateTriggers added it on some earlier login,
        // before this attribute existed) -- that list is independent of the attribute
        // check above and Keycloak processes it regardless of what evaluateTriggers
        // would now decide. Confirmed empirically (post-plan, found during Task 4's live
        // verification, fixed in commit fa9cd06): without this, a real web-initiated
        // merge (session reassigned mid-flow onto the survivor) re-prompts the exact
        // same required action a second time, unresolvably, since the just-consumed
        // code is already gone. Clear it explicitly rather than relying on the
        // attribute write alone.
        survivor.removeRequiredAction("link-game-account");

        return movedAny ? LinkOutcome.LINKED : LinkOutcome.ALREADY_LINKED;
    }

    private void clearPendingCode(UserModel user) {
        user.removeAttribute(CODE_ATTRIBUTE);
        user.removeAttribute(CODE_EXPIRES_AT_ATTRIBUTE);
    }

    private boolean isGameIdentity(UserModel user) {
        String username = user.getUsername();
        return username != null && username.startsWith(BOHEMIA_USERNAME_PREFIX);
    }

    private long parseEpochSeconds(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
```

- [ ] **Step 5: Run the tests, confirm all pass**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkedIdentityServiceTest
```
Expected: `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/eliferpg/keycloak/spi/link/LinkOutcome.java \
        src/main/java/net/eliferpg/keycloak/spi/link/LinkedIdentityService.java \
        src/test/java/net/eliferpg/keycloak/spi/link/LinkedIdentityServiceTest.java
git commit -m "Add LinkedIdentityService: Keycloak-native PIN generation, redeem, and merge logic"
```

---

### Task 2: `LinkGameAccountResource`/`Factory` — new REST extension (self-service + trusted-caller)

**Files:**
- Create: `src/main/java/net/eliferpg/keycloak/spi/link/LinkGameAccountResource.java`
- Create: `src/main/java/net/eliferpg/keycloak/spi/link/LinkGameAccountResourceFactory.java`
- Create: `src/main/resources/META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory`
- Test: `src/test/java/net/eliferpg/keycloak/spi/link/LinkGameAccountResourceTest.java`

**Interfaces:**
- Consumes: `LinkedIdentityService` (Task 1) — `generateCode(RealmModel, UserModel)`, `redeem(KeycloakSession, RealmModel, UserModel, String, AuthenticationSessionModel)` (always passing `null` for the session param — this entry point never has a live browser auth session).
- Produces: REST endpoints at `/realms/{realm}/link-game-account/code` and `/realms/{realm}/link-game-account/redeem` (both `POST`, `application/json`).

To keep the real bearer-token-parsing logic (which needs a real signed JWT to exercise meaningfully) out of unit tests, the public JAX-RS methods stay thin and delegate to package-private methods that accept an already-resolved `AuthenticationManager.AuthResult` — those are what gets unit tested here. Full wire-level bearer-token behavior is exercised by Task 6's live integration test instead.

- [ ] **Step 1: Write the failing tests first — `LinkGameAccountResourceTest.java`**

```java
package net.eliferpg.keycloak.spi.link;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AuthenticationManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LinkGameAccountResourceTest {

    private KeycloakSession session;
    private UserProvider userProvider;
    private RealmModel realm;
    private LinkedIdentityService service;
    private LinkGameAccountResource resource;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        userProvider = mock(UserProvider.class);
        realm = mock(RealmModel.class);
        service = mock(LinkedIdentityService.class);
        when(session.users()).thenReturn(userProvider);
        resource = new LinkGameAccountResource(session, service);
    }

    private AuthenticationManager.AuthResult authResultFor(UserModel user, String scope) {
        AccessToken token = mock(AccessToken.class);
        when(token.getScope()).thenReturn(scope);
        return new AuthenticationManager.AuthResult(user, null, token, null);
    }

    @Test
    void generateCodeInternal_selfService_usesCallerAsTarget() {
        UserModel caller = mock(UserModel.class);
        when(service.generateCode(realm, caller)).thenReturn("ABCD2345");

        Response response = resource.generateCodeInternal(realm, authResultFor(caller, "openid profile"), null);

        assertEquals(200, response.getStatus());
        verify(service).generateCode(realm, caller);
    }

    @Test
    void generateCodeInternal_trustedCaller_resolvesTargetByBohemiaId() throws Exception {
        UserModel serviceAccountUser = mock(UserModel.class);
        UserModel gameUser = mock(UserModel.class);
        when(userProvider.getUserByUsername(realm, "bohemia_42")).thenReturn(gameUser);
        when(service.generateCode(realm, gameUser)).thenReturn("WXYZ6789");

        var body = objectMapper.readTree("{\"bohemiaId\":\"42\"}");
        Response response = resource.generateCodeInternal(realm,
            authResultFor(serviceAccountUser, "accounts:links:redeem-trusted"), body);

        assertEquals(200, response.getStatus());
        verify(service).generateCode(realm, gameUser);
    }

    @Test
    void generateCodeInternal_trustedCallerMissingBohemiaId_returnsBadRequest() throws Exception {
        UserModel serviceAccountUser = mock(UserModel.class);
        Response response = resource.generateCodeInternal(realm,
            authResultFor(serviceAccountUser, "accounts:links:redeem-trusted"), null);

        assertEquals(400, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void generateCodeInternal_trustedCallerUnknownBohemiaId_returnsNotFound() throws Exception {
        UserModel serviceAccountUser = mock(UserModel.class);
        when(userProvider.getUserByUsername(realm, "bohemia_999")).thenReturn(null);

        var body = objectMapper.readTree("{\"bohemiaId\":\"999\"}");
        Response response = resource.generateCodeInternal(realm,
            authResultFor(serviceAccountUser, "accounts:links:redeem-trusted"), body);

        assertEquals(404, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void redeemInternal_selfService_targetIsCaller_returnsOutcome() throws Exception {
        UserModel caller = mock(UserModel.class);
        when(service.redeem(session, realm, caller, "CODE0001", null)).thenReturn(LinkOutcome.LINKED);

        var body = objectMapper.readTree("{\"code\":\"CODE0001\"}");
        Response response = resource.redeemInternal(realm, authResultFor(caller, "openid profile"), body);

        assertEquals(200, response.getStatus());
    }

    @Test
    void redeemInternal_trustedCaller_ignoresSelfServiceTarget_usesBohemiaIdFromBody() throws Exception {
        UserModel serviceAccountUser = mock(UserModel.class);
        UserModel gameUser = mock(UserModel.class);
        when(userProvider.getUserByUsername(realm, "bohemia_42")).thenReturn(gameUser);
        when(service.redeem(session, realm, gameUser, "CODE0002", null)).thenReturn(LinkOutcome.LINKED);

        var body = objectMapper.readTree("{\"bohemiaId\":\"42\",\"code\":\"CODE0002\"}");
        Response response = resource.redeemInternal(realm,
            authResultFor(serviceAccountUser, "accounts:links:redeem-trusted"), body);

        assertEquals(200, response.getStatus());
        verify(service).redeem(session, realm, gameUser, "CODE0002", null);
    }

    @Test
    void redeemInternal_invalidCode_returns404() throws Exception {
        UserModel caller = mock(UserModel.class);
        when(service.redeem(session, realm, caller, "BADCODE1", null)).thenReturn(LinkOutcome.INVALID_CODE);

        var body = objectMapper.readTree("{\"code\":\"BADCODE1\"}");
        Response response = resource.redeemInternal(realm, authResultFor(caller, "openid profile"), body);

        assertEquals(404, response.getStatus());
    }

    @Test
    void redeemInternal_expiredCode_returns410() throws Exception {
        UserModel caller = mock(UserModel.class);
        when(service.redeem(session, realm, caller, "OLDCODE1", null)).thenReturn(LinkOutcome.EXPIRED_CODE);

        var body = objectMapper.readTree("{\"code\":\"OLDCODE1\"}");
        Response response = resource.redeemInternal(realm, authResultFor(caller, "openid profile"), body);

        assertEquals(410, response.getStatus());
    }

    @Test
    void redeemInternal_serviceError_returns500() throws Exception {
        UserModel caller = mock(UserModel.class);
        when(service.redeem(session, realm, caller, "ERRCODE1", null)).thenReturn(LinkOutcome.ERROR);

        var body = objectMapper.readTree("{\"code\":\"ERRCODE1\"}");
        Response response = resource.redeemInternal(realm, authResultFor(caller, "openid profile"), body);

        assertEquals(500, response.getStatus());
    }

    @Test
    void factory_returnsExpectedId() {
        LinkGameAccountResourceFactory factory = new LinkGameAccountResourceFactory();
        assertEquals("link-game-account", factory.getId());
    }
}
```

- [ ] **Step 2: Run the tests, confirm they fail to compile**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkGameAccountResourceTest
```

- [ ] **Step 3: Write `LinkGameAccountResource.java`**

```java
package net.eliferpg.keycloak.spi.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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

public class LinkGameAccountResource implements RealmResourceProvider {

    static final String TRUSTED_SCOPE = "accounts:links:redeem-trusted";
    private static final String BOHEMIA_USERNAME_PREFIX = "bohemia_";

    private final KeycloakSession session;
    private final LinkedIdentityService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LinkGameAccountResource(KeycloakSession session, LinkedIdentityService service) {
        this.session = session;
        this.service = service;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @POST
    @Path("code")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response generateCode(InputStream body) {
        AuthenticationManager.AuthResult auth = authenticate();
        if (auth == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "unauthenticated");
        }
        return generateCodeInternal(session.getContext().getRealm(), auth, readJson(body));
    }

    @POST
    @Path("redeem")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response redeem(InputStream body) {
        AuthenticationManager.AuthResult auth = authenticate();
        if (auth == null) {
            return errorResponse(Response.Status.UNAUTHORIZED, "unauthenticated");
        }
        return redeemInternal(session.getContext().getRealm(), auth, readJson(body));
    }

    Response generateCodeInternal(RealmModel realm, AuthenticationManager.AuthResult auth, JsonNode body) {
        UserModel target;
        if (hasTrustedScope(auth)) {
            UserModel resolved = resolveTargetFromBohemiaId(realm, body);
            if (resolved == null) {
                return missingOrUnknownBohemiaIdResponse(realm, body);
            }
            target = resolved;
        } else {
            target = auth.getUser();
        }

        String code = service.generateCode(realm, target);
        return Response.ok(Map.of("code", code, "expiresInSeconds", 600)).build();
    }

    Response redeemInternal(RealmModel realm, AuthenticationManager.AuthResult auth, JsonNode body) {
        String code = body == null ? null : body.path("code").asText(null);

        UserModel target;
        if (hasTrustedScope(auth)) {
            UserModel resolved = resolveTargetFromBohemiaId(realm, body);
            if (resolved == null) {
                return missingOrUnknownBohemiaIdResponse(realm, body);
            }
            target = resolved;
        } else {
            target = auth.getUser();
        }

        LinkOutcome outcome = service.redeem(session, realm, target, code, null);
        return switch (outcome) {
            case LINKED -> Response.ok(Map.of("outcome", "linked")).build();
            case ALREADY_LINKED -> Response.ok(Map.of("outcome", "already-linked")).build();
            case INVALID_CODE -> errorResponse(Response.Status.NOT_FOUND, "invalid code");
            case EXPIRED_CODE -> Response.status(410).entity(Map.of("error", "expired code")).build();
            case ERROR -> errorResponse(Response.Status.INTERNAL_SERVER_ERROR, "could not complete link");
        };
    }

    private UserModel resolveTargetFromBohemiaId(RealmModel realm, JsonNode body) {
        if (body == null) {
            return null;
        }
        String bohemiaId = body.path("bohemiaId").asText(null);
        if (bohemiaId == null || bohemiaId.isBlank()) {
            return null;
        }
        return session.users().getUserByUsername(realm, BOHEMIA_USERNAME_PREFIX + bohemiaId);
    }

    private Response missingOrUnknownBohemiaIdResponse(RealmModel realm, JsonNode body) {
        String bohemiaId = body == null ? null : body.path("bohemiaId").asText(null);
        if (bohemiaId == null || bohemiaId.isBlank()) {
            return errorResponse(Response.Status.BAD_REQUEST, "bohemiaId required for trusted callers");
        }
        return errorResponse(Response.Status.NOT_FOUND, "unknown bohemiaId");
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
```

- [ ] **Step 4: Write `LinkGameAccountResourceFactory.java`**

```java
package net.eliferpg.keycloak.spi.link;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public class LinkGameAccountResourceFactory implements RealmResourceProviderFactory {

    public static final String ID = "link-game-account";

    private final LinkedIdentityService service = new LinkedIdentityService();

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new LinkGameAccountResource(session, service);
    }

    @Override
    public void init(Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public String getId() {
        return ID;
    }
}
```

- [ ] **Step 5: Write the service registration file**

`src/main/resources/META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory`:
```
net.eliferpg.keycloak.spi.link.LinkGameAccountResourceFactory
```

- [ ] **Step 6: Run the tests, confirm all pass**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkGameAccountResourceTest
```
Expected: `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/eliferpg/keycloak/spi/link/LinkGameAccountResource.java \
        src/main/java/net/eliferpg/keycloak/spi/link/LinkGameAccountResourceFactory.java \
        src/main/resources/META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory \
        src/test/java/net/eliferpg/keycloak/spi/link/LinkGameAccountResourceTest.java
git commit -m "Add LinkGameAccountResource: self-service and trusted-caller REST endpoints for linking"
```

---

### Task 3: Rewire `LinkGameAccountRequiredAction` onto `LinkedIdentityService`, retire `CentralApiClient`

**Files:**
- Modify: `src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredAction.java`
- Modify: `src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionFactory.java`
- Delete: `src/main/java/net/eliferpg/keycloak/spi/CentralApiClient.java`
- Delete: `src/main/java/net/eliferpg/keycloak/spi/CentralApiClientException.java`
- Delete: `src/main/java/net/eliferpg/keycloak/spi/RedeemOutcome.java`
- Delete: `src/test/java/net/eliferpg/keycloak/spi/CentralApiClientTest.java`
- Test: `src/test/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionTest.java` (rewrite in place)

**Interfaces:**
- Consumes: `net.eliferpg.keycloak.spi.link.LinkedIdentityService`/`LinkOutcome` (Task 1) — `redeem(KeycloakSession, RealmModel, UserModel, String, AuthenticationSessionModel)`, passing `context.getSession()`, `context.getRealm()`, `context.getUser()`, the submitted code, and `context.getAuthenticationSession()` (non-null, this is the live-session case the safety fix in Global Constraints exists for).

- [ ] **Step 1: Write the failing tests first — rewrite `LinkGameAccountRequiredActionTest.java`**

```java
package net.eliferpg.keycloak.spi;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import net.eliferpg.keycloak.spi.link.LinkOutcome;
import net.eliferpg.keycloak.spi.link.LinkedIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LinkGameAccountRequiredActionTest {

    private LinkedIdentityService service;
    private RequiredActionContext context;
    private UserModel user;
    private HttpRequest httpRequest;
    private LoginFormsProvider formsProvider;
    private AuthenticationSessionModel authSession;
    private LinkGameAccountRequiredAction action;

    @BeforeEach
    void setUp() {
        service = mock(LinkedIdentityService.class);
        context = mock(RequiredActionContext.class);
        user = mock(UserModel.class);
        httpRequest = mock(HttpRequest.class);
        formsProvider = mock(LoginFormsProvider.class, org.mockito.Answers.RETURNS_SELF);
        authSession = mock(AuthenticationSessionModel.class);

        when(context.getUser()).thenReturn(user);
        when(context.getHttpRequest()).thenReturn(httpRequest);
        when(context.form()).thenReturn(formsProvider);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(formsProvider.createForm(anyString())).thenReturn(mock(Response.class));

        action = new LinkGameAccountRequiredAction(service);
    }

    @SuppressWarnings("unchecked")
    private void submitCode(String code) {
        MultivaluedMap<String, String> params = mock(MultivaluedMap.class);
        when(params.getFirst("code")).thenReturn(code);
        when(httpRequest.getDecodedFormParameters()).thenReturn(params);
    }

    @Test
    void evaluateTriggers_addsRequiredAction_whenNotLinked() {
        when(user.getFirstAttribute("gameAccountLinked")).thenReturn(null);
        action.evaluateTriggers(context);
        verify(user).addRequiredAction(LinkGameAccountRequiredActionFactory.PROVIDER_ID);
    }

    @Test
    void evaluateTriggers_doesNothing_whenAlreadyLinked() {
        when(user.getFirstAttribute("gameAccountLinked")).thenReturn("true");
        action.evaluateTriggers(context);
        verify(user, never()).addRequiredAction(anyString());
    }

    @Test
    void requiredActionChallenge_rendersForm() {
        action.requiredActionChallenge(context);
        verify(formsProvider).createForm("link-game-account.ftl");
        verify(context).challenge(any());
    }

    @Test
    void processAction_linked_succeeds() {
        submitCode("ABCD1234");
        when(service.redeem(any(), any(), eq(user), eq("ABCD1234"), eq(authSession))).thenReturn(LinkOutcome.LINKED);

        action.processAction(context);

        verify(context).success();
        verify(context, never()).challenge(any());
    }

    @Test
    void processAction_alreadyLinked_succeeds() {
        submitCode("ABCD1234");
        when(service.redeem(any(), any(), eq(user), anyString(), eq(authSession))).thenReturn(LinkOutcome.ALREADY_LINKED);
        action.processAction(context);
        verify(context).success();
    }

    @Test
    void processAction_invalidCode_reChallengesWithSpecificError() {
        submitCode("ABCD1234");
        when(service.redeem(any(), any(), eq(user), anyString(), eq(authSession))).thenReturn(LinkOutcome.INVALID_CODE);

        action.processAction(context);

        verify(formsProvider).setError("linkGameAccountInvalidCode");
        verify(context, never()).success();
    }

    @Test
    void processAction_expiredCode_reChallengesWithDistinctError() {
        submitCode("ABCD1234");
        when(service.redeem(any(), any(), eq(user), anyString(), eq(authSession))).thenReturn(LinkOutcome.EXPIRED_CODE);

        action.processAction(context);

        verify(formsProvider).setError("linkGameAccountExpiredCode");
    }

    @Test
    void processAction_error_reChallengesWithGenericError() {
        submitCode("ABCD1234");
        when(service.redeem(any(), any(), eq(user), anyString(), eq(authSession))).thenReturn(LinkOutcome.ERROR);

        action.processAction(context);

        verify(formsProvider).setError("linkGameAccountError");
    }

    @Test
    void processAction_serviceThrows_doesNotPropagate_reChallenges() {
        submitCode("ABCD1234");
        when(service.redeem(any(), any(), eq(user), anyString(), eq(authSession))).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> action.processAction(context));

        verify(formsProvider).setError("linkGameAccountError");
    }

    @Test
    void processAction_blankCode_rejectedWithoutCallingService() {
        submitCode("");
        action.processAction(context);
        verifyNoInteractions(service);
        verify(formsProvider).setError("linkGameAccountInvalidInput");
    }

    @Test
    void processAction_missingCode_rejectedWithoutCallingService() {
        submitCode(null);
        action.processAction(context);
        verifyNoInteractions(service);
        verify(formsProvider).setError("linkGameAccountInvalidInput");
    }

    @Test
    void factory_returnsExactProviderId() {
        LinkGameAccountRequiredActionFactory factory = new LinkGameAccountRequiredActionFactory();
        org.junit.jupiter.api.Assertions.assertEquals("link-game-account", factory.getId());
    }
}
```

Note: `eq(...)` needs `import static org.mockito.ArgumentMatchers.eq;` — add it to the imports above.

- [ ] **Step 2: Run the tests, confirm they fail** (still referencing the old `CentralApiClient`-based constructor)

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkGameAccountRequiredActionTest
```

- [ ] **Step 3: Rewrite `LinkGameAccountRequiredAction.java`**

```java
package net.eliferpg.keycloak.spi;

import net.eliferpg.keycloak.spi.link.LinkOutcome;
import net.eliferpg.keycloak.spi.link.LinkedIdentityService;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;

import java.util.regex.Pattern;

public class LinkGameAccountRequiredAction implements RequiredActionProvider {

    static final String LINKED_ATTRIBUTE = "gameAccountLinked";
    static final String FORM_TEMPLATE = "link-game-account.ftl";
    static final String CODE_PARAM = "code";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final LinkedIdentityService service;

    LinkGameAccountRequiredAction(LinkedIdentityService service) {
        this.service = service;
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        String linked = context.getUser().getFirstAttribute(LINKED_ATTRIBUTE);
        if (linked == null || linked.isBlank()) {
            context.getUser().addRequiredAction(LinkGameAccountRequiredActionFactory.PROVIDER_ID);
        }
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        context.challenge(context.form().createForm(FORM_TEMPLATE));
    }

    @Override
    public void processAction(RequiredActionContext context) {
        String code = context.getHttpRequest().getDecodedFormParameters().getFirst(CODE_PARAM);

        if (code == null || !CODE_PATTERN.matcher(code.trim()).matches()) {
            challengeWithError(context, "linkGameAccountInvalidInput");
            return;
        }

        LinkOutcome outcome;
        try {
            outcome = service.redeem(context.getSession(), context.getRealm(), context.getUser(),
                code.trim(), context.getAuthenticationSession());
        } catch (RuntimeException e) {
            outcome = LinkOutcome.ERROR;
        }

        switch (outcome) {
            case LINKED, ALREADY_LINKED -> context.success();
            case INVALID_CODE -> challengeWithError(context, "linkGameAccountInvalidCode");
            case EXPIRED_CODE -> challengeWithError(context, "linkGameAccountExpiredCode");
            case ERROR -> challengeWithError(context, "linkGameAccountError");
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
```

Note: `LinkedIdentityService.redeem(...)` already sets `gameAccountLinked=true` on the survivor internally (see Task 1) — `processAction` no longer needs to set that attribute itself, unlike the old `CentralApiClient`-based version.

- [ ] **Step 4: Rewrite `LinkGameAccountRequiredActionFactory.java`**

```java
package net.eliferpg.keycloak.spi;

import net.eliferpg.keycloak.spi.link.LinkedIdentityService;
import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class LinkGameAccountRequiredActionFactory implements RequiredActionFactory {

    /**
     * Referenced verbatim by eliferpg-realm.json when wiring this required action
     * into the authentication flow — do not change without coordinating that change.
     */
    public static final String PROVIDER_ID = "link-game-account";

    private final LinkedIdentityService service = new LinkedIdentityService();

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new LinkGameAccountRequiredAction(service);
    }

    @Override
    public void init(Config.Scope config) {
        // no-op -- no external config needed anymore, this SPI makes no outbound HTTP calls
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayText() {
        return "Link Game Account";
    }
}
```

- [ ] **Step 5: Delete the retired files**

```bash
git rm src/main/java/net/eliferpg/keycloak/spi/CentralApiClient.java \
       src/main/java/net/eliferpg/keycloak/spi/CentralApiClientException.java \
       src/main/java/net/eliferpg/keycloak/spi/RedeemOutcome.java \
       src/test/java/net/eliferpg/keycloak/spi/CentralApiClientTest.java
```

- [ ] **Step 6: Run the tests, confirm all pass**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkGameAccountRequiredActionTest
```
Expected: `Tests run: 12, Failures: 0, Errors: 0`.

- [ ] **Step 7: Run the FULL suite, confirm nothing regressed**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test
```
Expected: `Tests run: 33, Failures: 0, Errors: 0` (10 from Task 1 + 11 from Task 2 [10 originally + 1 added during its fix round] + 12 from Task 3; the old `CentralApiClientTest` and the old 14-test `LinkGameAccountRequiredActionTest` are gone by this point — deleted/replaced in this same task).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredAction.java \
        src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionFactory.java \
        src/test/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionTest.java
git commit -m "Rewire LinkGameAccountRequiredAction onto LinkedIdentityService, retire CentralApiClient"
```

---

### Task 4: Packaging cleanup — retire the now-unused Central API config and stub

**Files:**
- Modify: `README.md` — remove the four `ELIFERPG_*` env var docs, the "Central API contract gap" section; add a new section documenting the two REST endpoints' contracts (for whoever later builds Central API's thin Bridge-facing proxy).
- Modify: `compose.yml`, `docker-compose.integration-test.yml` — remove the `stub-central-api` service entirely and the four `ELIFERPG_*` env vars from the `keycloak` service (this SPI makes no outbound HTTP calls anymore).
- Delete: `src/test/java/net/eliferpg/keycloak/spi/stub/StubCentralApiServer.java`, `src/test/java/net/eliferpg/keycloak/spi/stub/Dockerfile`
- Modify: `scripts/local-dev-up.sh`, `scripts/integration-test.sh` — remove `keycloak-spi`/`ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET` client-credentials bootstrap (no longer needed — nothing calls out to a trusted client from Keycloak anymore); keep everything else (realm creation, `loginTheme`, required-action registration, test user creation) as-is, since none of that changed.

**Interfaces:**
- Consumes: nothing new.

- [ ] **Step 1: Update `compose.yml`** — remove the `stub-central-api` service block and the `ELIFERPG_CENTRAL_API_BASE_URL`/`ELIFERPG_KEYCLOAK_TOKEN_URL`/`ELIFERPG_KEYCLOAK_SPI_CLIENT_ID`/`ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET` lines and the `depends_on: - stub-central-api` line from the `keycloak` service. Result should be just the `keycloak` service, built from `Dockerfile`, with only `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` env vars.

- [ ] **Step 2: Same edit to `docker-compose.integration-test.yml`.**

- [ ] **Step 3: Delete the stub server files**

```bash
git rm src/test/java/net/eliferpg/keycloak/spi/stub/StubCentralApiServer.java \
       src/test/java/net/eliferpg/keycloak/spi/stub/Dockerfile
```

- [ ] **Step 4: Update `scripts/local-dev-up.sh` and `scripts/integration-test.sh`** — remove the `keycloak-spi` service-account client creation call (the `POST .../clients` call with `"clientId":"keycloak-spi"`) from both scripts, since nothing in this SPI authenticates as a trusted client anymore. Leave everything else unchanged.

- [ ] **Step 5: Verify the build still succeeds** (this task touches no Java, but confirms nothing broke)

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-spi-reforger-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B clean package
```
Expected: `BUILD SUCCESS`, 30/30 tests still passing.

- [ ] **Step 6: Commit**

```bash
git add compose.yml docker-compose.integration-test.yml scripts/local-dev-up.sh scripts/integration-test.sh
git rm -r src/test/java/net/eliferpg/keycloak/spi/stub
git commit -m "Retire stub Central API and outbound-HTTP config -- this SPI is now self-contained"
```

---

### Task 5: End-to-end verification against the actual devcontainer + realm

**Files:**
- Modify: `TESTING.md` — rewrite to reflect the new architecture: no stub server, a `bohemia_*` test user pre-seeded via Admin API instead of a real gameserver provisioning flow, and a direct curl exercise of both new REST endpoints (self-service + trusted-caller mode) alongside the browser-flow redeem test.

This task is manual verification + documentation, not new code — run it for real (same discipline as the original SPI's Task 5) and capture actual output, not a hypothetical transcript.

- [ ] **Step 1: Bring up the stack** (`./scripts/local-dev-up.sh` or the devcontainer's port-relay-backed equivalent) and confirm it still reaches readiness.

- [ ] **Step 2: Pre-seed a `bohemia_*` test user** via Admin API (`POST /admin/realms/{realm}/users` with `username: "bohemia_TEST001"`, matching the existing convention) to stand in for a gameserver-provisioned account, since this repo doesn't provision one itself.

- [ ] **Step 3: Exercise self-service generate + redeem via curl directly against the new endpoints**:
  - `POST /realms/{realm}/link-game-account/code` with a Bearer token for a portal-style test user (no special scope) — confirm `200` with a `code` field.
  - `POST /realms/{realm}/link-game-account/redeem` with a Bearer token for the `bohemia_TEST001` user (if it can obtain one — otherwise substitute a trusted-caller-mode redeem here instead, see Step 4) and the code from the previous step — confirm `200 {"outcome":"linked"}`, and confirm via Admin API that the portal user was deleted and `bohemia_TEST001` now has the portal user's federated identity (if any was set up) and `gameAccountLinked=true`.

- [ ] **Step 4: Exercise trusted-caller mode** — create a test client with `serviceAccountsEnabled: true` and grant it a client scope named `accounts:links:redeem-trusted` (matching Global Constraints), obtain a client-credentials token for it, then `POST /realms/{realm}/link-game-account/redeem` with `{"bohemiaId": "TEST001", "code": "..."}` and confirm the same outcome — this simulates what Central API's future thin Bridge-facing proxy will do.

- [ ] **Step 5: Re-drive the required-action browser flow** (same `curl`-scripted technique `scripts/integration-test.sh` already established) end-to-end: a fresh Discord-broker-style temp user completes login, enters a code that was generated on a pre-seeded `bohemia_*` user, and lands past the required action successfully — confirming the web-initiated path (through `LinkGameAccountRequiredAction`, not the REST resource) also works.

- [ ] **Step 6: Write the real captured output into `TESTING.md`**, replacing the old Central-API-contract-based walkthrough.

- [ ] **Step 7: Commit**

```bash
git add TESTING.md
git commit -m "Document a real end-to-end walkthrough of the Keycloak-native linking flow"
```

---

### Task 6: README rewrite

**Files:**
- Modify: `README.md`

- [ ] **Step 1:** Remove the "Configuration (environment variables)" section entirely (no env vars needed anymore) and the "Central API contract gap" section (obsolete — replaced by this repo owning the contract directly).
- [ ] **Step 2:** Add a "REST API" section documenting both endpoints (`POST /realms/{realm}/link-game-account/code`, `POST /realms/{realm}/link-game-account/redeem`), their two auth modes (self-service bearer token vs. `accounts:links:redeem-trusted`-scoped client-credentials + explicit `bohemiaId`), and response shapes — this is now the contract document for whoever builds Central API's thin Bridge-facing proxy later, taking over the role the old "Central API contract gap" section used to serve in reverse.
- [ ] **Step 3:** Update the "Wiring this into a realm" section's item about the `keycloak-spi` client/`accounts:links:redeem-trusted` scope to reflect that the scope is now granted to whatever trusted client calls *this* repo's REST endpoints (e.g. Central API's future proxy), not the other way around.
- [ ] **Step 4:** Leave unchanged: provider id section, devcontainer section, "Trying it yourself" section (still accurate — `local-dev-up.sh`/`integration-test.sh` still exist, just simplified), versioning/compatibility section, JDK version section.
- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "Update README for the Keycloak-native linking architecture"
```
