package com.diet.security;

import com.diet.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置（第四周鉴权）：无状态 JWT。
 * 授权规则：auth 接口与静态资源放行；/api/v1/** 必须持有效 token；
 * 未认证统一 401（前端据此弹出登录视图）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** JWT 认证过滤器。 */
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    /** BCrypt 密码编码器，AuthService 注册/登录使用。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 无状态安全过滤链：关闭 CSRF（token 天然防 CSRF）、不创建会话。 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 注册/登录公开
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 静态前端资源放行
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/error").permitAll()
                        // 管理员专属：Trace 调试、评估、公共餐食库写操作
                        .requestMatchers("/api/v1/diet/debug/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/diet/evaluations/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/diet/meals/public").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/diet/meals/public/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/diet/meals/public/*").hasRole("ADMIN")
                        // 普通用户专属：对话、会话、反馈、个人餐食（管理员界面不包含这些能力）
                        .requestMatchers(HttpMethod.POST, "/api/v1/diet/chat", "/api/v1/diet/chat/stream").hasRole("USER")
                        .requestMatchers("/api/v1/diet/sessions", "/api/v1/diet/sessions/*/messages").hasRole("USER")
                        .requestMatchers("/api/v1/diet/feedback").hasRole("USER")
                        .requestMatchers("/api/v1/diet/meals/personal", "/api/v1/diet/meals/personal/*").hasRole("USER")
                        // 其余业务接口：登录即可（公共餐食查询、槽位字典等双角色共用）
                        .requestMatchers("/api/v1/**").authenticated()
                        // 其余（静态兜底）放行
                        .anyRequest().permitAll())
                // 未认证统一 401，前端 api.js 据此切换到登录视图
                .exceptionHandling(handling -> handling.authenticationEntryPoint(
                        (request, response, ignored) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未登录或凭证无效")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
