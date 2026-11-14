package com.doctrail.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

//保存 Python 服务配置，专门用来读取外部配置文件里 AI 服务的连接参数，读取后，配置会变成一个 Java 对象
// 并且在项目启动时校验：这三个配置项必须全部配置，不能缺失，防止程序运行时出现空指针。
@Validated
@ConfigurationProperties(prefix = "doctrail.ai-service")
//`application.yml/application.properties` 里前缀为 `doctrail.ai-service` 的配置，
// 自动注入到这个对象，
// 并且做参数校验：`baseUrl`、`connectTimeout`、`readTimeout` 都不能为 null。
public record AiServiceProperties(
//        `record` 是 Java16 引入的**数据载体类**，专门用来存数据：
//       - 自动生成构造器、getter、equals、hashCode、toString
//       - 属性默认是 `final`，创建之后**不可修改**（只读配置）
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout
) {
}
