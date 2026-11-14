package com.doctrail.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers MyBatis mappers only for the explicit persistence profiles.
 */
//MyBatis Mapper 扫描配置类
@Configuration(proxyBeanMethods = false)
@Profile({"stage2-auth", "stage2-auth-bootstrap"})
@MapperScan("com.doctrail.mapper")
public class PersistenceMapperConfiguration {
}
