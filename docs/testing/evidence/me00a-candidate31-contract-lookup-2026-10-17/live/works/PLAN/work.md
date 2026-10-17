# 登录接口实现交付说明

## 一、现有契约核查结果

### 1. 相关文件与符号（均为已知事实，来自本次提供的源码）

| 文件 | 符号 | 说明 |
|---|---|---|
| `src/main/java/sample/orders/user/LoginController.java` | `LoginController#login` | 待实现方法，`@PostMapping("/api/auth/login")` |
| `src/main/java/sample/orders/user/LoginRequest.java` | `record LoginRequest(String accountName, String password)` | 请求体字段 |
| `src/main/java/sample/orders/user/LoginResult.java` | `record LoginResult(String accessToken, String tokenType, long userId)` | 成功响应 data 字段 |
| `src/main/java/sample/orders/user/ApiEnvelope.java` | `record ApiEnvelope<T>(String code, T data, String message)` | 统一响应包装 |
| `src/main/java/sample/orders/user/UserRepository.java` | `Optional<UserAccount> findByAccountName(String)` | 用户查询端口 |
| `src/main/java/sample/orders/user/UserAccount.java` | `record UserAccount(long id, String accountName, String passwordHash, boolean enabled)` | 用户实体 |
| `src/main/java/sample/orders/user/PasswordVerifier.java` | `boolean matches(String rawPassword, String passwordHash)` | 密码校验端口 |
| `src/main/java/sample/orders/user/JwtIssuer.java` | `String issue(long userId)` | JWT 签发端口 |
| `src/main/java/sample/orders/user/LegacyLoginController.java` | `GET /legacy/session` | 旧入口，**不改动** |

### 2. 接口契约（依据 `LoginController` 类注释，属已知事实）

- **方法/路径**：`POST /api/auth/login`
- **请求体**：`LoginRequest { accountName, password }`
- **成功**：HTTP 200，`ApiEnvelope.code = "OK"`，`data = LoginResult { accessToken, tokenType = "Bearer", userId }`
- **参数为空**（`accountName` 或 `password` 为 null/空白）：HTTP 400，`code = "INVALID_INPUT"`
- **用户不存在 / 密码错误 / 账号禁用**：HTTP 401，`code = "AUTH_FAILED"`，**消息通用**，不区分具体原因
- **安全约束**：失败响应与日志中不得出现密码、密码哈希或 Token

### 3. 未核实项（明确标注，不按惯例假设）

- **Token 结构与有效期**：`JwtIssuer` 仅声明 `String issue(long userId)`，未暴露算法、claims、过期时间。**未核实**，需查看 `JwtIssuer` 的实现类或 JWT 配置（本次资料未提供）。
- **`message` 字段具体文案**：注释只要求“通用消息”，未给出确切字符串。实现中采用通用文案，**具体文案待产品/契约确认**。
- **`tokenType` 大小写**：注释写 “Bearer”，实现按此返回 `"Bearer"`。
- **账号状态异常的其他形态**（如锁定、过期）：`UserAccount` 仅有 `enabled` 布尔，无其他状态字段，故仅处理 `enabled=false`。
- **请求体缺失/JSON 非法**：注释未定义。Spring 默认会抛 `HttpMessageNotReadableException`（400），本实现不额外拦截，保持框架默认行为（**待确认是否需统一为 `INVALID_INPUT`**）。

---

## 二、实现方案

### 分层与模块划分

- **Controller 层**：`LoginController` 负责入参校验、调用端口、组装 `ApiEnvelope` 与 HTTP 状态码。
- **端口层**：复用现有 `UserRepository`、`PasswordVerifier`、`JwtIssuer`，不新增端口。
- **DTO**：复用现有 `LoginRequest`、`LoginResult`、`ApiEnvelope`，不新增。

### 新增/修改文件清单

| 文件 | 动作 | 理由 |
|---|---|---|
| `src/main/java/sample/orders/user/LoginController.java` | 修改（仅实现 `login` 方法体） | 任务要求实现待定方法 |
| `src/test/java/sample/orders/user/LoginControllerTest.java` | 新增 | 覆盖正常/异常/边界场景，使用依赖 fake |

