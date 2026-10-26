package com.promptoptimizer.identity.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.persistence.PostgresUuidTypeHandler;
import com.promptoptimizer.identity.domain.*;
import com.promptoptimizer.identity.dto.EmailRegistrationRequest;
import com.promptoptimizer.identity.entity.SmsChallenge;
import com.promptoptimizer.identity.infrastructure.registration.*;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.identity.service.impl.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 在专用回环PostgreSQL数据库运行全部前向迁移，验证昵称持久化、资料合并与身份隔离。
 * 不连接业务库，所有测试资源在同连接事务内回滚，不发送邮件、短信或微信请求。
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfEnvironmentVariable(named="NICKNAME_TEST_DB_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:55447/nickname_acceptance")
class NicknameProfileDatabaseTest {
    private static SqlSessionFactory sessions;
    private SqlSession session;
    private IdentityProvisioningMapper provision;
    private UserIdentityMapper identities;
    private UserAccountMapper users;
    private NicknameFixtureMapper fixture;

    @BeforeAll
    static void migrateAndLoadMappers() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("NICKNAME_TEST_DB_URL"), "nickname_test", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        var configuration = new Configuration(new Environment("nickname-acceptance", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, PostgresUuidTypeHandler.class);
        for (String name : new String[] {"IdentityProvisioningMapper", "UserIdentityMapper", "UserAccountMapper", "SmsChallengeMapper", "SmsAccountMapper", "NicknameFixtureMapper"}) {
            String path = "mapper/identity/" + name + ".xml";
            try (var input = NicknameProfileDatabaseTest.class.getClassLoader().getResourceAsStream(path)) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void openTransaction() {
        session = sessions.openSession(false);
        provision = session.getMapper(IdentityProvisioningMapper.class);
        identities = session.getMapper(UserIdentityMapper.class);
        users = session.getMapper(UserAccountMapper.class);
        fixture = session.getMapper(NicknameFixtureMapper.class);
    }

    @AfterEach
    void rollbackFixtures() { session.rollback(); session.close(); }

    @Test
    void emailRegistrationStoresNicknameAndKeepsDefaultWhenOmitted() {
        var beans = new StaticListableBeanFactory(); beans.addBean("provision", provision);
        var gateway = new MybatisAccountRegistrationGateway(beans.getBeanProvider(IdentityProvisioningMapper.class));
        var properties = new RegistrationProperties(); properties.setVerificationSecret("synthetic-nickname-verification-key-only-for-tests");
        var codes = mock(EmailVerificationStore.class);
        when(codes.verify(anyString(), anyString())).thenReturn(EmailVerificationStore.VerificationResult.VALID);
        var passwords = mock(PasswordEncoder.class); when(passwords.encode(anyString())).thenReturn("synthetic-hash");
        var registration = new EmailRegistrationServiceImpl(identities, codes, mock(VerificationEmailSender.class), properties, gateway, passwords);
        registration.register(new EmailRegistrationRequest("nickname@example.test", "123456", "Password1", "  星河🌟  "));
        registration.register(new EmailRegistrationRequest("default@example.test", "123456", "Password1"));
        var named = identities.selectByLoginKey(UserIdentityType.EMAIL, "local", "nickname@example.test");
        assertThat(users.selectById(named.getUserId()).getDisplayName()).isEqualTo("星河🌟");
        assertThat(named.getProviderProfileJson()).isEqualTo("{}");
        var generated = identities.selectByLoginKey(UserIdentityType.EMAIL, "local", "default@example.test");
        assertThat(users.selectById(generated.getUserId()).getDisplayName()).isEqualTo("default");
    }

    @Test
    void phoneRegistrationStoresTheNicknameInTheSameAccountTransaction() {
        var challenges = session.getMapper(SmsChallengeMapper.class);
        UUID id = UUID.randomUUID(); OffsetDateTime now = OffsetDateTime.now();
        challenges.insert(new SmsChallenge(id, SmsPurpose.REGISTER, "phone-hash", "browser-hash", null, "synthetic", SmsChallengeState.VERIFIED,
                0, null, now.plusMinutes(5), null, null, now));
        var transactions = new SmsAccountTransactions(challenges, session.getMapper(SmsAccountMapper.class), users, identities, provision, mock(PasswordEncoder.class));
        var outcome = transactions.complete(id, SmsPurpose.REGISTER, "+8613800000000", "phone-hash", "browser-hash", null, "synthetic-hash", null, "星河🌟");
        outcome.requireSuccess();
        assertThat(users.selectById(outcome.userId()).getDisplayName()).isEqualTo("星河🌟");
        assertThat(identities.selectByLoginKey(UserIdentityType.PHONE, "local", "+8613800000000").getUserId()).isEqualTo(outcome.userId());
    }

    @Test
    void wechatProfileSyncPreservesCustomNameExtensionKeysAndOtherIdentities() throws Exception {
        UUID account = account("平台自填昵称"), other = account("另一个账号");
        UUID identity = identity(account, "test-app", "Subject-AbC");
        identity(other, "other-app", "Subject-AbC");
        fixture.putAvatar(identity, "https://example.test/avatar.png");
        var synchronizer = new WechatNicknameSynchronizer(identities);
        assertThat(synchronizer.syncVerifiedNickname("test-app", "Subject-AbC", " 微信昵称🌟 ")).isTrue();
        var saved = identities.selectByLoginKey(UserIdentityType.WECHAT, "test-app", "Subject-AbC");
        var profile = new ObjectMapper().readTree(saved.getProviderProfileJson());
        assertThat(profile.path("nickname").asText()).isEqualTo("微信昵称🌟");
        assertThat(profile.path("avatarUrl").asText()).isEqualTo("https://example.test/avatar.png");
        assertThat(OffsetDateTime.parse(profile.path("nicknameSyncedAt").asText()).getOffset().getTotalSeconds()).isZero();
        assertThat(users.selectById(account).getDisplayName()).isEqualTo("平台自填昵称");
        assertThat(users.selectById(account).getPlatformRole()).isEqualTo("USER");
        assertThat(identities.selectByLoginKey(UserIdentityType.WECHAT, "other-app", "Subject-AbC").getProviderProfileJson()).isEqualTo("{}");
        fixture.identityStatus(identity, "REVOKED");
        assertThat(synchronizer.syncVerifiedNickname("test-app", "Subject-AbC", "不能更新")).isFalse();
        fixture.identityStatus(identity, "ACTIVE"); fixture.accountStatus(account, "DISABLED");
        assertThat(synchronizer.syncVerifiedNickname("test-app", "Subject-AbC", "不能更新")).isFalse();
        assertThat(synchronizer.syncVerifiedNickname("test-app", "missing", "不能绑定账号")).isFalse();
    }

    @Test
    void profileDatabaseConstraintRejectsNonObjectJson() {
        UUID id = identity(account("显示昵称"), "test-app", "profile-object");
        assertThatThrownBy(() -> fixture.replaceProfile(id, "[]")).hasStackTraceContaining("ck_user_identity_provider_profile_object");
    }

    private UUID account(String displayName) {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        provision.insertTenant(tenant, "nickname-test");
        provision.insertUserAccount(user, tenant, null, displayName, "synthetic-hash", "USER"); return user;
    }

    private UUID identity(UUID user, String issuer, String subject) {
        UUID id = UUID.randomUUID();
        provision.insertUserIdentity(id, user, "WECHAT", issuer, subject, subject, "ACTIVE", OffsetDateTime.now()); return id;
    }
}
