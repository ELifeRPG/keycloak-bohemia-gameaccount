package com.eliferpg.keycloak.spi.bohemiagameaccount;

public enum LinkOutcome {
    LINKED,
    ALREADY_LINKED,
    /** The PIN was unknown, malformed, or already consumed (expiry is indistinguishable). */
    INVALID_PIN,
    /** The bohemiaId in the PIN is already bound to a different Keycloak user. */
    CONFLICT,
    ERROR
}
