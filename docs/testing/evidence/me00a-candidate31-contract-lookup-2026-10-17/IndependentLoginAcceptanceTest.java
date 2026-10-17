package sample.orders.user;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** 独立于模型自写测试的合成契约检查，不访问数据库、网络或真实凭据。 */
class IndependentLoginAcceptanceTest {
    private static final String PASSWORD = "fixture-only-password";
    private static final String HASH = "fixture-only-hash";

    private LoginController controller(boolean exists, boolean enabled, boolean matches) {
        UserRepository users = name -> exists
                ? Optional.of(new UserAccount(7, name, HASH, enabled)) : Optional.empty();
        PasswordVerifier verifier = (raw, stored) -> matches && PASSWORD.equals(raw) && HASH.equals(stored);
        JwtIssuer issuer = id -> "fixture.jwt." + id;
        return new LoginController(users, verifier, issuer);
    }

    @Test void successUsesExistingPortsAndResponseFields() {
        var response = controller(true, true, true).login(new LoginRequest("fixture-user", PASSWORD));
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("OK", response.getBody().code());
        assertEquals(new LoginResult("fixture.jwt.7", "Bearer", 7), response.getBody().data());
    }

    @Test void blankAccountIsInvalid() {
        assertFailure(controller(true, true, true).login(new LoginRequest(" ", PASSWORD)), 400, "INVALID_INPUT");
    }

    @Test void blankPasswordIsInvalid() {
        assertFailure(controller(true, true, true).login(new LoginRequest("fixture-user", "")), 400, "INVALID_INPUT");
    }

    @Test void nullRequestIsInvalid() {
        assertFailure(controller(true, true, true).login(null), 400, "INVALID_INPUT");
    }

    @Test void missingUserAndWrongPasswordAreIndistinguishable() {
        var missing = controller(false, true, false).login(new LoginRequest("fixture-user", PASSWORD));
        var wrong = controller(true, true, false).login(new LoginRequest("fixture-user", PASSWORD));
        assertFailure(missing, 401, "AUTH_FAILED");
        assertFailure(wrong, 401, "AUTH_FAILED");
        assertEquals(missing.getBody(), wrong.getBody());
    }

    @Test void disabledAccountCannotReceiveToken() {
        assertFailure(controller(true, false, true).login(new LoginRequest("fixture-user", PASSWORD)), 401, "AUTH_FAILED");
    }

    @Test void endpointIsNotSilentlyRenamed() throws Exception {
        var method = LoginController.class.getMethod("login", LoginRequest.class);
        assertArrayEquals(new String[]{"/api/auth/login"},
                method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value());
    }

    @Test void legacyEntryStillWorks() {
        assertEquals("legacy-entry", new LegacyLoginController().session());
    }

    /** 失败体必须按既有契约返回，合成敏感值只用于观察结果，不作为请求清洗器。 */
    private void assertFailure(ResponseEntity<ApiEnvelope<LoginResult>> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(code, response.getBody().code());
        assertNull(response.getBody().data());
        String body = response.getBody().toString();
        assertFalse(body.contains(PASSWORD));
        assertFalse(body.contains(HASH));
        assertFalse(body.contains("fixture.jwt."));
    }
}
