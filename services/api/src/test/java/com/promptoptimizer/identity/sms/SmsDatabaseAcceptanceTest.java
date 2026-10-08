package com.promptoptimizer.identity.sms;

import com.promptoptimizer.common.config.MybatisPlusConfiguration;
import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.identity.controller.*;
import com.promptoptimizer.identity.domain.*;
import com.promptoptimizer.identity.dto.*;
import com.promptoptimizer.identity.entity.SmsChallenge;
import com.promptoptimizer.identity.infrastructure.sms.*;
import com.promptoptimizer.identity.mapper.*;
import com.promptoptimizer.identity.security.*;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.identity.service.impl.*;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * 真实 PostgreSQL/Redis + 模拟云供应商验收。只接受专用本机端口与数据库名，绝不连接开发业务库。
 * 每次启动使用随机 schema；不发送短信，不读取任何 AccessKey。
 */
@EnabledIfEnvironmentVariable(named="SMS_TEST_DB_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:55439/pnvs_acceptance")
@SpringBootTest(classes=SmsDatabaseAcceptanceTest.TestApp.class, properties={
        "spring.session.store-type=none", "app.security.login-guard.require-redis=false", "decorator.datasource.enabled=false",
        "app.security.sms.enabled=false", "spring.main.banner-mode=off", "logging.level.root=WARN"})
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("sms-acceptance")
class SmsDatabaseAcceptanceTest {
    private static final String SCHEMA = "sms_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicInteger NUMBERS = new AtomicInteger(1000);
    private static final String PASSWORD = "Synthetic-test1";

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("sms-acceptance")
    @EnableAutoConfiguration
    @Import({MybatisPlusConfiguration.class, SmsChallengeTransactions.class, SmsAccountTransactions.class,
            PhoneVerificationService.class, SmsFingerprint.class, RedisSmsRateLimiter.class,
            DatabaseUserDetailsService.class, SmsAuthenticationProvider.class, AuthenticationServiceImpl.class,
            SecurityConfiguration.class, SecurityErrorWriter.class, SecurityContextCurrentActor.class,
            AuthenticationController.class, PhoneAuthenticationController.class, SmsExceptionHandler.class, PhonePersistenceExceptionHandler.class,
            GlobalExceptionHandler.class, LoginCaptchaServiceImpl.class, LoginFailureGuard.class})
    static class TestApp {
        @Bean SmsProperties smsProperties() {
            var p = new SmsProperties(); p.setEnabled(true); p.setSchemePrefix("test");
            p.setVerificationSecret("synthetic-sms-secret-not-valid-outside-tests"); p.setIpHourlyLimit(500);
            return p;
        }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("SMS_TEST_DB_URL") + "?currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> "pnvs_test");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.data.redis.url", () -> "redis://127.0.0.1:56389");
        properties.add("spring.autoconfigure.exclude", () -> "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration");
    }

    @Autowired PhoneVerificationService service;
    @Autowired SmsChallengeTransactions challenges;
    @Autowired SmsAccountTransactions accounts;
    @Autowired SmsChallengeMapper challengeMapper;
    @Autowired SmsAccountMapper phoneMapper;
    @Autowired UserIdentityMapper identities;
    @Autowired UserAccountMapper users;
    @Autowired IdentityProvisioningMapper provision;
    @Autowired SmsFixtureMapper fixture;
    @Autowired PasswordEncoder encoder;
    @Autowired SmsFingerprint fingerprints;
    @Autowired DatabaseUserDetailsService details;
    @Autowired LoginCaptchaService captcha;
    @Autowired SmsProperties properties;
    @Autowired RedisSmsRateLimiter limiter;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean SmsVerificationProvider cloud;
    @MockBean AnalyticsEventService analytics;

    @BeforeEach void setup() {
        properties.setEnabled(true);
        properties.setVerificationSecret("synthetic-sms-secret-not-valid-outside-tests");
        properties.setSchemePrefix("t" + UUID.randomUUID().toString().substring(0, 7));
        properties.setPhoneHourlyLimit(5); properties.setIpHourlyLimit(500); properties.setActorHourlyLimit(5);
        when(cloud.verify(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test void registrationCreatesOneAccountAndCanResumeLostResponse() {
        String phone = number(); var request = browser(); var challenge = issue(phone, SmsPurpose.REGISTER, request, null);
        var body = new SmsRequests.Registration(phone, challenge.challengeId(), "123456", PASSWORD);
        service.register(body, request);
        var counts = fixture.counts();
        service.register(body, request);
        assertThat(fixture.counts()).isEqualTo(counts);
        var identity = identities.selectByLoginKey(UserIdentityType.PHONE, "local", phone);
        var user = users.selectById(identity.getUserId());
        assertThat(user.getEmail()).isNull(); assertThat(user.getPlatformRole()).isEqualTo("USER");
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(users.selectDefaultWorkspaceId(user.getId(), user.getTenantId())).isNotNull();
        verify(cloud, times(1)).verify(anyString(), anyString(), anyString(), anyString());
    }

    @Test void challengeRejectsCrossBrowserPurposeActorAndExpiry() {
        String phone = number(); var request = browser(); var view = issue(phone, SmsPurpose.REGISTER, request, null);
        assertThatThrownBy(() -> service.register(new SmsRequests.Registration(phone, view.challengeId(), "123456", PASSWORD), browser())).isInstanceOf(SmsException.class);
        assertThatThrownBy(() -> service.login(new SmsRequests.Login(phone, view.challengeId(), "123456"), request)).isInstanceOf(SmsException.class);
        var row = challengeMapper.find(view.challengeId());
        assertThatThrownBy(() -> SmsChallengeTransactions.checked(row, row.purpose(), row.phoneFingerprint(), row.browserFingerprint(), UUID.randomUUID())).isInstanceOf(SmsException.class);
        fixture.expire(view.challengeId(), OffsetDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> service.register(new SmsRequests.Registration(phone, view.challengeId(), "123456", PASSWORD), request)).isInstanceOf(SmsException.class);
        verify(cloud, never()).verify(anyString(), anyString(), anyString(), anyString());
    }

    @Test void fifthAttemptExhaustsChallengeAndFreshSendSupersedesOldOne() {
        when(cloud.verify(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        String phone = number(); var request = browser(); var view = issue(phone, SmsPurpose.REGISTER, request, null);
        for (int i=0;i<6;i++) assertThatThrownBy(() -> service.register(new SmsRequests.Registration(phone, view.challengeId(), "000000", PASSWORD), request)).isInstanceOf(SmsException.class);
        assertThat(challengeMapper.find(view.challengeId()).attempts()).isEqualTo(5);
        verify(cloud, times(5)).verify(anyString(), anyString(), anyString(), anyString());
        var first = challenges.create(SmsPurpose.LOGIN, "replacement", "browser", null, "test-login");
        challenges.create(SmsPurpose.LOGIN, "replacement", "browser", null, "test-login");
        assertThat(challengeMapper.find(first.id()).state()).isEqualTo(SmsChallengeState.SUPERSEDED);
    }

    @Test void onlyOneConcurrentRequestMayVerify() throws Exception {
        var row = challenges.create(SmsPurpose.REGISTER, "concurrent", "browser", null, "test-register");
        challengeMapper.markSent(row.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> attempt = () -> { start.await(); try { challenges.reserve(row.id(), row.purpose(), "concurrent", "browser", null, UUID.randomUUID()); return true; } catch (SmsException e) { return false; } };
            var a=pool.submit(attempt); var b=pool.submit(attempt); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS),b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(challengeMapper.find(row.id()).attempts()).isEqualTo(1);
    }

    @Test void concurrentRegistrationIsIdempotentAndDatabaseRollbackRetainsAuthorization() throws Exception {
        String phone = number(); var row = verified(SmsPurpose.REGISTER, phone, null);
        var before = fixture.counts();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> { complete(row, phone, null); status.setRollbackOnly(); });
        assertThat(fixture.counts()).isEqualTo(before);
        assertThat(challengeMapper.find(row.id()).state()).isEqualTo(SmsChallengeState.VERIFIED);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> complete(row, phone, null));
            var b = pool.submit(() -> complete(row, phone, null));
            assertThat(a.get(10, TimeUnit.SECONDS).userId()).isEqualTo(b.get(10, TimeUnit.SECONDS).userId());
        }
        assertThat(challengeMapper.find(row.id()).state()).isEqualTo(SmsChallengeState.CONSUMED);
    }

    @Test void bindingOccupiedPhoneConsumesProofWithoutChangingEitherHistory() {
        var first = createUser("owner", "USER"); var other = createUser("other", "USER");
        String phone = number(); phoneMapper.insertPhone(UUID.randomUUID(), other.actorIdentity().userId(), phone);
        var digest = fixture.historyDigest(); var counts = fixture.counts();
        var row = verified(SmsPurpose.BIND, phone, first);
        var result = complete(row, phone, first);
        assertThatThrownBy(result::requireSuccess).isInstanceOf(SmsException.class)
                .extracting(e -> ((SmsException)e).getCode()).isEqualTo("PHONE_ALREADY_BOUND");
        assertThat(fixture.counts()).isEqualTo(counts); assertThat(fixture.historyDigest()).isEqualTo(digest);
        assertThat(phoneMapper.activePhone(other.actorIdentity().userId())).isNotNull();
        assertThat(challengeMapper.find(row.id()).state()).isEqualTo(SmsChallengeState.CONSUMED);
    }

    @Test void administratorCanBindButCannotSmsLoginAndAccountHasOnePhone() {
        var admin = createUser("admin", "PLATFORM_ADMIN"); String phone = number();
        var bind = verified(SmsPurpose.BIND, phone, admin);
        complete(bind, phone, admin).requireSuccess(); complete(bind, phone, admin).requireSuccess();
        var login = verified(SmsPurpose.LOGIN, phone, null);
        assertThat(complete(login, phone, null).code()).isEqualTo("SMS_PASSWORD_LOGIN_REQUIRED");
        var another = number();
        assertThat(complete(verified(SmsPurpose.BIND, another, admin), another, admin).code()).isEqualTo("PHONE_BINDING_EXISTS");
    }

    @Test void revokedPhoneStaysReservedAndLockedAccountCannotLoginOrBind() {
        var user = createUser("locked", "USER"); String phone = number();
        UUID identityId = UUID.randomUUID(); phoneMapper.insertPhone(identityId, user.actorIdentity().userId(), phone);
        fixture.accountStatus(user.actorIdentity().userId(), "LOCKED");
        assertThat(complete(verified(SmsPurpose.LOGIN, phone, null), phone, null).code()).isEqualTo("AUTHENTICATION_FAILED");
        assertThatThrownBy(() -> accounts.checkBindingActor(user, PASSWORD)).isInstanceOf(BadCredentialsException.class);
        fixture.accountStatus(user.actorIdentity().userId(), "DISABLED");
        assertThat(complete(verified(SmsPurpose.LOGIN, phone, null), phone, null).code()).isEqualTo("AUTHENTICATION_FAILED");
        fixture.identityStatus(identityId, "REVOKED");
        assertThat(complete(verified(SmsPurpose.REGISTER, phone, null), phone, null).code()).isEqualTo("PHONE_ALREADY_REGISTERED");
        assertThat(complete(verified(SmsPurpose.LOGIN, phone, null), phone, null).code()).isEqualTo("AUTHENTICATION_FAILED");
    }

    @Test void revokedLoginIdentityOrWrongPasswordCannotBind() {
        var user = createUser("revoke", "USER");
        assertThatThrownBy(() -> accounts.checkBindingActor(user, "incorrect1")).isInstanceOf(BadCredentialsException.class);
        fixture.identityStatus(user.getIdentityId(), "REVOKED");
        assertThatThrownBy(() -> accounts.checkBindingActor(user, PASSWORD)).isInstanceOf(BadCredentialsException.class);
    }

    @Test void rateLimitsAreAtomicSharedAcrossPurposesAndFailClosed() throws Exception {
        assertThat(redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("return redis.call('PTTL',KEYS[1])", Long.class), List.of("synthetic-check"))).isEqualTo(-2L);
        String phone = UUID.randomUUID().toString(), ip = UUID.randomUUID().toString();
        limiter.reserve(phone, ip, null);
        assertThatThrownBy(() -> limiter.reserve(phone, ip, null)).isInstanceOf(SmsException.class);
        String prefix = "prompt-optimizer:sms:{"+properties.getSchemePrefix()+"}:";
        redis.expire(prefix+"cooldown:"+phone, Duration.ofMillis(900));
        assertThatThrownBy(() -> limiter.reserve(phone, ip, null)).isInstanceOf(SmsException.class);
        properties.setPhoneHourlyLimit(1); redis.delete(prefix+"cooldown:"+phone);
        assertThatThrownBy(() -> limiter.reserve(phone, ip, null)).isInstanceOf(SmsException.class);
        properties.setIpHourlyLimit(1);
        assertThatThrownBy(() -> limiter.reserve("different", ip, null)).isInstanceOf(SmsException.class);
        properties.setActorHourlyLimit(1);
        limiter.reserve("bind1", "another-ip", "actor");
        assertThatThrownBy(() -> limiter.reserve("bind2", "other-ip", "actor")).isInstanceOf(SmsException.class);
    }

    @Test void timeoutNeverResendsAndDoesNotRefundBudget() {
        doThrow(SmsException.unavailable()).when(cloud).send(anyString(),anyString(),anyString(),anyString());
        String phone=number(); var request=browser();
        assertThatThrownBy(() -> issue(phone,SmsPurpose.REGISTER,request,null)).isInstanceOf(SmsException.class);
        assertThatThrownBy(() -> issue(phone,SmsPurpose.LOGIN,request,null)).isInstanceOf(SmsException.class)
                .extracting(e -> ((SmsException)e).getCode()).isEqualTo("SMS_RATE_LIMITED");
        verify(cloud,times(1)).send(anyString(),anyString(),anyString(),anyString());
    }

    @Test void csrfAndAnonymousBindingAreRejectedBeforeCloudAccess() throws Exception {
        mvc.perform(post("/api/v1/auth/phone/login").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        var csrf=mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/v1/me/phone-binding").cookie(csrf).header("X-XSRF-TOKEN",csrf.getValue())
                .contentType(APPLICATION_JSON).content("{}")) .andExpect(status().isUnauthorized());
        verifyNoInteractions(cloud);
    }

    @Test void smsLoginRotatesSessionMasksNumberAndConsumedCodeCannotLoginAgain() throws Exception {
        var user=createUser("login", "USER"); String phone=number();
        phoneMapper.insertPhone(UUID.randomUUID(),user.actorIdentity().userId(),phone);
        var request=browser(); var view=issue(phone,SmsPurpose.LOGIN,request,null);
        var session=(MockHttpSession)request.getSession(); String previous=session.getId();
        var csrf=mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var payload=json.writeValueAsString(new SmsRequests.Login(phone,view.challengeId(),"123456"));
        var response=mvc.perform(post("/api/v1/auth/phone/login").session(session).cookie(csrf)
                .header("X-XSRF-TOKEN",csrf.getValue()).contentType(APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.phoneBound").value(true)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(phone,phone.substring(3));
        assertThat(session.getId()).isNotEqualTo(previous);
        // 登录响应先删除旧Cookie再写新Cookie，浏览器使用最后一条，不能拿删除标记重放。
        var fresh=Arrays.stream(response.getResponse().getCookies()).filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()) && !cookie.getValue().isBlank())
                .reduce((first,last) -> last).orElseThrow();
        mvc.perform(post("/api/v1/auth/phone/login").session(session).cookie(fresh).header("X-XSRF-TOKEN",fresh.getValue())
                .contentType(APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
    }

    @Test void phonePasswordEmailAndNumericUsernameRemainDistinct() throws Exception {
        var user = createUser("password", "PLATFORM_ADMIN"); String phone = number();
        var phoneIdentity = UUID.randomUUID(); var usernameIdentity = UUID.randomUUID();
        phoneMapper.insertPhone(phoneIdentity, user.actorIdentity().userId(), phone);
        provision.insertUsernameIdentityIfAbsent(usernameIdentity, user.actorIdentity().userId(), phone.substring(3));
        assertThat(((AuthenticatedUser) details.loadUserByUsername(phone.substring(3))).getIdentityId()).isEqualTo(usernameIdentity);
        assertThat(((AuthenticatedUser) details.loadUserByUsername("PHONE:"+phone)).getIdentityId()).isEqualTo(phoneIdentity);
        for (var body : List.of(new LoginRequest(user.getUsername(), PASSWORD, ""),
                new LoginRequest(phone.substring(3), PASSWORD, ""), new LoginRequest(phone, PASSWORD, "", UserIdentityType.PHONE))) {
            var session = new MockHttpSession();
            mvc.perform(get("/api/v1/auth/captcha").session(session)).andExpect(status().isOk());
            var answer = (String) session.getAttribute(LoginCaptchaService.ATTRIBUTE);
            var csrf = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
            mvc.perform(post("/api/v1/auth/login").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(APPLICATION_JSON).content(json.writeValueAsString(new LoginRequest(body.identifier(), PASSWORD, answer, body.identityType()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(user.actorIdentity().userId().toString()));
        }
    }

    @Test void unregisteredSmsLoginNeverCreatesAnAccount() {
        String phone=number(); var row=verified(SmsPurpose.LOGIN,phone,null); var counts=fixture.counts();
        assertThat(complete(row,phone,null).code()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(fixture.counts()).isEqualTo(counts);
    }

    @Test void concurrentFirstBindingsCannotProduceTwoActivePhones() throws Exception {
        var user=createUser("parallel-bind","USER"); String a=number(),b=number();
        var left=verified(SmsPurpose.BIND,a,user); var right=verified(SmsPurpose.BIND,b,user);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> complete(left,a,user)); var second=pool.submit(() -> complete(right,b,user));
            assertThat(List.of(first.get(10,TimeUnit.SECONDS).code(),second.get(10,TimeUnit.SECONDS).code()))
                    .containsExactlyInAnyOrder("OK","PHONE_BINDING_EXISTS");
        }
    }

    @Test void databaseConstraintsRejectPhoneIssuerBypassAndDuplicatePhone() {
        var user=createUser("constraints","USER"); String phone=number();
        assertThatThrownBy(() -> provision.insertUserIdentity(UUID.randomUUID(),user.actorIdentity().userId(),"PHONE","other-provider",phone,phone,"ACTIVE",OffsetDateTime.now()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(phoneMapper.insertPhone(UUID.randomUUID(),user.actorIdentity().userId(),phone)).isEqualTo(1);
        assertThat(phoneMapper.insertPhone(UUID.randomUUID(),user.actorIdentity().userId(),phone)).isZero();
        assertThatThrownBy(() -> phoneMapper.insertPhone(UUID.randomUUID(),user.actorIdentity().userId(),number()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void sharedRedisCaptchaHasFiveMinuteExpiryAndOnlyOneConsumer() throws Exception {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("redis", redis);
        var shared = new LoginCaptchaServiceImpl(factory.getBeanProvider(StringRedisTemplate.class), true);
        var original = browser();
        shared.issue(original);
        var answer = (String) original.getSession().getAttribute(LoginCaptchaService.ATTRIBUTE);
        String attribute = LoginCaptchaService.ATTRIBUTE + "_ID";
        var id = original.getSession().getAttribute(attribute);
        assertThat(redis.getExpire("prompt-optimizer:captcha:" + id, TimeUnit.SECONDS)).isBetween(250L, 300L);
        // 模拟两个实例同时读取尚未保存变更的相同 Session 快照；Redis 是唯一消费竞争点。
        var snapshot = browser(); snapshot.getSession().setAttribute(attribute, id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(1);
            var first = pool.submit(() -> consumeCaptcha(shared, original, answer, ready));
            var second = pool.submit(() -> consumeCaptcha(shared, snapshot, answer, ready));
            ready.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        shared.issue(original);
        String nextId = (String) original.getSession().getAttribute(attribute);
        redis.expire("prompt-optimizer:captcha:" + nextId, Duration.ZERO);
        assertThatThrownBy(() -> shared.verifyAndConsume(original,
                (String) original.getSession().getAttribute(LoginCaptchaService.ATTRIBUTE))).isInstanceOf(LoginGuardException.class);
    }

    @Test void uncertainCloudVerificationCannotBeRetriedOrResetBudget() {
        String phone = number(); var request = browser();
        var view = issue(phone, SmsPurpose.REGISTER, request, null);
        when(cloud.verify(anyString(), anyString(), anyString(), anyString())).thenThrow(SmsException.unavailable());
        var body = new SmsRequests.Registration(phone, view.challengeId(), "123456", PASSWORD);
        assertThatThrownBy(() -> service.register(body, request)).isInstanceOf(SmsException.class);
        assertThatThrownBy(() -> service.register(body, request)).isInstanceOf(SmsException.class);
        assertThat(challengeMapper.find(view.challengeId()).state()).isEqualTo(SmsChallengeState.FAILED);
        verify(cloud, times(1)).verify(anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> issue(phone, SmsPurpose.REGISTER, request, null)).isInstanceOf(SmsException.class);
        verify(cloud, times(1)).send(anyString(), anyString(), anyString(), anyString());
    }

    private static boolean consumeCaptcha(LoginCaptchaService service, MockHttpServletRequest request,
            String answer, CountDownLatch ready) throws InterruptedException {
        ready.await();
        try { service.verifyAndConsume(request, answer); return true; }
        catch (LoginGuardException exception) { return false; }
    }

    private SmsRequests.ChallengeView issue(String phone, SmsPurpose purpose, MockHttpServletRequest request, AuthenticatedUser actor) {
        captcha.issue(request); String answer=(String)request.getSession().getAttribute(LoginCaptchaService.ATTRIBUTE);
        return service.issue(phone,purpose,answer,actor,actor==null?null:PASSWORD,request);
    }
    private static MockHttpServletRequest browser() { var r=new MockHttpServletRequest(); r.setSession(new MockHttpSession()); r.setRemoteAddr("127.0.0.1"); return r; }
    private static String number() { return "+861380000"+String.format("%04d",NUMBERS.incrementAndGet()); }
    private SmsChallenge verified(SmsPurpose purpose,String phone,AuthenticatedUser actor) {
        var row=challenges.create(purpose,fingerprints.of("phone",phone),"browser",actor==null?null:actor.actorIdentity().userId(),properties.scheme(purpose));
        challengeMapper.markSent(row.id()); UUID token=UUID.randomUUID();
        challenges.reserve(row.id(),purpose,row.phoneFingerprint(),"browser",row.actorUserId(),token);
        challengeMapper.verified(row.id(),token,true); return challengeMapper.find(row.id());
    }
    private SmsAccountTransactions.Outcome complete(SmsChallenge row,String phone,AuthenticatedUser actor) {
        return accounts.complete(row.id(),row.purpose(),phone,row.phoneFingerprint(),row.browserFingerprint(),actor,encoder.encode(PASSWORD),PASSWORD);
    }
    private AuthenticatedUser createUser(String name,String role) {
        UUID user=UUID.randomUUID(),tenant=UUID.randomUUID(),workspace=UUID.randomUUID(),identity=UUID.randomUUID();
        String email=name+user+"@example.com";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            provision.insertTenant(tenant,"Synthetic"); provision.insertUserAccount(user,tenant,email,"Synthetic",encoder.encode(PASSWORD),role);
            provision.insertWorkspace(workspace,tenant,"Synthetic","Synthetic",user); provision.insertWorkspaceMember(workspace,user,"OWNER");
            provision.insertUserIdentity(identity,user,"EMAIL","local",email,email,"ACTIVE",OffsetDateTime.now());
            fixture.history(UUID.randomUUID(),tenant,workspace,user);
        });
        return (AuthenticatedUser)details.loadUserByUsername(email);
    }
}
