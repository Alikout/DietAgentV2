package com.diet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 * token 的 subject 即 userId，role claim 承载 USER/ADMIN 角色，服务端按角色做路径级鉴权；
 * 有效期 2 小时。签名密钥来自 JWT_SECRET 环境变量（至少 32 字符），默认值仅供本地开发。
 */
@Component
public class JwtService {

    /** token 有效期：2 小时，过期强制重新登录（配合短有效期降低密钥泄漏影响面）。 */
    private static final long TTL_MS = 2 * 60 * 60 * 1000L;

    /** HS256 签名密钥。 */
    private final SecretKey key;

    public JwtService(@Value("${diet.jwt.secret:dev-only-diet-agent-jwt-secret-key-change-in-production-0123456789}") String secret) {
        // HMAC 密钥要求至少 256 位（32 字节），不足时 Keys.hmacShaKeyFor 直接抛异常（fail-fast）
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 为指定用户签发 token（role 参与角色鉴权）。 */
    public String issue(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))          // subject 承载 userId
                .claim("username", username)              // username 仅作展示，不参与鉴权
                .claim("role", role)                      // 角色声明，过滤器转成 ROLE_ 前缀 authority
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(TTL_MS)))
                .signWith(key)
                .compact();
    }

    /** 校验 token 签名与有效期，返回身份（userId + 角色）；无效 token 抛 JwtException 由过滤器转匿名。 */
    public JwtIdentity verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new JwtIdentity(Long.valueOf(claims.getSubject()), claims.get("role", String.class));
    }

    /** token 解析出的身份：userId + 角色。 */
    public record JwtIdentity(Long userId, String role) {
    }
}
