package com.promptoptimizer.identity.infrastructure.security;

import com.promptoptimizer.identity.infrastructure.persistence.UserAccountEntity;
import com.promptoptimizer.identity.infrastructure.persistence.UserAccountRepository;
import com.promptoptimizer.identity.support.TestActors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class IdentitySecurityTest {
    private final UserAccountRepository repository = mock(UserAccountRepository.class);

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
        when(repository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(account));
        when(repository.findDefaultWorkspaceId(TestActors.USER_ID, TestActors.TENANT_ID))
                .thenReturn(Optional.of(TestActors.WORKSPACE_ID));
        DatabaseUserDetailsService service = new DatabaseUserDetailsService(repository);
        AuthenticatedUser user = (AuthenticatedUser) service.loadUserByUsername(" ALICE@EXAMPLE.COM ");
        assertThat(user.actorIdentity().userId()).isEqualTo(TestActors.USER_ID);
        assertThat(user.actorIdentity().workspaceId()).isEqualTo(TestActors.WORKSPACE_ID);
        when(repository.findDefaultWorkspaceId(TestActors.USER_ID, TestActors.TENANT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("alice@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
        account.setPasswordHash(null);
        assertThatThrownBy(() -> service.loadUserByUsername("alice@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void bootstrapNeverOverwritesExistingPasswordAndHashesOnlyOnce() {
        UserAccountEntity account = account();
        when(repository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(account));
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        BootstrapUserPasswordInitializer initializer = new BootstrapUserPasswordInitializer(repository, encoder,
                "alice@example.com", "test-only-password");
        initializer.run(null);
        verify(repository, never()).save(any());
        account.setPasswordHash(null);
        initializer.run(null);
        assertThat(encoder.matches("test-only-password", account.getPasswordHash())).isTrue();
        initializer.run(null);
        verify(repository, times(1)).save(account);
    }

    @Test
    void bootstrapRejectsPasswordThatBcryptWouldTruncate() {
        UserAccountEntity account = account();
        account.setPasswordHash(null);
        when(repository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(account));
        BootstrapUserPasswordInitializer initializer = new BootstrapUserPasswordInitializer(repository,
                new BCryptPasswordEncoder(4), "alice@example.com", "密".repeat(25));
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
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
}
