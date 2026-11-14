package com.doctrail.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@Profile("stage2-auth")
public class StageTwoSecurityConfig {
//    - `HttpSecurity http`：Spring Security 的配置建造器，用来配置安全规则。
//   - `JwtAuthenticationFilter jwtFilter`：我们自定义的 JWT 过滤器，Spring 自动注入进来。
//   - `ObjectMapper objectMapper`：Jackson 的 JSON 序列化工具，用来把错误信息转成 JSON 返回前端。
    @Bean
    SecurityFilterChain stageTwoFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                             ObjectMapper objectMapper) throws Exception {
        return http
//                关闭 CSRF 防护。
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//                `.exceptionHandling(...)` 异常处理器
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, objectMapper, 401, 1001, "unauthorized"))
                        .accessDeniedHandler((request, response, exception) -> writeError(response, objectMapper, 403, 1003, "forbidden")))
                .authorizeHttpRequests(authorize -> authorize
                        // Swagger UI and its OpenAPI JSON must remain reachable so local API tests can be performed.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        //第一组：所有/api/auth/** 接口，不需要登录
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/captcha",
                                "/api/auth/refresh", "/api/auth/logout",
                                "/api/admin/auth/login", "/health/**", "/error").permitAll()
                        //第二组：所有/api/admin/** 接口，必须登录且角色是ADMIN
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        //普通用户会话接口不能被管理员身份复用；conversation.user_id 引用 user 表。
                        .requestMatchers("/api/chat/**").hasRole("USER")
                        //第三组：所有/api/** 接口，必须登录（任意角色USER/ADMIN都可以）
                        .requestMatchers("/api/**").authenticated()
                        // 第四组：剩下所有其他请求，全部拒绝访问
                        .anyRequest().denyAll())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
//这是一个**通用工具方法**，当鉴权出现异常（401 未授权 /403 权限不足）的时候调用。
    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper,
                                   int status, int code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Result.failure(code, message)));
    }
}
