package com.doctrail.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Enables persistence only for the one-shot stage-2 account bootstrap. */
//basePackageClasses：通过 Class 类反向拿到这个类所在的包，扫描这个包下面所有 Mapper 接口。
//这里指定了两个：UserAccountMapper.class、AdminAccountMapper.class
//后面代码就可以直接构造注入 UserAccountMapper、AdminAccountMapper使用相当于每一个 Mapper 接口上面单独加@Mapper注解。
@Configuration(proxyBeanMethods = false)
@Profile("stage2-auth-bootstrap")
public class StageTwoAuthBootstrapConfiguration {
}
