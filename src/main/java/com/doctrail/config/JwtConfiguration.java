package com.doctrail.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
//- 根据`JwtProperties`上`@ConfigurationProperties`声明的前缀加载外部配置；
//- 实例化`JwtProperties`对象，完成属性映射；将配置类实例注册为 Spring Bean**。
@Configuration(proxyBeanMethods = false)
@Profile("stage2-auth")
@EnableConfigurationProperties({JwtProperties.class, RefreshTokenProperties.class, AuthPolicyProperties.class})
public class JwtConfiguration {
}
