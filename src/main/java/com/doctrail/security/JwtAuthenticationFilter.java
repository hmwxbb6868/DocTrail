package com.doctrail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;


/*
* 从请求头提取 Bearer Token，解析 JWT，校验 token 合法后，
* 组装 Authentication 对象，放进 Security 上下文，告诉 Spring Security：当前用户已经登录。
* */

//怎么生效？@Component把 `JwtAuthenticationFilter` 交给 Spring 容器，变成 Bean
@Component
@Profile("stage2-auth")
public class JwtAuthenticationFilter extends OncePerRequestFilter {
//    `JwtAuthenticationFilter extends OncePerRequestFilter`，本质是 Servlet 规范的 Filter。
    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

//    过滤器链（一串按顺序执行的过滤器）**行，filterChain.doFilter(request, response)，把 request 和 response 传给下一个过滤器**。
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 拿到请求头 Authorization
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() > 7) {
            try {
//                // 3. 截取字符串，去掉前面的 "Bearer "，拿到原始token字符串
                AuthenticatedIdentity identity = jwtService.parse(header.substring(7).trim());
                //`UsernamePasswordAuthenticationToken` 是 Spring Security 自带的类，用来代表**当前登录用户的认证信息**
                var authentication = new UsernamePasswordAuthenticationToken(
                        identity,
                        null,
//                        权限列表（因为一个用户可以同时拥有多个角色 / 权限）：把角色`ADMIN`/`USER`拼接成`ROLE_ADMIN` / `ROLE_USER`，
//                        Spring Security 要求角色必须带`ROLE_`前缀。
                        List.of(new SimpleGrantedAuthority("ROLE_" + identity.role()))
                );
                //把本次请求的附加信息（访问 IP 等）放到认证对象里，属于附加信息
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                //`SecurityContextHolder.getContext()`：当前请求的安全上下文盒子。
                //`setAuthentication(authentication)`：把刚才做好的登录认证对象放进盒子
                //Spring Security 看到盒子里面有这个对象，就认为：**这个请求已经登录成功**
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
//                情况：请求头有 Bearer token，但是 token 无效（过期 / 篡改）
//                **清空安全上下文盒子**，盒子变空，代表没有登录。
                SecurityContextHolder.clearContext();
            }
        }
//        情况：请求根本没有 Authorization 头（没有 token）
        // 把当前请求，继续交给过滤器链里面下一个组件继续处理。
        filterChain.doFilter(request, response);
    }
}
