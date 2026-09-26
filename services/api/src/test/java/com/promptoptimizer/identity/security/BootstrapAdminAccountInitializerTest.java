package com.promptoptimizer.identity.security;

import com.promptoptimizer.identity.mapper.IdentityProvisioningMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 MyBatis 初始化仍创建独立的邮箱管理员与用户名备用管理员。 */
class BootstrapAdminAccountInitializerTest {

    @Test
    @SuppressWarnings("unchecked")
    void createsSeparatePrimaryAndBackupAdminAccountsWithoutLoggingCredentials() {
        IdentityProvisioningMapper mapper = mock(IdentityProvisioningMapper.class);
        ObjectProvider<IdentityProvisioningMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        when(mapper.selectAdminBootstrapConsumedForUpdate()).thenReturn(false);
        when(mapper.countPlatformAdmins()).thenReturn(0);
        when(mapper.existsIdentity(anyString(), anyString(), anyString())).thenReturn(false);

        var encoder = new BCryptPasswordEncoder(4);
        var initializer = new BootstrapAdminAccountInitializer(
                provider, encoder, true, "admin", "primary@example.com", "Admin", "test-only-password1");

        initializer.run(null);

        ArgumentCaptor<UUID> userIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> emails = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> passwordHashes = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).insertUserAccount(userIds.capture(), any(UUID.class), emails.capture(),
                anyString(), passwordHashes.capture(), eq("PLATFORM_ADMIN"));
        assertThat(userIds.getAllValues()).doesNotHaveDuplicates();
        assertThat(emails.getAllValues()).containsExactly("primary@example.com", null);
        assertThat(passwordHashes.getAllValues())
                .allSatisfy(hash -> assertThat(encoder.matches("test-only-password1", hash)).isTrue());

        ArgumentCaptor<UUID> identityUserIds = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> identityTypes = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).insertUserIdentity(any(UUID.class), identityUserIds.capture(),
                identityTypes.capture(), eq("local"), anyString(), anyString(), eq("ACTIVE"), isNull());
        assertThat(identityUserIds.getAllValues()).containsExactlyElementsOf(userIds.getAllValues());
        assertThat(identityTypes.getAllValues()).containsExactly("EMAIL", "USERNAME");
        verify(mapper, times(2)).insertTenant(any(UUID.class), anyString());
        verify(mapper, times(2)).insertWorkspace(any(UUID.class), any(UUID.class), anyString(), anyString(), any(UUID.class));
        verify(mapper, times(2)).insertWorkspaceMember(any(UUID.class), any(UUID.class), eq("OWNER"));
        verify(mapper).markAdminBootstrapConsumed();
    }

    @Test
    @SuppressWarnings("unchecked")
    void linksConfiguredUsernameToMatchingExistingAdminEvenAfterBootstrapWasConsumed() {
        IdentityProvisioningMapper mapper = mock(IdentityProvisioningMapper.class);
        ObjectProvider<IdentityProvisioningMapper> provider = mock(ObjectProvider.class);
        UUID existingAdminId = UUID.randomUUID();
        when(provider.getIfAvailable()).thenReturn(mapper);
        when(mapper.selectAdminBootstrapConsumedForUpdate()).thenReturn(true);
        when(mapper.selectActivePlatformAdminIdByEmailIdentity("primary@example.com"))
                .thenReturn(existingAdminId);
        when(mapper.existsIdentity("USERNAME", "local", "admin"))
                .thenReturn(false);
        when(mapper.insertUsernameIdentityIfAbsent(any(UUID.class), eq(existingAdminId), eq("admin")))
                .thenReturn(1);

        var initializer = new BootstrapAdminAccountInitializer(
                provider, new BCryptPasswordEncoder(4), true, "Admin", "PRIMARY@example.com", "Admin", "");

        initializer.run(null);

        verify(mapper).insertUsernameIdentityIfAbsent(any(UUID.class), eq(existingAdminId), eq("admin"));
        verify(mapper, never()).insertUserAccount(any(UUID.class), any(UUID.class), any(), anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotCreateConfiguredUsernameWhenItIsAlreadyBound() {
        IdentityProvisioningMapper mapper = mock(IdentityProvisioningMapper.class);
        ObjectProvider<IdentityProvisioningMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        when(mapper.selectAdminBootstrapConsumedForUpdate()).thenReturn(true);
        when(mapper.selectActivePlatformAdminIdByEmailIdentity("primary@example.com"))
                .thenReturn(UUID.randomUUID());
        when(mapper.existsIdentity("USERNAME", "local", "admin")).thenReturn(true);

        var initializer = new BootstrapAdminAccountInitializer(
                provider, new BCryptPasswordEncoder(4), true, "admin", "primary@example.com", "Admin", "");

        initializer.run(null);

        verify(mapper, never()).insertUsernameIdentityIfAbsent(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotLinkConfiguredUsernameWhenNoMatchingActiveAdminExists() {
        IdentityProvisioningMapper mapper = mock(IdentityProvisioningMapper.class);
        ObjectProvider<IdentityProvisioningMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        when(mapper.selectAdminBootstrapConsumedForUpdate()).thenReturn(true);
        when(mapper.selectActivePlatformAdminIdByEmailIdentity("primary@example.com")).thenReturn(null);

        var initializer = new BootstrapAdminAccountInitializer(
                provider, new BCryptPasswordEncoder(4), true, "admin", "primary@example.com", "Admin", "");

        initializer.run(null);

        verify(mapper, never()).existsIdentity(anyString(), anyString(), anyString());
        verify(mapper, never()).insertUsernameIdentityIfAbsent(any(UUID.class), any(UUID.class), anyString());
    }
}
