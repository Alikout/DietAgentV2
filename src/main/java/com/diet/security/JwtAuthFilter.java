package com.diet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：解析 Authorization: Bearer &lt;token&gt;，校验通过后把 userId 写入 SecurityContext。
 * 无效/缺失凭证保持匿名，由 SecurityFilterChain 的授权规则返回 401。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Bearer 前缀长度（"Bearer ".length()）。 */
    private static final int BEARER_PREFIX_LENGTH = 7;

    /** JWT 签发与校验服务。 */
    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 从 Authorization 头提取 Bearer token
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                // 校验签名与有效期，解析 userId + role
                JwtService.JwtIdentity identity = jwtService.verify(header.substring(BEARER_PREFIX_LENGTH));
                // 旧版本 token（升级前签发）没有 role claim：按 USER 兜底，不强制下线
                String role = identity.role() == null || identity.role().isBlank() ? "USER" : identity.role();
                // 已认证标记：principal=userId，authority=ROLE_<role> 供路径级鉴权使用
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(identity.userId(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                // 无效/过期 token：保持匿名，授权规则统一返回 401，不在过滤器层暴露细节
            }
        }
        filterChain.doFilter(request, response);
    }
}
