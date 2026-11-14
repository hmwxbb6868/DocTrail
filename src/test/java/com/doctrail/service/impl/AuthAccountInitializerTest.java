package com.doctrail.service.impl;

import com.doctrail.config.AuthBootstrapProperties;
import com.doctrail.entity.AdminAccountEntity;
import com.doctrail.entity.UserAccountEntity;
import com.doctrail.mapper.AdminAccountMapper;
import com.doctrail.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;

class AuthAccountInitializerTest {
    private final UserAccountMapper userMapper = mock(UserAccountMapper.class);
    private final AdminAccountMapper adminMapper = mock(AdminAccountMapper.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void createsConfiguredAccountsWithBcryptHashes() {
        String demoPassword = randomPassword();
        String adminPassword = randomPassword();
        AuthBootstrapProperties properties = properties("demo", demoPassword, "admin", adminPassword);
        AuthAccountInitializer initializer = new AuthAccountInitializer(properties, encoder, userMapper, adminMapper);

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<UserAccountEntity> user = ArgumentCaptor.forClass(UserAccountEntity.class);
        ArgumentCaptor<AdminAccountEntity> admin = ArgumentCaptor.forClass(AdminAccountEntity.class);
        verify(userMapper).insert(user.capture());
        verify(adminMapper).insert(admin.capture());
        assertThat(user.getValue().getPasswordHash()).isNotEqualTo(demoPassword);
        assertThat(encoder.matches(demoPassword, user.getValue().getPasswordHash())).isTrue();
        assertThat(user.getValue().getRole()).isEqualTo("USER");
        assertThat(admin.getValue().getPasswordHash()).isNotEqualTo(adminPassword);
        assertThat(encoder.matches(adminPassword, admin.getValue().getPasswordHash())).isTrue();
        assertThat(admin.getValue().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void leavesMatchingExistingAccountsUntouched() {
        String demoPassword = randomPassword();
        String adminPassword = randomPassword();
        UserAccountEntity user = new UserAccountEntity();
        user.setId(1L);
        user.setUsername("demo");
        user.setPasswordHash(encoder.encode(demoPassword));
        user.setRole("USER");
        AdminAccountEntity admin = new AdminAccountEntity();
        admin.setId(2L);
        admin.setUsername("admin");
        admin.setPasswordHash(encoder.encode(adminPassword));
        admin.setRole("ADMIN");
        when(userMapper.selectByUsername("demo")).thenReturn(user);
        when(adminMapper.selectByUsername("admin")).thenReturn(admin);
        AuthAccountInitializer initializer = new AuthAccountInitializer(
                properties("demo", demoPassword, "admin", adminPassword), encoder, userMapper, adminMapper);

        initializer.run(new DefaultApplicationArguments());

        verify(userMapper, never()).insert(any(UserAccountEntity.class));
        verify(userMapper, never()).updateById(any(UserAccountEntity.class));
        verify(adminMapper, never()).insert(any(AdminAccountEntity.class));
        verify(adminMapper, never()).updateById(any(AdminAccountEntity.class));
    }

    @Test
    void updatesExistingAccountsWhenPasswordOrRoleDoesNotMatch() {
        String demoPassword = randomPassword();
        String adminPassword = randomPassword();
        UserAccountEntity user = new UserAccountEntity();
        user.setId(1L);
        user.setUsername("demo");
        user.setPasswordHash(encoder.encode(randomPassword()));
        user.setRole("ADMIN");
        AdminAccountEntity admin = new AdminAccountEntity();
        admin.setId(2L);
        admin.setUsername("admin");
        admin.setPasswordHash(encoder.encode(randomPassword()));
        admin.setRole("USER");
        when(userMapper.selectByUsername("demo")).thenReturn(user);
        when(adminMapper.selectByUsername("admin")).thenReturn(admin);
        AuthAccountInitializer initializer = new AuthAccountInitializer(
                properties("demo", demoPassword, "admin", adminPassword), encoder, userMapper, adminMapper);

        initializer.run(new DefaultApplicationArguments());

        verify(userMapper).updateById(user);
        verify(adminMapper).updateById(admin);
        assertThat(encoder.matches(demoPassword, user.getPasswordHash())).isTrue();
        assertThat(user.getRole()).isEqualTo("USER");
        assertThat(encoder.matches(adminPassword, admin.getPasswordHash())).isTrue();
        assertThat(admin.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void skipsAccountsWhenCredentialsAreCompletelyEmpty() {
        AuthAccountInitializer initializer = new AuthAccountInitializer(
                properties("", "", "", ""), encoder, userMapper, adminMapper);

        initializer.run(new DefaultApplicationArguments());

        verifyNoInteractions(userMapper, adminMapper);
    }

    @Test
    void rejectsPartiallyConfiguredCredentials() {
        AuthAccountInitializer initializer = new AuthAccountInitializer(
                properties("demo", "", "", ""), encoder, userMapper, adminMapper);

        assertThatIllegalStateException()
                .isThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .withMessageContaining("demo bootstrap credentials");
        verifyNoInteractions(userMapper, adminMapper);
    }

    @Test
    void requiredModeRejectsAnyMissingAccountBeforeWriting() {
        AuthBootstrapProperties properties = properties("demo", randomPassword(), "", "");
        properties.setRequired(true);
        AuthAccountInitializer initializer = new AuthAccountInitializer(
                properties, encoder, userMapper, adminMapper);

        assertThatIllegalStateException()
                .isThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .withMessageContaining("admin bootstrap credentials are required");
        verifyNoInteractions(userMapper, adminMapper);
    }

    private AuthBootstrapProperties properties(String demoUsername, String demoPassword,
                                               String adminUsername, String adminPassword) {
        AuthBootstrapProperties properties = new AuthBootstrapProperties();
        properties.setDemoUsername(demoUsername);
        properties.setDemoPassword(demoPassword);
        properties.setAdminUsername(adminUsername);
        properties.setAdminPassword(adminPassword);
        return properties;
    }

    private String randomPassword() {
        return "Tda-" + UUID.randomUUID() + "-x";
    }
}
