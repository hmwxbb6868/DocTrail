package com.doctrail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

//标记这个类是配置类，作用：告诉 Spring，这个类里面可以写@Bean方法，用来注册 Bean 到 Spring 容器。
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {
//    BCryptPasswordEncoder 这个类是 SpringSecurity 自带的，
//    我们不能直接去修改它的源码加@Component，所以用@Bean方式注册。
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