**不改动**：`LegacyLoginController`、`UserAccount`、`UserRepository`、`PasswordVerifier`、`JwtIssuer`、`LoginRequest`、`LoginResult`、`ApiEnvelope`。无迁移、无依赖变更。

### 关键设计点

1. **入参校验**：`request == null` 或 `accountName`/`password` 为 null 或 `isBlank()` → 400 `INVALID_INPUT`。先校验再查库，避免无谓查询。
2. **认证失败统一**：用户不存在、密码不匹配、`enabled=false` 均返回 401 `AUTH_FAILED` 且**同一通用消息**，防止账号枚举。
3. **密码校验短路**：用户不存在时不调用 `PasswordVerifier`（避免对 null hash 调用），但对外表现一致。
4. **不泄露敏感信息**：失败消息不含账号、密码、哈希、Token；不记录日志（本 fixture 无日志框架要求，保持无日志输出）。
5. **Token 签发**：仅在认证成功后调用 `tokens.issue(user.id())`，`tokenType` 固定 `"Bearer"`。

---

## 三、关键代码

src/main/java/sample/orders/user/LoginController.java

```java
package sample.orders.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

        UserAccount account = users.findByAccountName(request.accountName()).orElse(null);

        // Uniform failure for missing / wrong password / disabled to avoid account enumeration.
        if (account == null
                || !account.enabled()
                || !passwords.matches(request.password(), account.passwordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiEnvelope<>(CODE_AUTH_FAILED, null, MSG_AUTH_FAILED));
        }

        String accessToken = tokens.issue(account.id());
        LoginResult result = new LoginResult(accessToken, TOKEN_TYPE, account.id());
        return ResponseEntity.ok(new ApiEnvelope<>(CODE_OK, result, "OK"));
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LoginController using in-memory fakes for the mandatory ports.
 * All users/values are synthetic. No database or network access.
 */
class LoginControllerTest {

    private static final String ACCOUNT = "alice";
    private static final String RAW_PASSWORD = "s3cret-pass";
    private static final String HASH = "hash-of-s3cret-pass";
    private static final long USER_ID = 42L;
    private static final String ISSUED_TOKEN = "synthetic.jwt.token";

    private FakeUserRepository users;
    private FakePasswordVerifier passwords;
    private FakeJwtIssuer tokens;
    private LoginController controller;

    @BeforeEach
    void setUp() {
        users = new FakeUserRepository();
        passwords = new FakePasswordVerifier();
        tokens = new FakeJwtIssuer(ISSUED_TOKEN);
        controller = new LoginController(users, passwords, tokens);
    }

    // ---------- success ----------

    @Test
    @DisplayName("valid credentials -> 200 OK with Bearer token and userId")
    void loginSuccess() {
        users.put(new UserAccount(USER_ID, ACCOUNT, HASH, true));
        passwords.register(HASH, RAW_PASSWORD);

        ResponseEntity<ApiEnvelope<LoginResult>> resp =
                controller.login(new LoginRequest(ACCOUNT, RAW_PASSWORD));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        ApiEnvelope<LoginResult> body = resp.getBody();
        assertNotNull(body);
        assertEquals("OK", body.code());
        assertNotNull(body.data());
        assertEquals(ISSUED_TOKEN, body.data().accessToken());
        assertEquals("Bearer", body.data().tokenType());
        assertEquals(USER_ID, body.data().userId());
        assertEquals(1, tokens.issueCount, "token issued exactly once");
    }

    // ---------- invalid input (400) ----------

    @Test
    @DisplayName("null request -> 400 INVALID_INPUT")
    void nullRequest() {
        assertInvalidInput(controller.login(null));
    }

    @Test
    @DisplayName("null accountName -> 400 INVALID_INPUT")
    void nullAccountName() {
        assertInvalidInput(controller.login(new LoginRequest(null, RAW_PASSWORD)));
    }

    @Test
    @DisplayName("blank accountName -> 400 INVALID_INPUT")
    void blankAccountName() {
        assertInvalidInput(controller.login(new LoginRequest("   ", RAW_PASSWORD)));
    }

    @Test
    @DisplayName("null password -> 400 INVALID_INPUT")
    void nullPassword() {
        assertInvalidInput(controller.login(new LoginRequest(ACCOUNT, null)));
    }

    @Test
    @DisplayName("blank password -> 400 INVALID_INPUT")
    void blankPassword() {
        assertInvalidInput(controller.login(new LoginRequest(ACCOUNT, "\t\n")));
    }

    @Test
    @DisplayName("invalid input does not query repository or issue token")
    void invalidInputShortCircuits() {
        controller.login(new LoginRequest("", ""));
        assertEquals(0, users.findCount, "repository must not be queried");
        assertEquals(0, tokens.issueCount, "token must not be issued");
    }

    // ---------- auth failure (401) ----------

    @Test
    @DisplayName("unknown account -> 401 AUTH_FAILED")
    void unknownAccount() {
        assertAuthFailed(controller.login(new LoginRequest("nobody", RAW_PASSWORD)));
    }

    @Test
    @DisplayName("wrong password -> 401 AUTH_FAILED")
    void wrongPassword() {
        users.put(new UserAccount(USER_ID, ACCOUNT, HASH, true));
        passwords.register(HASH, RAW_PASSWORD);

        assertAuthFailed(controller.login(new LoginRequest(ACCOUNT, "wrong-pass")));
        assertEquals(0, tokens.issueCount, "token must not be issued on failure");
    }

    @Test
    @DisplayName("disabled account with correct password -> 401 AUTH_FAILED")
    void disabledAccount() {
        users.put(new UserAccount(USER_ID, ACCOUNT, HASH, false));
        passwords.register(HASH, RAW_PASSWORD);

        assertAuthFailed(controller.login(new LoginRequest(ACCOUNT, RAW_PASSWORD)));
        assertEquals(0, tokens.issueCount, "token must not be issued on failure");
    }

    @Test
    @DisplayName("failure message is generic and leaks no sensitive data")
    void failureMessageIsGeneric() {
        users.put(new UserAccount(USER_ID, ACCOUNT, HASH, true));
        passwords.register(HASH, RAW_PASSWORD);

        ApiEnvelope<LoginResult> body =
                controller.login(new LoginRequest(ACCOUNT, "wrong-pass")).getBody();
        assertNotNull(body);
        assertNull(body.data());
        String msg = body.message();
        assertNotNull(msg);
        assertFalse(msg.contains(RAW_PASSWORD), "must not contain password");
        assertFalse(msg.contains(HASH), "must not contain password hash");
        assertFalse(msg.contains(ACCOUNT), "must not contain account name");
        assertFalse(msg.contains(ISSUED_TOKEN), "must not contain token");
    }

    @Test
    @DisplayName("missing account and wrong password produce identical failure envelope")
    void failureIsIndistinguishable() {
        users.put(new UserAccount(USER_ID, ACCOUNT, HASH, true));
        passwords.register(HASH, RAW_PASSWORD);

        ApiEnvelope<LoginResult> missing =
                controller.login(new LoginRequest("nobody", RAW_PASSWORD)).getBody();
        ApiEnvelope<LoginResult> wrong =
                controller.login(new LoginRequest(ACCOUNT, "wrong-pass")).getBody();

        assertNotNull(missing);
        assertNotNull(wrong);
        assertEquals(missing.code(), wrong.code());
        assertEquals(missing.message(), wrong.message());
    }

    // ---------- helpers ----------

    private void assertInvalidInput(ResponseEntity<ApiEnvelope<LoginResult>> resp) {
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        ApiEnvelope<LoginResult> body = resp.getBody();
        assertNotNull(body);
        assertEquals("INVALID_INPUT", body.code());
        assertNull(body.data());
        assertNotNull(body.message());
    }

    private void assertAuthFailed(ResponseEntity<ApiEnvelope<LoginResult>> resp) {
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        ApiEnvelope<LoginResult> body = resp.getBody();
        assertNotNull(body);
        assertEquals("AUTH_FAILED", body.code());
        assertNull(body.data());
        assertNotNull(body.message());
    }

    // ---------- fakes ----------

    private static final class FakeUserRepository implements UserRepository {
        private final Map<String, UserAccount> store = new HashMap<>();
        int findCount = 0;

        void put(UserAccount account) {
            store.put(account.accountName(), account);
        }

        @Override
        public Optional<UserAccount> findByAccountName(String accountName) {
            findCount++;
            return Optional.ofNullable(store.get(accountName));
        }
    }

    private static final class FakePasswordVerifier implements PasswordVerifier {
        private final Map<String, String> hashToRaw = new HashMap<>();

        void register(String hash, String raw) {
            hashToRaw.put(hash, raw);
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            if (rawPassword == null || passwordHash == null) {
                return false;
            }
            return rawPassword.equals(hashToRaw.get(passwordHash));
        }
    }

    private static final class FakeJwtIssuer implements JwtIssuer {
        private final String token;
        int issueCount = 0;

        FakeJwtIssuer(String token) {
            this.token = token;
        }

        @Override
        public String issue(long userId) {
            issueCount++;
            return token;
        }
    }
}
```

