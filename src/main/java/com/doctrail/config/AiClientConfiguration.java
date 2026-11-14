package com.doctrail.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.doctrail.service.ai.client.AiHealthClient;
import com.doctrail.service.ai.client.AiAskClient;
import com.doctrail.service.ai.client.AiParseClient;
import com.doctrail.service.ai.client.AiDeleteClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/*
AiClientConfiguration，AiServiceProperties负责创建和配置 AiHealthClient，
而 AiHealthClient 负责真正调用 Python 健康接口。
读取 yml 里 AI 服务连接配置，创建带超时保护的 HTTP 客户端，
创建严格 JSON 契约校验器，最后组装成健康检查客户端，交给 Spring 管理。
* */



//SpringBoot 推荐写法，关闭代理，提升启动性能
@Configuration(proxyBeanMethods = false)
//请读取 AiServiceProperties，并根据它创建客户端相关的 Bean。
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiClientConfiguration {

    private ObjectMapper contractObjectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

// `@Bean`：标记这个方法，**方法返回的对象交给 Spring 容器管理，变成 Bean**。
    @Bean
    RestClient aiRestClient(AiServiceProperties properties) {
//        SimpleClientHttpRequestFactory：Spring 底层 http 请求工厂，
//        RestClient 只是上层封装 API，**实际发 TCP、HTTP 网络请求，全部由这个工厂来干**。
//        可以设置连接超时、读取超时。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
//        建立 TCP 连接的最长等待时间
        requestFactory.setConnectTimeout(
                Math.toIntExact(properties.connectTimeout().toMillis())
        );
//        读取远程服务的最长等待时间
        requestFactory.setReadTimeout(
                Math.toIntExact(properties.readTimeout().toMillis())
        );
//        创建 RestClient，并设置基础 URL、请求工厂

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
//`(RestClient aiRestClient)`：
// Spring 自动注入**上一个方法创建好的 RestClient Bean**，依赖自动装配。
//    创建 Jackson 的 ObjectMapper（JSON 解析器），专门用来做**契约校验**：
//1. `JsonMapper.builder()`：构建 JsonMapper
//2. `findAndAddModules()`：自动加载 jackson 扩展模块，支持时间、Java 新类型等序列化
//3. `enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`
//👉 **重点！** 解析远端返回 JSON 的时候，如果 JSON 里面有我们实体类**没有定义的字段，直接抛出异常**。
    @Bean
    AiHealthClient aiHealthClient(RestClient aiRestClient) {
        return new AiHealthClient(aiRestClient, contractObjectMapper());
    }

    @Bean
    AiAskClient aiAskClient(
            RestClient aiRestClient,
            @Value("${doctrail.ai-service.internal-token:}") String internalToken
    ) {
        return new AiAskClient(aiRestClient, contractObjectMapper(), internalToken);
    }

    @Bean
    AiParseClient aiParseClient(
            RestClient aiRestClient,
            @Value("${doctrail.ai-service.internal-token:}") String internalToken
    ) {
        return new AiParseClient(aiRestClient, contractObjectMapper(), internalToken);
    }

    @Bean
    AiDeleteClient aiDeleteClient(
            RestClient aiRestClient,
            @Value("${doctrail.ai-service.internal-token:}") String internalToken
    ) {
        return new AiDeleteClient(aiRestClient, contractObjectMapper(), internalToken);
    }
}
