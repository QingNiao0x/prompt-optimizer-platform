package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.domain.UserIdentityStatus;
import com.promptoptimizer.identity.domain.UserIdentityType;
import com.promptoptimizer.identity.entity.UserAccountEntity;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.identity.entity.UserIdentityEntity;
import com.promptoptimizer.identity.mapper.UserIdentityMapper;
import com.promptoptimizer.identity.support.TestActors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class IdentitySecurityTest {
    private final UserAccountMapper repository = mock(UserAccountMapper.class);
    private final UserIdentityMapper identityRepository = mock(UserIdentityMapper.class);

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void currentActorRejectsAnonymousAndArbitraryPrincipals() {
        SecurityContextCurrentActor actor = new SecurityContextCurrentActor();
        assertThatThrownBy(actor::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatThrownBy(actor::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "spoofed-user-id", null, List.of()));
        assertThatThrownBy(actor::require).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void databaseIdentityComesFromAccountAndMembershipNotRequestIds() {
        UserAccountEntity account = account();
        when(identityRepository.selectByLoginKey(any(), any(), any())).thenReturn(emailIdentity());
        when(repository.selectById(TestActors.USER_ID)).thenReturn(account);
        when(repository.selectDefaultWorkspaceId(TestActors.USER_ID, TestActors.TENANT_ID))
                .thenReturn(TestActors.WORKSPACE_ID);
        DatabaseUserDetailsService service = new DatabaseUserDetailsService(repository, identityRepository);
        AuthenticatedUser user = (AuthenticatedUser) service.loadUserByUsername(" ALICE@EXAMPLE.COM ");
        assertThat(user.actorIdentity().userId()).isEqualTo(TestActors.USER_ID);
        assertThat(user.actorIdentity().workspaceId()).isEqualTo(TestActors.WORKSPACE_ID);
        when(repository.selectDefaultWorkspaceId(TestActors.USER_ID, TestActors.TENANT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.loadUserByUsername("alice@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
        account.setPasswordHash(null);
        assertThatThrownBy(() -> service.loadUserByUsername("alice@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void databaseIdentityLogsUnknownIdentityTypeWithoutLoggingIdentifier(CapturedOutput output) {
        DatabaseUserDetailsService service = new DatabaseUserDetailsService(repository, identityRepository);
        MDC.put("requestId", "unit-auth-lookup-1");
        try {
            assertThatThrownBy(() -> service.loadUserByUsername("missing@example.com"))
                    .isInstanceOf(UsernameNotFoundException.class);
        } finally {
            MDC.remove("requestId");
        }
        assertThat(output)
                .contains("登录身份未通过校验")
                .contains("requestId=unit-auth-lookup-1")
                .contains("身份类型=EMAIL")
                .contains("原因=IDENTITY_NOT_FOUND")
                .doesNotContain("missing@example.com");
        verifyNoInteractions(repository);
    }

    @Test
    void databaseIdentityLogsRevokedIdentityAndRejectsItBeforeLoadingAccount(CapturedOutput output) {
        UserIdentityEntity revoked = emailIdentity();
        revoked.setStatus(UserIdentityStatus.REVOKED);
        when(identityRepository.selectByLoginKey(any(), any(), any())).thenReturn(revoked);
        DatabaseUserDetailsService service = new DatabaseUserDetailsService(repository, identityRepository);

        MDC.put("requestId", "unit-auth-revoked-1");
        try {
            assertThatThrownBy(() -> service.loadUserByUsername("revoked@example.com"))
                    .isInstanceOf(UsernameNotFoundException.class);
        } finally {
            MDC.remove("requestId");
        }

        assertThat(output)
                .contains("requestId=unit-auth-revoked-1")
                .contains("身份类型=EMAIL")
                .contains("原因=IDENTITY_REVOKED")
                .doesNotContain("revoked@example.com");
        verifyNoInteractions(repository);
    }

    @Test
    void usernameOnlyAdminCanLoadWithoutContactEmail() {
        UserAccountEntity account = account();
        account.setEmail(null);
        account.setPlatformRole("PLATFORM_ADMIN");
        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUserId(TestActors.USER_ID);
        identity.setIdentityType(UserIdentityType.USERNAME);
        identity.setIssuer("local");
        identity.setNormalizedIdentifier("admin");
        identity.setStatus(UserIdentityStatus.ACTIVE);
        when(identityRepository.selectByLoginKey(any(), any(), any())).thenReturn(identity);
        when(repository.selectById(TestActors.USER_ID)).thenReturn(account);
        when(repository.selectDefaultWorkspaceId(TestActors.USER_ID, TestActors.TENANT_ID))
                .thenReturn(TestActors.WORKSPACE_ID);

        AuthenticatedUser user = (AuthenticatedUser) new DatabaseUserDetailsService(repository, identityRepository)
                .loadUserByUsername(" ADMIN ");

        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.actorIdentity().email()).isEmpty();
        assertThat(user.getAuthorities()).extracting("authority")
                .contains("ROLE_PLATFORM_ADMIN");
    }

    @Test
    void bootstrapNeverOverwritesExistingPasswordAndHashesOnlyOnce() {
        UserAccountEntity account = account();
        stubEmailIdentity(account);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        BootstrapUserPasswordInitializer initializer = new BootstrapUserPasswordInitializer(
                repository, identityRepository, encoder, "alice@example.com", "test-only-password1"
        );
        initializer.run(null);
        verify(repository, never()).updatePasswordHash(any(), anyString());
        account.setPasswordHash(null);
        doAnswer(invocation -> {
            account.setPasswordHash(invocation.getArgument(1));
            return 1;
        }).when(repository).updatePasswordHash(eq(TestActors.USER_ID), anyString());
        initializer.run(null);
        assertThat(encoder.matches("test-only-password1", account.getPasswordHash())).isTrue();
        initializer.run(null);
        verify(repository, times(1)).updatePasswordHash(eq(TestActors.USER_ID), anyString());
    }

    @Test
    void bootstrapRejectsPasswordThatBcryptWouldTruncate() {
        UserAccountEntity account = account();
        account.setPasswordHash(null);
        stubEmailIdentity(account);
        BootstrapUserPasswordInitializer initializer = new BootstrapUserPasswordInitializer(repository,
                identityRepository, new BCryptPasswordEncoder(4), "alice@example.com", "密".repeat(25));
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).updatePasswordHash(any(), anyString());
    }

    @Test
    void corsIsDisabledWhenNoAllowedOriginIsConfigured() {
        CorsConfigurationSource source = new SecurityConfiguration().corsConfigurationSource("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "http://localhost:5173");

        assertThat(source.getCorsConfiguration(request)).isNull();
    }

    private UserAccountEntity account() {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(TestActors.USER_ID);
        account.setTenantId(TestActors.TENANT_ID);
        account.setEmail("alice@example.com");
        account.setDisplayName("Alice");
        account.setPasswordHash("already-configured");
        account.setStatus("ACTIVE");
        return account;
    }

    private UserIdentityEntity emailIdentity() {
        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setId(TestActors.USER_ID);
        identity.setUserId(TestActors.USER_ID);
        identity.setIdentityType(UserIdentityType.EMAIL);
        identity.setIssuer("local");
        identity.setIdentifier("alice@example.com");
        identity.setNormalizedIdentifier("alice@example.com");
        identity.setStatus(UserIdentityStatus.ACTIVE);
        return identity;
    }

    private void stubEmailIdentity(UserAccountEntity account) {
        when(identityRepository.selectByLoginKey(any(), any(), any())).thenReturn(emailIdentity());
        when(repository.selectById(TestActors.USER_ID)).thenReturn(account);
    }
}