---

## 四、接口契约（示例为示意，字段以实际契约为准）

### 成功

```
POST /api/auth/login
Content-Type: application/json

{ "accountName": "alice", "password": "s3cret-pass" }
```

```json
HTTP/1.1 200 OK
{
  "code": "OK",
  "data": { "accessToken": "<jwt>", "tokenType": "Bearer", "userId": 42 },
  "message": "OK"
}
```

### 参数为空

```json
HTTP/1.1 400 Bad Request
{ "code": "INVALID_INPUT", "data": null, "message": "accountName and password are required" }
```

### 认证失败（用户不存在 / 密码错误 / 账号禁用，三者响应一致）

```json
HTTP/1.1 401 Unauthorized
{ "code": "AUTH_FAILED", "data": null, "message": "Authentication failed" }
```

> 注：`message` 文案为示意，注释仅要求“通用消息”，确切文案待确认。`accessToken` 结构/有效期由 `JwtIssuer` 实现决定，**未核实**。

---

## 五、测试方案与受影响测试

### 受影响现有测试

- 本次资料中**未提供任何现有测试文件**（`src/test` 下无内容）。因此无既有测试需修改；`LegacyLoginController` 无测试且未改动。
- 若仓库其他位置存在 `LoginController` 相关测试，需一并运行（本次资料未包含，**未核实**）。

