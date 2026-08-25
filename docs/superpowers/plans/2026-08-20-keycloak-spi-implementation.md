# keycloak-eliferpg-spi Implementation Plan

> **Repo renamed:** this project was originally scaffolded as
> `keycloak-eliferpg-spi` and was later renamed to `keycloak-spi-reforger`.
> This plan is left as the historical execution record (all 6 tasks below
> were completed under the original name) and was not mechanically
> re-titled throughout — where it says `keycloak-eliferpg-spi`, read it as
> this repo under its original name, including the `keycloak-eliferpg-spi-m2`
> Docker volume name referenced in several verification commands (the repo
> now uses `keycloak-spi-reforger-m2` instead).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Keycloak 26.0.8 Required Action Provider (`link-game-account`) that prompts an authenticating user for a PIN, redeems it against eliferpg's Central API over a server-to-server client-credentials call, and completes the login once linked — packaged as a JAR + Docker image.

**Architecture:** A Maven single-module Java project. `LinkGameAccountRequiredActionFactory`/`LinkGameAccountRequiredAction` implement Keycloak's `RequiredActionFactory`/`RequiredActionProvider` SPI. `CentralApiClient` is a small internal HTTP client (JDK `java.net.http.HttpClient` + Jackson, both supplied by the Keycloak runtime) that fetches/caches an OAuth2 client-credentials token from Keycloak's own token endpoint and calls the Central API's `POST /api/accounts/links/redeem`. The provider never blocks the login flow on an unhandled exception — every outcome (success, invalid code, expired code, network error) maps to a distinct, typed `RedeemOutcome`.

**Tech Stack:** Java 21, Maven, Keycloak SPI 26.0.8 (`provided` scope), JDK `HttpClient`, Jackson (transitive from `keycloak-services`), JUnit 5 + Mockito (test only), Docker.

**Spec:** `IMPLEMENTATION_PLAN.md` (repo root) — the original requirements document this plan implements. Read both; this plan is the argued, task-broken-down version of that spec, and resolves several details the spec left open (see Global Constraints and the per-task notes below).

## Global Constraints

These were verified empirically against the actual `quay.io/keycloak/keycloak:26.0` image and Maven Central during plan authoring — treat them as ground truth, not assumptions:

- **JDK 21.** Confirmed via `docker run --rm --entrypoint java quay.io/keycloak/keycloak:26.0 -version` → `openjdk 21.0.6`. `maven.compiler.release=21`.
- **Keycloak patch version 26.0.8.** Confirmed via `kc.sh --version` inside the image. All `org.keycloak:*` dependencies pin to `26.0.8` exactly.
- **No `mvn`/`java` on the host running these tasks.** Every Maven invocation MUST run inside Docker:
  `docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn ...`
  The named volume `keycloak-eliferpg-spi-m2` already exists and is pre-warmed with all dependency downloads from plan authoring — reuse it (do not use `-v $(pwd)/.m2:...` or an anonymous volume, and do not `docker volume rm` it).
- **Maven coordinates verified to resolve** (via `mvn dependency:tree` against the pre-warmed repo):
  - `org.keycloak:keycloak-server-spi:26.0.8:provided`
  - `org.keycloak:keycloak-server-spi-private:26.0.8:provided`
  - `org.keycloak:keycloak-services:26.0.8:provided`
  - `org.junit.jupiter:junit-jupiter:5.10.3:test`
  - `org.mockito:mockito-core:5.12.0:test`
  - Plugins: `maven-compiler-plugin:3.13.0`, `maven-surefire-plugin:3.2.5`, `maven-jar-plugin:3.4.1`
