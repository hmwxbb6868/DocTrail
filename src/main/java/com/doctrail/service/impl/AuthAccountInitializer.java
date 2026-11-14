package com.doctrail.service.impl;

import com.doctrail.config.AuthBootstrapProperties;
import com.doctrail.entity.AdminAccountEntity;
import com.doctrail.entity.UserAccountEntity;
import com.doctrail.mapper.AdminAccountMapper;
import com.doctrail.mapper.UserAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/** Creates or reconciles bootstrap accounts without logging credentials. */
/*
* 在 Spring Boot 应用启动完成后，根据配置文件或环境变量，自动创建或修正一个演示用户账号和一个管理员账号。*/
@Component
@Profile("stage2-auth-bootstrap")
public class AuthAccountInitializer implements ApplicationRunner {
//    implements ApplicationRunner表示这个类是一个应用启动后执行器。
//只要 Spring 创建了这个 Bean，应用启动完成后就会自动调用：run(ApplicationArguments args)
    private static final Logger log = LoggerFactory.getLogger(AuthAccountInitializer.class);
//  负责读取配置中的账号和密码
    private final AuthBootstrapProperties properties;
//  负责密码加密
    private final PasswordEncoder passwordEncoder;
//  负责用户账号的增删改查
    private final UserAccountMapper userAccountMapper;
//  负责管理员账号的增删改查
    private final AdminAccountMapper adminAccountMapper;

    public AuthAccountInitializer(
            AuthBootstrapProperties properties,
            PasswordEncoder passwordEncoder,
            UserAccountMapper userAccountMapper,
            AdminAccountMapper adminAccountMapper
    ) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.userAccountMapper = userAccountMapper;
        this.adminAccountMapper = adminAccountMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Credentials demoCredentials = validateCredentials(
                "demo", properties.getDemoUsername(), properties.getDemoPassword());
        Credentials adminCredentials = validateCredentials(
                "admin", properties.getAdminUsername(), properties.getAdminPassword());
        initializeUser(demoCredentials);
        initializeAdmin(adminCredentials);
    }
//  每次项目启动，都会检查预置账号，一旦数据库账号和 yml 配置不一致，自动修正为 yml 配置的状态。
    private void initializeUser(Credentials credentials) {
        if (credentials == null) {
            log.warn("Skipping demo account bootstrap because its credentials are not configured");
            return;
        }
        UserAccountEntity existing = userAccountMapper.selectByUsername(credentials.username());
        if (existing == null) {
            UserAccountEntity account = new UserAccountEntity();
            account.setUsername(credentials.username());
//            passwordEncoder.encode(credentials.password()加密明文密码
            account.setPasswordHash(passwordEncoder.encode(credentials.password()));
            account.setRole("USER");
            userAccountMapper.insert(account);
            log.info("Created demo account '{}'; password value was not logged", credentials.username());
            return;
        }
//        existing.getPasswordHash()：数据库里现存账号存的 BCrypt 哈希字符串；
//        credentials.password()：yml 配置文件里写的明文密码
//        passwordEncoder.matches(明文, 数据库哈希)：用 BCrypt 校验，明文和哈希是否匹配
        if (!passwordEncoder.matches(credentials.password(), existing.getPasswordHash())
                || !"USER".equals(existing.getRole())) {
            existing.setPasswordHash(passwordEncoder.encode(credentials.password()));
            existing.setRole("USER");
            userAccountMapper.updateById(existing);
            log.info("Updated demo account '{}'; password value was not logged", credentials.username());
            return;
        }
        log.info("Demo account '{}' already matches bootstrap credentials", credentials.username());
    }

    private void initializeAdmin(Credentials credentials) {
        if (credentials == null) {
            log.warn("Skipping admin account bootstrap because its credentials are not configured");
            return;
        }
        AdminAccountEntity existing = adminAccountMapper.selectByUsername(credentials.username());
        if (existing == null) {
            AdminAccountEntity account = new AdminAccountEntity();
            account.setUsername(credentials.username());
            account.setPasswordHash(passwordEncoder.encode(credentials.password()));
            account.setRole("ADMIN");
            adminAccountMapper.insert(account);
            log.info("Created admin account '{}'; password value was not logged", credentials.username());
            return;
        }
        if (!passwordEncoder.matches(credentials.password(), existing.getPasswordHash())
                || !"ADMIN".equals(existing.getRole())) {
            existing.setPasswordHash(passwordEncoder.encode(credentials.password()));
            existing.setRole("ADMIN");
            adminAccountMapper.updateById(existing);
            log.info("Updated admin account '{}'; password value was not logged", credentials.username());
            return;
        }
        log.info("Admin account '{}' already matches bootstrap credentials", credentials.username());
    }

    private Credentials validateCredentials(String label, String username, String password) {
        String normalizedUsername = username == null ? "" : username.trim();
        String configuredPassword = password == null ? "" : password;
        if (normalizedUsername.isEmpty() && configuredPassword.isEmpty()) {
            if (properties.isRequired()) {
                throw new IllegalStateException(label + " bootstrap credentials are required in required mode");
            }
            return null;
        }
        if (normalizedUsername.isEmpty() || configuredPassword.isBlank()) {
            throw new IllegalStateException(label + " bootstrap credentials must provide both username and password");
        }
        if (normalizedUsername.length() < 3 || normalizedUsername.length() > 64) {
            throw new IllegalStateException(label + " bootstrap username must contain 3 to 64 characters");
        }
        if (configuredPassword.length() < 6 || configuredPassword.length() > 72
                || configuredPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException(label + " bootstrap password must contain 6 to 72 UTF-8 bytes");
        }
        return new Credentials(normalizedUsername, configuredPassword);
    }

    private record Credentials(String username, String password) {
    }
}
