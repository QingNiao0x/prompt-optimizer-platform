package sample.orders.user;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** Existing contract: POST /api/auth/login; accountName/password; success 200+OK with accessToken, Bearer, userId.
 * Blank fields return 400+INVALID_INPUT. Missing, wrong-password or disabled accounts return 401+AUTH_FAILED.
 * Failure message is generic; no password, password hash or token in failures/logs. Existing ports below are mandatory. */
@RestController
public class LoginController {
    private final UserRepository users;
    private final PasswordVerifier passwords;
    private final JwtIssuer tokens;
    public LoginController(UserRepository users, PasswordVerifier passwords, JwtIssuer tokens) {
        this.users = users; this.passwords = passwords; this.tokens = tokens;
    }
    @PostMapping("/api/auth/login")
    public ResponseEntity<ApiEnvelope<LoginResult>> login(@RequestBody LoginRequest request) {
        throw new UnsupportedOperationException("Pending implementation");
    }
}