- **Do not add an explicit Jackson dependency.** `com.fasterxml.jackson.core:jackson-databind:2.17.2` arrives transitively at `provided` scope from `keycloak-services` — confirmed via `dependency:tree`. Adding it explicitly risks a version mismatch with the runtime.
- **`org.jboss.logging:jboss-logging:3.6.0.Final` is available transitively at `provided` scope** — use `org.jboss.logging.Logger` for logging (Keycloak's own convention), not `java.util.logging` or a new SLF4J dependency.
- **A benign warning is expected and NOT a failure**: `mvn test` prints `ERROR: The LogManager accessed before the "java.util.logging.manager" system property was set...` from jboss-logmanager. Tests still pass; do not attempt to "fix" this — it's an artifact of running jboss-logging outside a real Keycloak process.
- **groupId/artifactId/version:** `net.eliferpg` / `keycloak-eliferpg-spi` / `0.1.0-SNAPSHOT`, packaging `jar`.
- **Provider id:** exactly `"link-game-account"` (verbatim, hyphenated, matches plan §7.1 — not `link-game-action` or any other variant). This is the string `eliferpg-realm.json` will reference later — do not typo it. It must be returned by both `LinkGameAccountRequiredActionFactory.getId()` and passed to `UserModel.addRequiredAction(...)`.
- **Env vars** (read via `System.getenv`, fail fast with `IllegalStateException` if unset/blank — never a guessed default):
  `ELIFERPG_CENTRAL_API_BASE_URL`, `ELIFERPG_KEYCLOAK_SPI_CLIENT_ID`, `ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET`, `ELIFERPG_KEYCLOAK_TOKEN_URL`.
- **Central API contract** (per spec §5.2 — this endpoint does not exist yet on the Central API side as of plan authoring; build against this contract and a local stub, per spec's own instruction not to block on it):
  ```
  POST {ELIFERPG_CENTRAL_API_BASE_URL}/api/accounts/links/redeem
  Authorization: Bearer <keycloak-spi client-credentials token>
  Content-Type: application/json

  {"keycloakUserId": "<kc user id>", "code": "<pin>"}
  ```
  Response mapping (this plan's own decision, since the spec doesn't disambiguate 404 vs 410 — flag this mapping when coordinating with whoever implements the Central API side, per spec §5.2's own instruction to flag the contract explicitly):
  - `200 {"outcome": "linked"}` → `RedeemOutcome.LINKED`
  - `200 {"outcome": "already-linked"}` → `RedeemOutcome.ALREADY_LINKED`
  - `200 {"outcome": "merged"}` → `RedeemOutcome.MERGED`
  - `404` → `RedeemOutcome.INVALID_CODE` (unknown code — HTTP "Not Found")
  - `410` → `RedeemOutcome.EXPIRED_CODE` (was valid, now gone — HTTP "Gone")
  - anything else (5xx, timeout, connection refused, malformed body, unrecognized `outcome` value) → `RedeemOutcome.ERROR`
- **Attribute name is `gameAccountLinked` (a boolean marker), NOT `bohemiaId`.** The spec's own §7.2 hedges with "or equivalent confirmation" — this plan exercises that: the redeem response never hands back the real Bohemia ID, so writing a fabricated value into `bohemiaId` would corrupt real user data. `evaluateTriggers` reads `gameAccountLinked`; on any success outcome, `processAction` sets `gameAccountLinked=true`. The real `bohemiaId` attribute is left untouched — out of this SPI's authority per spec §4 (non-goals).
- **Code validation pattern:** `^[A-Za-z0-9-]{1,32}$` (applied after `.trim()`) — reject before calling the Central API.
- **HTTP timeout:** 5 seconds (`Duration.ofSeconds(5)`), applied to both connect and per-request timeout on every `HttpRequest`.
- **Token cache expiry margin:** 30 seconds — refetch when `Instant.now()` is after `expiresAt.minusSeconds(30)`.
- **FTL/theme conventions verified against the real base theme** (extracted from `/opt/keycloak/lib/lib/main/org.keycloak.keycloak-themes-26.0.8.jar` inside the image): custom login templates use `<#import "template.ftl" as layout>` + `<@layout.registrationLayout displayMessage=true; section>...</@layout.registrationLayout>`, form action is `${url.loginAction}` (a fully-qualified URL that already embeds `session_code`/`execution`/`tab_id` — **no hidden form fields need to be added by us**), CSS/label classes come from `properties.kcXxxClass!`, and the layout auto-renders the `message` object (populated by `LoginFormsProvider.setError(...)`) when `displayMessage=true` — our FTL does not need to render its own error banner.
- **Theme parent:** our `theme/eliferpg/login/theme.properties` sets `parent=keycloak` (not `base`) so it inherits the full PatternFly-styled login theme, CSS, JS, and base message bundle; we only add our new template + message keys.
- **Message keys** (in `theme/eliferpg/login/messages/messages_en.properties`): `linkGameAccountTitle`, `linkGameAccountCodeLabel`, `linkGameAccountInvalidInput`, `linkGameAccountInvalidCode`, `linkGameAccountExpiredCode`, `linkGameAccountError`.
- **Required-action login flow cannot be exercised via the OAuth2 Resource Owner Password (direct grant) flow** — confirmed empirically: a pending required action makes Keycloak's token endpoint return `{"error":"invalid_grant","error_description":"Account is not fully set up"}` for ROPC. Integration testing (Task 5) MUST drive the real browser-style authorization code flow via `curl` + HTML scraping of the `<form ... action="...">` attribute — confirmed empirically to work (see Task 5).
- **`quay.io/keycloak/keycloak:26.0` is already pulled locally** — do not re-pull unless it's missing (`docker images | grep keycloak`).
- Every task's steps implicitly include these constraints — a reviewer should treat a deviation from any bullet above as a defect unless a later task's text explicitly overrides it.

---

### Task 1: Maven Project Scaffolding

**Files:**
- Create: `pom.xml`
- Create: `.gitignore` (already exists at repo root from workspace setup — verify it still covers `target/`, `.superpowers/`, `.serena/`, `*.class`, `.idea/`, `*.iml`; add any that are missing)
- Create: `src/main/java/net/eliferpg/keycloak/spi/.gitkeep` (placeholder so the directory exists in git before Task 2/3 populate it — delete this file the moment a real `.java` file lands in that directory)

**Interfaces:**
- Produces: a `pom.xml` that resolves all dependencies listed in Global Constraints, at the versions listed there, with `maven.compiler.release=21`.

- [ ] **Step 1: Write `pom.xml`**

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <groupId>net.eliferpg</groupId>
  <artifactId>keycloak-eliferpg-spi</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <keycloak.version>26.0.8</keycloak.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.keycloak</groupId>
      <artifactId>keycloak-server-spi</artifactId>
      <version>${keycloak.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.keycloak</groupId>
      <artifactId>keycloak-server-spi-private</artifactId>
      <version>${keycloak.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.keycloak</groupId>
      <artifactId>keycloak-services</artifactId>
      <version>${keycloak.version}</version>
      <scope>provided</scope>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.3</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <version>5.12.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <version>3.4.1</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Create the source directory skeleton**

```bash
mkdir -p src/main/java/net/eliferpg/keycloak/spi
mkdir -p src/main/resources/META-INF/services
mkdir -p src/main/resources/theme/eliferpg/login/messages
mkdir -p src/test/java/net/eliferpg/keycloak/spi
touch src/main/java/net/eliferpg/keycloak/spi/.gitkeep
```

- [ ] **Step 3: Verify the pom resolves and the build plugins are valid**

Run (from repo root):
```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B dependency:resolve
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B validate
```
Expected: both `BUILD SUCCESS`, no dependency resolution errors. The repo volume is pre-warmed, so this should complete in well under a minute with no downloads for the coordinates listed in Global Constraints (the two new build plugins may download on first use — that's expected).

- [ ] **Step 4: Confirm Jackson and jboss-logging really do arrive transitively**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B dependency:tree | grep -iE "jackson-databind|jboss-logging:jboss-logging"
```
Expected output includes lines like:
```
+- com.fasterxml.jackson.core:jackson-databind:jar:2.17.2:provided
+- org.jboss.logging:jboss-logging:jar:3.6.0.Final:provided
```
If either is missing, STOP — the dependency tree shape has changed from what this plan verified; do not add an explicit Jackson/logging dependency without re-confirming the actual transitive versions in play.

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore src/main/java/net/eliferpg/keycloak/spi/.gitkeep src/main/resources src/test/java
git commit -m "Scaffold Maven project for keycloak-eliferpg-spi"
```

---

### Task 2: `CentralApiClient` — Central API HTTP client with token caching

**Files:**
- Create: `src/main/java/net/eliferpg/keycloak/spi/RedeemOutcome.java`
- Create: `src/main/java/net/eliferpg/keycloak/spi/CentralApiClientException.java`
- Create: `src/main/java/net/eliferpg/keycloak/spi/CentralApiClient.java`
- Test: `src/test/java/net/eliferpg/keycloak/spi/CentralApiClientTest.java`

**Interfaces:**
- Consumes: nothing from other tasks (this is a leaf component).
- Produces (Task 3 depends on these exact names/signatures):
  - `enum RedeemOutcome { LINKED, ALREADY_LINKED, MERGED, INVALID_CODE, EXPIRED_CODE, ERROR }`
  - `class CentralApiClient` with:
    - `public CentralApiClient()` — no-arg constructor, reads all four env vars from Global Constraints, throws `IllegalStateException` if any is unset/blank.
    - `public RedeemOutcome redeem(String keycloakUserId, String code)` — **never throws**; every failure path returns `RedeemOutcome.ERROR` (or `INVALID_CODE`/`EXPIRED_CODE` where applicable). This is the only method Task 3 calls.

- [ ] **Step 1: Write `RedeemOutcome.java`**

```java
package net.eliferpg.keycloak.spi;

public enum RedeemOutcome {
    LINKED,
    ALREADY_LINKED,
    MERGED,
    INVALID_CODE,
    EXPIRED_CODE,
    ERROR
}
```

- [ ] **Step 2: Write `CentralApiClientException.java`**

```java
package net.eliferpg.keycloak.spi;

class CentralApiClientException extends Exception {

    enum Kind { INVALID_CODE, EXPIRED_CODE, NETWORK_ERROR }

    private final Kind kind;

    CentralApiClientException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    CentralApiClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    Kind getKind() {
        return kind;
    }
}
```

Package-private on purpose: it's an internal signaling type between `CentralApiClient`'s own HTTP helpers and its public `redeem()` method — nothing outside this package should catch it, since `redeem()` never lets it escape.

- [ ] **Step 3: Write the failing tests first — `CentralApiClientTest.java`**

Uses only JDK-provided `com.sun.net.httpserver.HttpServer` (no extra test dependency) to stand up two local stub endpoints: a fake Keycloak token endpoint and a fake Central API redeem endpoint.

```java
package net.eliferpg.keycloak.spi;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CentralApiClientTest {

    private HttpServer tokenServer;
    private HttpServer apiServer;
    private final AtomicInteger tokenRequestCount = new AtomicInteger();
    private volatile long tokenExpiresIn = 300;
    private volatile int redeemStatus = 200;
    private volatile String redeemBody = "{\"outcome\":\"linked\"}";
    private volatile long redeemDelayMillis = 0;

    private String tokenUrl;
    private String centralApiBaseUrl;

    @BeforeEach
    void setUp() throws IOException {
        tokenRequestCount.set(0);
        tokenServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        tokenServer.createContext("/token", exchange -> {
            tokenRequestCount.incrementAndGet();
            byte[] body = ("{\"access_token\":\"stub-token\",\"expires_in\":" + tokenExpiresIn + "}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        tokenServer.start();
        tokenUrl = "http://localhost:" + tokenServer.getAddress().getPort() + "/token";

        apiServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        apiServer.createContext("/api/accounts/links/redeem", exchange -> {
            if (redeemDelayMillis > 0) {
                try {
                    Thread.sleep(redeemDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = redeemBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(redeemStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        apiServer.start();
        centralApiBaseUrl = "http://localhost:" + apiServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        tokenServer.stop(0);
        apiServer.stop(0);
    }

    private CentralApiClient client() {
        return new CentralApiClient(centralApiBaseUrl, tokenUrl, "client-id", "client-secret", Duration.ofSeconds(5));
    }

    private CentralApiClient clientWithTimeout(Duration timeout) {
        return new CentralApiClient(centralApiBaseUrl, tokenUrl, "client-id", "client-secret", timeout);
    }

    @Test
    void redeem_linked_returnsLinked() {
        redeemBody = "{\"outcome\":\"linked\"}";
        assertEquals(RedeemOutcome.LINKED, client().redeem("kc-user-1", "ABCD1234"));
    }

    @Test
    void redeem_alreadyLinked_returnsAlreadyLinked() {
        redeemBody = "{\"outcome\":\"already-linked\"}";
        assertEquals(RedeemOutcome.ALREADY_LINKED, client().redeem("kc-user-1", "ABCD1234"));
    }

    @Test
    void redeem_merged_returnsMerged() {
        redeemBody = "{\"outcome\":\"merged\"}";
        assertEquals(RedeemOutcome.MERGED, client().redeem("kc-user-1", "ABCD1234"));
    }

    @Test
    void redeem_unknownCode_returns404_mapsToInvalidCode() {
        redeemStatus = 404;
        redeemBody = "{\"title\":\"unknown code\"}";
        assertEquals(RedeemOutcome.INVALID_CODE, client().redeem("kc-user-1", "NOPE"));
    }

    @Test
    void redeem_expiredCode_returns410_mapsToExpiredCode() {
        redeemStatus = 410;
        redeemBody = "{\"title\":\"expired code\"}";
        assertEquals(RedeemOutcome.EXPIRED_CODE, client().redeem("kc-user-1", "STALE"));
    }

    @Test
    void redeem_serverError_returnsError() {
        redeemStatus = 500;
        redeemBody = "{}";
        assertEquals(RedeemOutcome.ERROR, client().redeem("kc-user-1", "ABCD1234"));
    }

    @Test
    void redeem_networkError_returnsError() {
        CentralApiClient client = new CentralApiClient(
            "http://localhost:1", tokenUrl, "client-id", "client-secret", Duration.ofSeconds(5));
        assertEquals(RedeemOutcome.ERROR, client.redeem("kc-user-1", "ABCD1234"));
    }

    @Test
    void redeem_timeout_returnsError() {
        redeemDelayMillis = 2000;
        redeemBody = "{\"outcome\":\"linked\"}";
        assertEquals(RedeemOutcome.ERROR, clientWithTimeout(Duration.ofMillis(200)).redeem("kc-user-1", "ABCD1234"));
    }

    @Test
    void redeem_tokenFetchFailure_returnsError() {
        CentralApiClient client = new CentralApiClient(
            centralApiBaseUrl, "http://localhost:1/token", "client-id", "client-secret", Duration.ofSeconds(5));
        assertEquals(RedeemOutcome.ERROR, client.redeem("kc-user-1", "ABCD1234"));
    }

    @Test
    void redeem_tokenCaching_secondCallDoesNotRefetchToken() {
        tokenExpiresIn = 300;
        CentralApiClient client = client();
        client.redeem("kc-user-1", "ABCD1234");
        client.redeem("kc-user-1", "ABCD1234");
        assertEquals(1, tokenRequestCount.get());
    }

    @Test
    void redeem_tokenNearExpiry_refetchesToken() {
        tokenExpiresIn = 1;
        CentralApiClient client = client();
        client.redeem("kc-user-1", "ABCD1234");
        client.redeem("kc-user-1", "ABCD1234");
        assertEquals(2, tokenRequestCount.get());
    }

    @Test
    void redeem_sendsBearerTokenAndJsonBody() throws IOException {
        apiServer.removeContext("/api/accounts/links/redeem");
        java.util.concurrent.atomic.AtomicReference<String> capturedAuth = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        apiServer.createContext("/api/accounts/links/redeem", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"outcome\":\"linked\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        client().redeem("kc-user-42", "SECRETCODE");

        assertEquals("Bearer stub-token", capturedAuth.get());
        assertEquals(true, capturedBody.get().contains("\"keycloakUserId\":\"kc-user-42\""));
        assertEquals(true, capturedBody.get().contains("\"code\":\"SECRETCODE\""));
    }
}
```

- [ ] **Step 4: Run the tests, confirm they fail with "cannot find symbol: class CentralApiClient"**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=CentralApiClientTest
```
Expected: compile error, `CentralApiClient` does not exist yet.

- [ ] **Step 5: Write `CentralApiClient.java`**

```java
package net.eliferpg.keycloak.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class CentralApiClient {

    private static final Logger LOGGER = Logger.getLogger(CentralApiClient.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final long TOKEN_EXPIRY_MARGIN_SECONDS = 30;

    private final String centralApiBaseUrl;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public CentralApiClient() {
        this(
            requireEnv("ELIFERPG_CENTRAL_API_BASE_URL"),
            requireEnv("ELIFERPG_KEYCLOAK_TOKEN_URL"),
            requireEnv("ELIFERPG_KEYCLOAK_SPI_CLIENT_ID"),
            requireEnv("ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET"),
            DEFAULT_TIMEOUT
        );
    }

    CentralApiClient(String centralApiBaseUrl, String tokenUrl, String clientId, String clientSecret, Duration timeout) {
        this.centralApiBaseUrl = stripTrailingSlash(centralApiBaseUrl);
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public RedeemOutcome redeem(String keycloakUserId, String code) {
        try {
            String token = getValidToken();
            return doRedeem(token, keycloakUserId, code);
        } catch (CentralApiClientException e) {
            LOGGER.warnf(e, "Central API redeem failed (%s)", e.getKind());
            return switch (e.getKind()) {
                case INVALID_CODE -> RedeemOutcome.INVALID_CODE;
                case EXPIRED_CODE -> RedeemOutcome.EXPIRED_CODE;
                case NETWORK_ERROR -> RedeemOutcome.ERROR;
            };
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected error during Central API redeem", e);
            return RedeemOutcome.ERROR;
        }
    }

    private synchronized String getValidToken() throws CentralApiClientException {
        if (cachedToken == null || Instant.now().isAfter(tokenExpiresAt.minusSeconds(TOKEN_EXPIRY_MARGIN_SECONDS))) {
            fetchAndCacheToken();
        }
        return cachedToken;
    }

    private void fetchAndCacheToken() throws CentralApiClientException {
        String form = "grant_type=client_credentials"
            + "&client_id=" + encode(clientId)
            + "&client_secret=" + encode(clientSecret);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();

        HttpResponse<String> response = send(request);

        if (response.statusCode() != 200) {
            throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                "Token endpoint returned HTTP " + response.statusCode());
        }

        try {
            JsonNode body = objectMapper.readTree(response.body());
            String accessToken = body.path("access_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(0);
            if (accessToken == null || accessToken.isBlank() || expiresIn <= 0) {
                throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                    "Token response missing access_token/expires_in");
            }
            cachedToken = accessToken;
            tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
        } catch (IOException e) {
            throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                "Failed to parse token response", e);
        }
    }

    private RedeemOutcome doRedeem(String token, String keycloakUserId, String code) throws CentralApiClientException {
        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(Map.of(
                "keycloakUserId", keycloakUserId,
                "code", code
            ));
        } catch (IOException e) {
            throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                "Failed to serialize redeem request", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(centralApiBaseUrl + "/api/accounts/links/redeem"))
            .timeout(timeout)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        HttpResponse<String> response = send(request);

        return switch (response.statusCode()) {
            case 200 -> parseOutcome(response.body());
            case 404 -> throw new CentralApiClientException(CentralApiClientException.Kind.INVALID_CODE, "Unknown code");
            case 410 -> throw new CentralApiClientException(CentralApiClientException.Kind.EXPIRED_CODE, "Expired code");
            default -> throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                "Central API returned HTTP " + response.statusCode());
        };
    }

    private RedeemOutcome parseOutcome(String responseBody) throws CentralApiClientException {
        try {
            String outcome = objectMapper.readTree(responseBody).path("outcome").asText("");
            return switch (outcome) {
                case "linked" -> RedeemOutcome.LINKED;
                case "already-linked" -> RedeemOutcome.ALREADY_LINKED;
                case "merged" -> RedeemOutcome.MERGED;
                default -> throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                    "Unrecognized outcome: " + outcome);
            };
        } catch (IOException e) {
            throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                "Failed to parse redeem response", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws CentralApiClientException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                "HTTP call to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CentralApiClientException(CentralApiClientException.Kind.NETWORK_ERROR,
                "HTTP call to " + request.uri() + " was interrupted", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 6: Run the tests again, confirm all pass**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=CentralApiClientTest
```
Expected: `Tests run: 12, Failures: 0, Errors: 0`. The `redeem_timeout_returnsError` test takes ~200ms; the whole class should finish in a few seconds.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/eliferpg/keycloak/spi/RedeemOutcome.java \
        src/main/java/net/eliferpg/keycloak/spi/CentralApiClientException.java \
        src/main/java/net/eliferpg/keycloak/spi/CentralApiClient.java \
        src/test/java/net/eliferpg/keycloak/spi/CentralApiClientTest.java
git commit -m "Add CentralApiClient with client-credentials token caching and redeem contract"
```

---

### Task 3: `LinkGameAccountRequiredAction` + Factory + FTL template

**Files:**
- Create: `src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionFactory.java`
- Create: `src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredAction.java`
- Create: `src/main/resources/META-INF/services/org.keycloak.authentication.RequiredActionFactory`
- Create: `src/main/resources/theme/eliferpg/login/theme.properties`
- Create: `src/main/resources/theme/eliferpg/login/link-game-account.ftl`
- Create: `src/main/resources/theme/eliferpg/login/messages/messages_en.properties`
- Test: `src/test/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionTest.java`
- Delete: `src/main/java/net/eliferpg/keycloak/spi/.gitkeep` (real files now live in that directory)

**Interfaces:**
- Consumes: `CentralApiClient` (Task 2) — its no-arg constructor and `redeem(String, String)`; `RedeemOutcome` enum values.
- Produces: `LinkGameAccountRequiredActionFactory.PROVIDER_ID = "link-game-account"` (this exact string is what the eliferpg realm config will reference — do not change it later without updating this constant's javadoc note about why).

These interfaces (verified via `javap` against the actual `keycloak-server-spi`/`keycloak-server-spi-private` 26.0.8 jars — use these exact signatures, do not guess alternates):

```java
// org.keycloak.provider.ProviderFactory<T extends Provider>
T create(KeycloakSession session);
void init(Config.Scope config);
void postInit(KeycloakSessionFactory factory);
void close();
String getId();

// org.keycloak.authentication.RequiredActionFactory extends ProviderFactory<RequiredActionProvider>
String getDisplayText();

// org.keycloak.authentication.RequiredActionProvider extends Provider
void evaluateTriggers(RequiredActionContext context);
void requiredActionChallenge(RequiredActionContext context);
void processAction(RequiredActionContext context);
void close();

// org.keycloak.authentication.RequiredActionContext (relevant subset)
UserModel getUser();
LoginFormsProvider form();
HttpRequest getHttpRequest();          // org.keycloak.http.HttpRequest
void challenge(jakarta.ws.rs.core.Response response);
void success();

// org.keycloak.http.HttpRequest
jakarta.ws.rs.core.MultivaluedMap<String, String> getDecodedFormParameters();

// org.keycloak.forms.login.LoginFormsProvider (relevant subset)
jakarta.ws.rs.core.Response createForm(String templateName);
LoginFormsProvider setError(String messageKey, Object... params);

// org.keycloak.models.UserModel (relevant subset)
String getId();
String getFirstAttribute(String name);
void setSingleAttribute(String name, String value);
void addRequiredAction(String actionId);
```

- [ ] **Step 1: Write the FTL template — `link-game-account.ftl`**

```ftl
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("linkGameAccountTitle")}
    <#elseif section = "form">
        <form id="kc-link-game-account-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="code" class="${properties.kcLabelClass!}">${msg("linkGameAccountCodeLabel")}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <input type="text" id="code" name="code" class="${properties.kcInputClass!}"
                           autofocus autocomplete="off" maxlength="32" />
                </div>
            </div>
            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                           type="submit" value="${msg("doSubmit")}" />
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
```

- [ ] **Step 2: Write `theme.properties`**

```properties
parent=keycloak
```

- [ ] **Step 3: Write `messages_en.properties`**

```properties
linkGameAccountTitle=Link Game Account
linkGameAccountCodeLabel=Enter the code shown in-game or on your account page
linkGameAccountInvalidInput=Please enter a valid code.
linkGameAccountInvalidCode=That code isn't recognized. Double-check it and try again.
linkGameAccountExpiredCode=That code has expired. Generate a new one and try again.
linkGameAccountError=Something went wrong linking your account. Please try again in a moment.
```

- [ ] **Step 4: Write the factory service registration file**

`src/main/resources/META-INF/services/org.keycloak.authentication.RequiredActionFactory` (file content is exactly one line, the fully-qualified factory class name):
```
net.eliferpg.keycloak.spi.LinkGameAccountRequiredActionFactory
```

- [ ] **Step 5: Write the failing tests first — `LinkGameAccountRequiredActionTest.java`**

```java
package net.eliferpg.keycloak.spi;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.UserModel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LinkGameAccountRequiredActionTest {

    private CentralApiClient centralApiClient;
    private RequiredActionContext context;
    private UserModel user;
    private HttpRequest httpRequest;
    private LoginFormsProvider formsProvider;
    private LinkGameAccountRequiredAction action;

    @BeforeEach
    void setUp() {
        centralApiClient = mock(CentralApiClient.class);
        context = mock(RequiredActionContext.class);
        user = mock(UserModel.class);
        httpRequest = mock(HttpRequest.class);
        formsProvider = mock(LoginFormsProvider.class, org.mockito.Answers.RETURNS_SELF);

        when(context.getUser()).thenReturn(user);
        when(context.getHttpRequest()).thenReturn(httpRequest);
        when(context.form()).thenReturn(formsProvider);
        when(formsProvider.createForm(anyString())).thenReturn(mock(Response.class));
        when(user.getId()).thenReturn("kc-user-id");

        action = new LinkGameAccountRequiredAction(centralApiClient);
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
    void processAction_linked_setsAttributeAndSucceeds() {
        submitCode("ABCD1234");
        when(centralApiClient.redeem("kc-user-id", "ABCD1234")).thenReturn(RedeemOutcome.LINKED);

        action.processAction(context);

        verify(user).setSingleAttribute("gameAccountLinked", "true");
        verify(context).success();
        verify(context, never()).challenge(any());
    }

    @Test
    void processAction_alreadyLinked_succeeds() {
        submitCode("ABCD1234");
        when(centralApiClient.redeem(anyString(), anyString())).thenReturn(RedeemOutcome.ALREADY_LINKED);
        action.processAction(context);
        verify(context).success();
    }

    @Test
    void processAction_merged_succeeds() {
        submitCode("ABCD1234");
        when(centralApiClient.redeem(anyString(), anyString())).thenReturn(RedeemOutcome.MERGED);
        action.processAction(context);
        verify(context).success();
    }

    @Test
    void processAction_invalidCode_reChallengesWithSpecificError() {
        submitCode("ABCD1234");
        when(centralApiClient.redeem(anyString(), anyString())).thenReturn(RedeemOutcome.INVALID_CODE);

        action.processAction(context);

        verify(formsProvider).setError("linkGameAccountInvalidCode");
        verify(context).challenge(any());
        verify(context, never()).success();
    }

    @Test
    void processAction_expiredCode_reChallengesWithDistinctError() {
        submitCode("ABCD1234");
        when(centralApiClient.redeem(anyString(), anyString())).thenReturn(RedeemOutcome.EXPIRED_CODE);

        action.processAction(context);

        verify(formsProvider).setError("linkGameAccountExpiredCode");
        verify(context, never()).success();
    }

    @Test
    void processAction_networkError_reChallengesWithGenericError() {
        submitCode("ABCD1234");
        when(centralApiClient.redeem(anyString(), anyString())).thenReturn(RedeemOutcome.ERROR);

        action.processAction(context);

        verify(formsProvider).setError("linkGameAccountError");
        verify(context, never()).success();
    }

    @Test
    void processAction_centralApiClientThrows_doesNotPropagate_reChallenges() {
        submitCode("ABCD1234");
        when(centralApiClient.redeem(anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> action.processAction(context));

        verify(formsProvider).setError("linkGameAccountError");
        verify(context, never()).success();
    }

    @Test
    void processAction_blankCode_rejectedWithoutCallingCentralApi() {
        submitCode("");
        action.processAction(context);
        verifyNoInteractions(centralApiClient);
        verify(formsProvider).setError("linkGameAccountInvalidInput");
    }

    @Test
    void processAction_missingCode_rejectedWithoutCallingCentralApi() {
        submitCode(null);
        action.processAction(context);
        verifyNoInteractions(centralApiClient);
        verify(formsProvider).setError("linkGameAccountInvalidInput");
    }

    @Test
    void processAction_overlongCode_rejectedWithoutCallingCentralApi() {
        submitCode("A".repeat(33));
        action.processAction(context);
        verifyNoInteractions(centralApiClient);
        verify(formsProvider).setError("linkGameAccountInvalidInput");
    }

    @Test
    void factory_returnsExactProviderId() {
        LinkGameAccountRequiredActionFactory factory = new LinkGameAccountRequiredActionFactory();
        org.junit.jupiter.api.Assertions.assertEquals("link-game-account", factory.getId());
    }
}
```

- [ ] **Step 6: Run the tests, confirm they fail with compile errors** (classes don't exist yet)

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkGameAccountRequiredActionTest
```

- [ ] **Step 7: Write `LinkGameAccountRequiredAction.java`**

```java
package net.eliferpg.keycloak.spi;

import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;

import java.util.regex.Pattern;

public class LinkGameAccountRequiredAction implements RequiredActionProvider {

    static final String LINKED_ATTRIBUTE = "gameAccountLinked";
    static final String FORM_TEMPLATE = "link-game-account.ftl";
    static final String CODE_PARAM = "code";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,32}$");

    private final CentralApiClient centralApiClient;

    LinkGameAccountRequiredAction(CentralApiClient centralApiClient) {
        this.centralApiClient = centralApiClient;
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

        RedeemOutcome outcome;
        try {
            outcome = centralApiClient.redeem(context.getUser().getId(), code.trim());
        } catch (RuntimeException e) {
            outcome = RedeemOutcome.ERROR;
        }

        switch (outcome) {
            case LINKED, ALREADY_LINKED, MERGED -> {
                context.getUser().setSingleAttribute(LINKED_ATTRIBUTE, "true");
                context.success();
            }
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
        // no-op: holds no resources
    }
}
```

- [ ] **Step 8: Write `LinkGameAccountRequiredActionFactory.java`**

```java
package net.eliferpg.keycloak.spi;

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

    private CentralApiClient centralApiClient;

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new LinkGameAccountRequiredAction(centralApiClient);
    }

    @Override
    public void init(Config.Scope config) {
        centralApiClient = new CentralApiClient();
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

Note: `factory_returnsExactProviderId` in the test instantiates the factory directly and calls `getId()` without calling `init()` first — that's fine, `getId()` doesn't touch `centralApiClient`. Do not call `create()` in a test without first calling `init()` (it would NPE on the env-var read, or throw `IllegalStateException` if env vars are unset) — the other tests correctly go through the `LinkGameAccountRequiredAction` constructor directly instead.

- [ ] **Step 9: Remove the placeholder**

```bash
rm src/main/java/net/eliferpg/keycloak/spi/.gitkeep
```

- [ ] **Step 10: Run the tests again, confirm all pass**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=LinkGameAccountRequiredActionTest
```
Expected: `Tests run: 14, Failures: 0, Errors: 0`.

- [ ] **Step 11: Run the full test suite (both test classes) to confirm nothing regressed**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test
```
Expected: `Tests run: 26, Failures: 0, Errors: 0` (12 + 14 from Task 2 and Task 3).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredAction.java \
        src/main/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionFactory.java \
        src/main/resources/META-INF/services/org.keycloak.authentication.RequiredActionFactory \
        src/main/resources/theme/eliferpg \
        src/test/java/net/eliferpg/keycloak/spi/LinkGameAccountRequiredActionTest.java
git rm src/main/java/net/eliferpg/keycloak/spi/.gitkeep
git commit -m "Implement link-game-account RequiredActionProvider, factory, and FTL form"
```

---

### Task 4: Dockerfile & Image Packaging

**Files:**
- Create: `Dockerfile`
- Modify: none

**Interfaces:**
- Consumes: `target/keycloak-eliferpg-spi-0.1.0-SNAPSHOT.jar` (built by `mvn package`), `src/main/resources/theme/eliferpg` (Task 3).
- Produces: a runnable Docker image tagged `eliferpg/keycloak-spi:dev` for use in Task 5.

- [ ] **Step 1: Write `Dockerfile`**

```dockerfile
FROM quay.io/keycloak/keycloak:26.0
COPY target/keycloak-eliferpg-spi-*.jar /opt/keycloak/providers/
COPY src/main/resources/theme/eliferpg /opt/keycloak/themes/eliferpg
```

- [ ] **Step 2: Build the JAR**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B clean package
```
Expected: `BUILD SUCCESS`, `target/keycloak-eliferpg-spi-0.1.0-SNAPSHOT.jar` exists. Confirm it's a *thin* jar (only our classes/resources, since all Keycloak deps are `provided`):
```bash
docker run --rm --entrypoint jar maven:3.9-eclipse-temurin-21 tf /dev/null 2>/dev/null; \
docker run --rm -v "$(pwd)":/app --entrypoint jar maven:3.9-eclipse-temurin-21 tf /app/target/keycloak-eliferpg-spi-0.1.0-SNAPSHOT.jar
```
Expected: only `net/eliferpg/...`, `META-INF/...` entries — no `org/keycloak/...` or `com/fasterxml/...` classes bundled in.

- [ ] **Step 3: Build the Docker image**

```bash
docker build -t eliferpg/keycloak-spi:dev .
```
Expected: `BUILD SUCCESS`-equivalent (`docker build` exits 0).

- [ ] **Step 4: Confirm the provider loads without error**

```bash
docker rm -f kc-provider-check >/dev/null 2>&1
docker run -d --name kc-provider-check -p 18081:8080 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e ELIFERPG_CENTRAL_API_BASE_URL=http://localhost:9/unused \
  -e ELIFERPG_KEYCLOAK_TOKEN_URL=http://localhost:8080/realms/master/protocol/openid-connect/token \
  -e ELIFERPG_KEYCLOAK_SPI_CLIENT_ID=unused \
  -e ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET=unused \
  eliferpg/keycloak-spi:dev start-dev
sleep 15
docker logs kc-provider-check 2>&1 | grep -iE "error|exception|link-game-account" | head -30
```
Expected: no `ERROR`/`Exception` lines caused by our provider (some unrelated startup warnings from Keycloak itself are normal — read them, don't just grep-and-panic). The env vars here are dummy values — Task 4 only needs to prove `init()` doesn't throw at startup (the vars are set and non-blank, so `CentralApiClient`'s constructor succeeds; it never makes a network call during `init()`).

- [ ] **Step 5: Confirm the required action is registered via the Admin REST API**

```bash
TOKEN=$(curl -s -X POST http://localhost:18081/realms/master/protocol/openid-connect/token \
  -d "grant_type=password&client_id=admin-cli&username=admin&password=admin" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -s http://localhost:18081/admin/realms/master/authentication/unregistered-required-actions \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```
Expected: an entry in the returned JSON array with `"providerId": "link-game-account"` and `"name": "Link Game Account"` — this is Keycloak's list of required-action providers that are registered (ServiceLoader-discovered) but not yet enabled in this realm. Its presence proves the JAR was picked up and the `META-INF/services` registration worked.

```bash
docker rm -f kc-provider-check >/dev/null 2>&1
```

- [ ] **Step 6: Commit**

```bash
git add Dockerfile
git commit -m "Add Dockerfile packaging the SPI JAR and theme onto the Keycloak 26.0 base image"
```

---

### Task 5: Stub Central API + End-to-End Integration Walkthrough

**Files:**
- Create: `src/test/java/net/eliferpg/keycloak/spi/stub/StubCentralApiServer.java` (a standalone `main()`-based helper, NOT packaged into the shipped JAR — it lives under `src/test` so `mvn package` never bundles it)
- Create: `docker-compose.integration-test.yml`
- Create: `scripts/integration-test.sh`
- Create: `TESTING.md`

**Interfaces:**
- Consumes: `eliferpg/keycloak-spi:dev` image (Task 4).
- Produces: `TESTING.md`, documenting exact steps + actual captured output from a real run — this satisfies the plan's "Manual integration walkthrough performed and documented" deliverable.

This task validates the whole system end-to-end. Two things were verified empirically while writing this plan (do not re-derive, just use them):

1. **A pending required action makes Resource Owner Password (direct grant) fail** with `{"error":"invalid_grant","error_description":"Account is not fully set up"}` — so this walkthrough MUST drive the real authorization code flow.
2. **The login form's `action` attribute is a complete, self-contained URL** (embeds `session_code`, `execution`, `client_id`, `tab_id`, `client_data` as query params) — there are no hidden `<input>` fields to extract. The scraping technique is: GET the login page, regex out `<form[^>]*action="([^"]*)"`, HTML-unescape `&amp;` → `&`, POST the field(s) there with a cookie jar, follow redirects, repeat for the next page.

- [ ] **Step 1: Write the stub Central API server**

No JSON library dependency — the request bodies we send are fixed-shape (`{"keycloakUserId":"...","code":"..."}`), so a substring check on `code` is enough; this file is a test/dev tool, not shipped.

```java
package net.eliferpg.keycloak.spi.stub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Throwaway stand-in for eliferpg's Central API `/api/accounts/links/redeem`
 * endpoint, for manual/integration testing only (see TESTING.md). Routes on
 * the submitted code's prefix:
 *   LINKED*  -> 200 {"outcome":"linked"}
 *   ALREADY* -> 200 {"outcome":"already-linked"}
 *   MERGED*  -> 200 {"outcome":"merged"}
 *   EXPIRED* -> 410
 *   SLOW*    -> sleeps 10s then 200 linked (exercises the 5s client timeout)
 *   anything else -> 404
 */
public class StubCentralApiServer {

    private static final Pattern CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]*)\"");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/api/accounts/links/redeem", StubCentralApiServer::handle);
        server.start();
        System.out.println("StubCentralApiServer listening on :" + port);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        System.out.println("[stub] Authorization=" + authHeader + " body=" + requestBody);

        Matcher matcher = CODE_PATTERN.matcher(requestBody);
        String code = matcher.find() ? matcher.group(1) : "";

        int status;
        String body;
        if (code.startsWith("LINKED")) {
            status = 200;
            body = "{\"outcome\":\"linked\"}";
        } else if (code.startsWith("ALREADY")) {
            status = 200;
            body = "{\"outcome\":\"already-linked\"}";
        } else if (code.startsWith("MERGED")) {
            status = 200;
            body = "{\"outcome\":\"merged\"}";
        } else if (code.startsWith("EXPIRED")) {
            status = 410;
            body = "{\"title\":\"expired\"}";
        } else if (code.startsWith("SLOW")) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            status = 200;
            body = "{\"outcome\":\"linked\"}";
        } else {
            status = 404;
            body = "{\"title\":\"unknown code\"}";
        }

        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
```

- [ ] **Step 2: Write `docker-compose.integration-test.yml`**

The Keycloak container calls back into itself for its own token endpoint (`ELIFERPG_KEYCLOAK_TOKEN_URL=http://localhost:8080/...`), and reaches the stub via the compose network (`http://stub-central-api:8081`).

```yaml
services:
  keycloak:
    image: eliferpg/keycloak-spi:dev
    ports:
      - "18082:8080"
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      ELIFERPG_CENTRAL_API_BASE_URL: http://stub-central-api:8081
      ELIFERPG_KEYCLOAK_TOKEN_URL: http://localhost:8080/realms/spi-integration-test/protocol/openid-connect/token
      ELIFERPG_KEYCLOAK_SPI_CLIENT_ID: keycloak-spi
      ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET: test-secret-value
    command: start-dev
    depends_on:
      - stub-central-api

  stub-central-api:
    image: maven:3.9-eclipse-temurin-21
    working_dir: /app
    volumes:
      - .:/app
    command: >
      sh -c "javac -d /tmp/out src/test/java/net/eliferpg/keycloak/spi/stub/StubCentralApiServer.java &&
             java -cp /tmp/out net.eliferpg.keycloak.spi.stub.StubCentralApiServer 8081"
```

- [ ] **Step 3: Write `scripts/integration-test.sh`**

This brings the stack up, bootstraps a realm/client/user via the Admin REST API, drives the full browser-style login flow through the `link-game-account` required action for four cases (success, invalid, expired, timeout), and tears down. It's meant to be run once by hand and its real output pasted into `TESTING.md` — not wired into CI (no CI exists yet in this repo, per the plan's own non-goal).

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE="http://localhost:18082"
REALM="spi-integration-test"
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT

wait_for_keycloak() {
  for i in $(seq 1 60); do
    if curl -sf "$BASE/realms/master" >/dev/null 2>&1; then
      echo "Keycloak ready after ${i}s"
      return 0
    fi
    sleep 1
  done
  echo "Keycloak did not become ready in time" >&2
  exit 1
}

admin_token() {
  curl -s -X POST "$BASE/realms/master/protocol/openid-connect/token" \
    -d "grant_type=password&client_id=admin-cli&username=admin&password=admin" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
}

extract_form_action() {
  # $1 = html file. Prints the first <form ...action="..."> URL, unescaped.
  grep -oE '<form[^>]*action="[^"]*"' "$1" | head -1 \
    | sed -E 's/.*action="([^"]*)"/\1/' | sed 's/&amp;/\&/g'
}

echo "=== docker compose up ==="
docker compose -f docker-compose.integration-test.yml up -d --build
wait_for_keycloak

echo "=== bootstrap realm/client/user via Admin API ==="
TOKEN=$(admin_token)
AUTH="Authorization: Bearer $TOKEN"

curl -s -X POST "$BASE/admin/realms" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"realm\":\"$REALM\",\"enabled\":true}"

curl -s -X PUT "$BASE/admin/realms/$REALM/authentication/required-actions/link-game-account" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"alias":"link-game-account","name":"Link Game Account","providerId":"link-game-account","enabled":true,"defaultAction":false}'

# service-account client for the SPI's own client-credentials calls (matches
# ELIFERPG_KEYCLOAK_SPI_CLIENT_ID/SECRET in docker-compose.integration-test.yml)
curl -s -X POST "$BASE/admin/realms/$REALM/clients" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"keycloak-spi","secret":"test-secret-value","serviceAccountsEnabled":true,"publicClient":false,"standardFlowEnabled":false}'

# browser client the human/test player authenticates through
curl -s -X POST "$BASE/admin/realms/$REALM/clients" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"test-client","publicClient":true,"standardFlowEnabled":true,"redirectUris":["http://localhost/*"]}'

run_case() {
  local label="$1" username="$2" code="$3"
  echo "=== case: $label (code=$code) ==="
  rm -f "$COOKIES"

  # test user with no pending required actions other than ours, and profile
  # fields set so Keycloak's default VERIFY_PROFILE required action doesn't
  # also fire (if it still fires, disable it for $REALM via the Admin API's
  # PUT /authentication/required-actions/VERIFY_PROFILE {"enabled":false}
  # and re-run — this is a realm-config wrinkle, not a defect in our provider).
  curl -s -X POST "$BASE/admin/realms/$REALM/users" -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"username\":\"$username\",\"enabled\":true,\"emailVerified\":true,\"email\":\"$username@example.test\",\"firstName\":\"Test\",\"lastName\":\"User\",\"credentials\":[{\"type\":\"password\",\"value\":\"pass1234\",\"temporary\":false}]}"

  curl -s -c "$COOKIES" -L \
    "$BASE/realms/$REALM/protocol/openid-connect/auth?client_id=test-client&response_type=code&scope=openid&redirect_uri=http://localhost/callback" \
    -o /tmp/step1.html -w "GET /auth -> %{http_code}\n"

  local login_action
  login_action=$(extract_form_action /tmp/step1.html)
  echo "login form action: $login_action"

  curl -s -c "$COOKIES" -b "$COOKIES" -L "$login_action" \
    --data-urlencode "username=$username" --data-urlencode "password=pass1234" \
    -o /tmp/step2.html -w "POST login -> %{http_code} url=%{url_effective}\n"

  local challenge_action
  challenge_action=$(extract_form_action /tmp/step2.html)
  echo "required-action form action: $challenge_action"

  curl -s -c "$COOKIES" -b "$COOKIES" -L "$challenge_action" \
    --data-urlencode "code=$code" \
    -o /tmp/step3.html -w "POST code -> %{http_code} url=%{url_effective}\n"

  if grep -q 'code=' <<<"$(cat /tmp/step3.html | grep -o 'url_effective.*' || true)"; then :; fi
  echo "--- result page excerpt ---"
  grep -oE 'kc-feedback-text|linkGameAccount[A-Za-z]*|"code":"[^"]*"' /tmp/step3.html | sort -u || true
  echo
}

run_case "success"       "player-success" "LINKED001"
run_case "invalid code"  "player-invalid" "GARBAGE99"
run_case "expired code"  "player-expired" "EXPIRED01"
run_case "slow/timeout"  "player-slow"    "SLOW00001"

echo "=== docker compose down ==="
docker compose -f docker-compose.integration-test.yml down -v
```

- [ ] **Step 4: Run it for real**

```bash
chmod +x scripts/integration-test.sh
./scripts/integration-test.sh 2>&1 | tee /tmp/integration-test-output.txt
```

Read the actual output. Confirm, for each case:
- **success**: the final page is NOT the `link-game-account` form again (no `linkGameAccountInvalidCode`/`linkGameAccountExpiredCode`/`linkGameAccountError` message keys present) — the flow moved past our required action.
- **invalid code**: the result page contains `linkGameAccountInvalidCode`.
- **expired code**: the result page contains `linkGameAccountExpiredCode`.
- **slow/timeout**: takes noticeably longer (our 5s HTTP timeout should fire before the stub's 10s sleep) and the result page contains `linkGameAccountError`, not a hung `curl` or a Keycloak 500.

If VERIFY_PROFILE or another built-in required action intercepts the flow before reaching ours (visible as an unfamiliar form field set in `/tmp/step2.html` that isn't `code`), apply the fallback noted in the script's comment (disable `VERIFY_PROFILE` for the test realm) and re-run — do not treat that as a defect in this SPI.

- [ ] **Step 5: Write `TESTING.md`**, pasting the **actual** captured output from Step 4 (not a hypothetical transcript), plus:
  - Prerequisites (`docker`, `docker compose`).
  - How to run: `./scripts/integration-test.sh`.
  - What each of the four cases proves.
  - The two empirically-verified facts from this task's intro (ROPC + required actions, and the self-contained `url.loginAction`) as background for future maintainers who might otherwise try to "simplify" this into a ROPC-based test and hit the same wall.
  - A note that `docker-compose.integration-test.yml` and `scripts/integration-test.sh` are dev/test-only tooling for *this* repo, separate from eliferpg's own `compose.yml` (per spec §4's non-goal — this repo never touches that file).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/net/eliferpg/keycloak/spi/stub/StubCentralApiServer.java \
        docker-compose.integration-test.yml scripts/integration-test.sh TESTING.md
git commit -m "Add stub Central API and scripted end-to-end integration walkthrough"
```

---

### Task 6: README + CI Workflow + Final Deliverables Check

**Files:**
- Create: `README.md`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: nothing new — this task documents/wires up what Tasks 1-5 built.

- [ ] **Step 1: Write `README.md`**, covering at minimum:
  - What this project is (one paragraph, point back to `IMPLEMENTATION_PLAN.md` for the full spec).
  - **The provider id: `link-game-account`** — call this out prominently since `eliferpg-realm.json` (a different repo) will reference it verbatim when wiring the authentication flow.
  - The four env vars from Global Constraints (`ELIFERPG_CENTRAL_API_BASE_URL`, `ELIFERPG_KEYCLOAK_SPI_CLIENT_ID`, `ELIFERPG_KEYCLOAK_SPI_CLIENT_SECRET`, `ELIFERPG_KEYCLOAK_TOKEN_URL`) — what each is for, and that the process fails fast at Keycloak startup (`init()`) if any is unset.
  - How to build: `docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn clean package` then `docker build -t eliferpg/keycloak-spi:<tag> .`
  - How to run the integration walkthrough: point at `TESTING.md`.
  - **Versioning & compatibility risk note** (matches spec §11 almost verbatim): `RequiredActionProvider` is not cross-version-guaranteed Keycloak SPI surface. This provider is pinned and verified against Keycloak **26.0.8** specifically. Any future bump of eliferpg's Keycloak version requires rebuilding and re-verifying this provider against the new version first — forward compatibility is not safe to assume.
  - A pointer to `../backend/docs/superpowers/plans/2026-08-20-keycloak-spi-integration.md` for how this plugs into the rest of the eliferpg system (realm wiring, `compose.yml` changes) — explicitly out of scope for this repo.
  - The Central API contract gap flagged in Global Constraints (the `keycloakUserId`-bearing redeem variant, and the 404-vs-410 mapping this plan chose) — so whoever implements or reviews the Central API side has it in one place.

- [ ] **Step 2: Write `.github/workflows/ci.yml`**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: mvn -B verify
```

- [ ] **Step 3: Cross-check against the original spec's deliverables checklist** (`IMPLEMENTATION_PLAN.md` §12) — for each line item, confirm it's actually true of the repo as it now stands (don't just skim; run the relevant command if there's any doubt):
  - Maven project builds the jar — re-run the Step 2 build command from Task 4.
  - Factory/Provider implemented per §7 — read `LinkGameAccountRequiredAction.java`/`Factory.java` side by side with spec §7.2's bullet list.
  - `CentralApiClient` implemented against §5.2's contract, with token caching — confirmed by Task 2's tests.
  - PIN entry form renders and submits — confirmed by Task 5's integration walkthrough.
  - Success/invalid/expired/network-error paths distinct — confirmed by Task 3's unit tests AND Task 5's live walkthrough.
  - Dockerfile produces a working image — confirmed by Task 4.
  - Unit tests for `processAction` branching — Task 3.
  - Manual integration walkthrough performed and documented — Task 5's `TESTING.md`.
  - JDK version verified against the live container, not assumed — Global Constraints' first bullet, verified during plan authoring; note in the README that this was done and how (`docker run --rm --entrypoint java quay.io/keycloak/keycloak:26.0 -version`).
  - README covers provider id, env vars, versioning note, pointer to the integration plan — Step 1 above.

  If any item doesn't actually hold, fix it now rather than deferring — this is the last task in the plan.

- [ ] **Step 4: Run the full test suite one final time**

```bash
docker run --rm -v "$(pwd)":/app -w /app -v keycloak-eliferpg-spi-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B clean verify
```
Expected: `BUILD SUCCESS`, all tests passing.

- [ ] **Step 5: Commit**

```bash
git add README.md .github/workflows/ci.yml
git commit -m "Add README and CI workflow"
```
