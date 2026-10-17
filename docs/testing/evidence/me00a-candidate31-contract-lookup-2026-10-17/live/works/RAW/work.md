以下是本次实现所需的完整修改文件。

src/main/java/sample/orders/user/LoginController.java
```java
package sample.orders.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Existing contract: POST /api/auth/login; accountName/password; success 200+OK with accessToken, Bearer, userId.
 * Blank fields return 400+INVALID_INPUT. Missing, wrong-password or disabled accounts return 401+AUTH_FAILED.
 * Failure message is generic; no password, password hash or token in failures/logs. Existing ports below are mandatory.
 */
@RestController
public class LoginController {

    private static final String CODE_OK = "OK";
    private static final String CODE_INVALID_INPUT = "INVALID_INPUT";
    private static final String CODE_AUTH_FAILED = "AUTH_FAILED";
    private static final String TOKEN_TYPE = "Bearer";
    private static final String MSG_INVALID_INPUT = "accountName and password are required";
    private static final String MSG_AUTH_FAILED = "Authentication failed";

    private final UserRepository users;
    private final PasswordVerifier passwords;
    private final JwtIssuer tokens;

    public LoginController(UserRepository users, PasswordVerifier passwords, JwtIssuer tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<ApiEnvelope<LoginResult>> login(@RequestBody LoginRequest request) {
        if (request == null
                || isBlank(request.accountName())
                || isBlank(request.password())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiEnvelope<>(CODE_INVALID_INPUT, null, MSG_INVALID_INPUT));
        }

        Optional<UserAccount> found = users.findByAccountName(request.accountName());
        if (found.isEmpty()) {
            return authFailed();
        }

        UserAccount account = found.get();
        if (!account.enabled()) {
            return authFailed();
        }

        if (!passwords.matches(request.password(), account.passwordHash())) {
            return authFailed();
        }

        String accessToken = tokens.issue(account.id());
        LoginResult result = new LoginResult(accessToken, TOKEN_TYPE, account.id());
        return ResponseEntity.ok(new ApiEnvelope<>(CODE_OK, result, CODE_OK));
    }

    private ResponseEntity<ApiEnvelope<LoginResult>> authFailed() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiEnvelope<>(CODE_AUTH_FAILED, null, MSG_AUTH_FAILED));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

src/test/java/sample/orders/user/LoginControllerTest.java
```java
package sample.orders.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LoginControllerTest {

    private static final long USER_ID = 42L;
    private static final String ACCOUNT = "alice";
    private static final String RAW_PASSWORD = "s3cret-pass";
    private static final String PASSWORD_HASH = "hash::" + RAW_PASSWORD;
    private static final String ISSUED_TOKEN = "jwt-token-synthetic";

    private FakeUserRepository users;
    private FakePasswordVerifier passwords;
    private FakeJwtIssuer tokens;
    private LoginController controller;

    @BeforeEach
    void setUp() {
        users = new FakeUserRepository();
        passwords = new FakePasswordVerifier();
        tokens = new FakeJwtIssuer();
        controller = new LoginController(users, passwords, tokens);
    }

    @Test
    void successfulLoginReturnsOkEnvelopeWithBearerTokenAndUserId() {
        users.put(new UserAccount(USER_ID, ACCOUNT, PASSWORD_HASH, true));

        ResponseEntity<ApiEnvelope<LoginResult>> response =
                controller.login(new LoginRequest(ACCOUNT, RAW_PASSWORD));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiEnvelope<LoginResult> body = response.getBody();
        assertNotNull(body);
        assertEquals("OK", body.code());
        assertNotNull(body.data());
        assertEquals(ISSUED_TOKEN, body.data().accessToken());
        assertEquals("Bearer", body.data().tokenType());
        assertEquals(USER_ID, body.data().userId());
        assertEquals(1, tokens.issueCount);
        assertEquals(USER_ID, tokens.lastUserId);
    }

    @Test
    void blankAccountNameReturnsBadRequestInvalidInput() {
        ResponseEntity<ApiEnvelope<LoginResult>> response =
                controller.login(new LoginRequest("   ", RAW_PASSWORD));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_INPUT", response.getBody().code());
        assertNull(response.getBody().data());
        assertEquals(0, tokens.issueCount);
    }

    @Test
    void nullPasswordReturnsBadRequestInvalidInput() {
        ResponseEntity<ApiEnvelope<LoginResult>> response =
                controller.login(new LoginRequest(ACCOUNT, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_INPUT", response.getBody().code());
        assertNull(response.getBody().data());
    }

    @Test
    void nullRequestReturnsBadRequestInvalidInput() {
        ResponseEntity<ApiEnvelope<LoginResult>> response = controller.login(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INVALID_INPUT", response.getBody().code());
    }

    @Test
    void missingAccountReturnsUnauthorizedAuthFailed() {
        ResponseEntity<ApiEnvelope<LoginResult>> response =
                controller.login(new LoginRequest("nobody", RAW_PASSWORD));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("AUTH_FAILED", response.getBody().code());
        assertNull(response.getBody().data());
        assertEquals(0, tokens.issueCount);
    }

    @Test
    void wrongPasswordReturnsUnauthorizedAuthFailed() {
        users.put(new UserAccount(USER_ID, ACCOUNT, PASSWORD_HASH, true));

        ResponseEntity<ApiEnvelope<LoginResult>> response =
                controller.login(new LoginRequest(ACCOUNT, "wrong-password"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("AUTH_FAILED", response.getBody().code());
        assertNull(response.getBody().data());
        assertEquals(0, tokens.issueCount);
    }

    @Test
    void disabledAccountReturnsUnauthorizedAuthFailed() {
        users.put(new UserAccount(USER_ID, ACCOUNT, PASSWORD_HASH, false));

        ResponseEntity<ApiEnvelope<LoginResult>> response =
                controller.login(new LoginRequest(ACCOUNT, RAW_PASSWORD));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("AUTH_FAILED", response.getBody().code());
        assertNull(response.getBody().data());
        assertEquals(0, tokens.issueCount);
    }

    @Test
    void failureMessageIsGenericAndDoesNotLeakSecrets() {
        users.put(new UserAccount(USER_ID, ACCOUNT, PASSWORD_HASH, true));

        ResponseEntity<ApiEnvelope<LoginResult>> response =
                controller.login(new LoginRequest(ACCOUNT, "wrong-password"));

        assertNotNull(response.getBody());
        String message = response.getBody().message();
        assertNotNull(message);
        assertFalse(message.contains(RAW_PASSWORD));
        assertFalse(message.contains(PASSWORD_HASH));
        assertFalse(message.contains(ISSUED_TOKEN));
    }

    private static final class FakeUserRepository implements UserRepository {
        private final Map<String, UserAccount> byName = new HashMap<>();

        void put(UserAccount account) {
            byName.put(account.accountName(), account);
        }

        @Override
        public Optional<UserAccount> findByAccountName(String accountName) {
            return Optional.ofNullable(byName.get(accountName));
        }
    }

    private static final class FakePasswordVerifier implements PasswordVerifier {
        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return passwordHash != null && passwordHash.equals("hash::" + rawPassword);
        }
    }

    private static final class FakeJwtIssuer implements JwtIssuer {
        private int issueCount;
        private long lastUserId;

        @Override
        public String issue(long userId) {
            issueCount++;
            lastUserId = userId;
            return ISSUED_TOKEN;
        }
    }
}
```