### 新增测试覆盖

| 场景 | 用例 |
|---|---|
| 正常 | `loginSuccess` |
| 异常-参数 | `nullRequest`、`nullAccountName`、`blankAccountName`、`nullPassword`、`blankPassword` |
| 边界-短路 | `invalidInputShortCircuits`（不查库、不签发） |
| 异常-认证 | `unknownAccount`、`wrongPassword`、`disabledAccount` |
| 安全 | `failureMessageIsGeneric`、`failureIsIndistinguishable`（防枚举） |

### 运行方式

```
mvn -q -Dtest=LoginControllerTest test
```

### 结果说明

- 测试使用内存 fake 实现三个端口，**不访问数据库/网络**，符合 fixture 约束。
- 本环境未实际执行构建（无 Maven/依赖环境信息），**运行结果未实测**；代码按 JUnit 5 + Spring `ResponseEntity` 编写，逻辑上应全部通过。请在具备 Java 21 + Spring Boot 3 的环境中执行上述命令确认。

---

## 六、已知事实 / 用户确认 / 必要假设 区分

- **已知事实**：路径、请求/响应字段、状态码、错误码、通用消息要求、端口签名，均来自提供的源码与注释。
- **用户确认**：沿用现有表/密码校验/JWT 契约，不改旧入口，不迁移，不新增依赖。
- **必要假设（已标注待确认）**：`message` 具体文案、`tokenType` 精确大小写、请求体缺失时的处理方式、Token 结构与有效期。这些均未在源码中确认，实现中未硬编码为“事实”，仅按注释最小要求处理。