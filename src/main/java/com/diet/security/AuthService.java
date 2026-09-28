package com.diet.security;

import com.diet.common.model.web.AuthResponse;
import com.diet.exception.DietException;
import com.diet.mapper.UserMapper;
import com.diet.common.model.row.UserRow;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务：注册与登录，签发 JWT。
 * 密码用 BCrypt 单向哈希存储；服务端身份只信任 token，不再信任可伪造的 X-User-Id 头。
 */
@Service
public class AuthService {

    /** 用户 Mapper，读写 diet_user 表。 */
    private final UserMapper userMapper;

    /** BCrypt 密码编码器（SecurityConfig 提供 Bean）。 */
    private final PasswordEncoder passwordEncoder;

    /** JWT 签发与校验。 */
    private final JwtService jwtService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** 注册：用户名唯一，密码至少 6 位，BCrypt 哈希后入库，成功即返回 token。 */
    @Transactional
    public AuthResponse register(String username, String password) {
        // 基本合法性校验：用户名非空、密码长度下限
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new DietException("用户名不能为空，密码至少 6 位");
        }
        String normalized = username.trim();
        // 用户名唯一性校验（数据库另有 uk_user_username 兜底）
        if (userMapper.findByUsername(normalized) != null) {
            throw new DietException("用户名已存在");
        }
        // 角色授予（双保险之"自动"）：系统中还没有任何管理员时，该账号自动成为 ADMIN；
        // 已有管理员则默认 USER（手工提升走 upgrade_add_user_role.sql 中的 SQL 语句）
        String role = userMapper.countByRole("ADMIN") == 0 ? "ADMIN" : "USER";
        // BCrypt 单向哈希后入库
        UserRow row = new UserRow();
        row.setUsername(normalized);
        row.setPasswordHash(passwordEncoder.encode(password));
        row.setRole(role);
        userMapper.insert(row);
        // 注册即登录：直接签发 token
        return new AuthResponse(jwtService.issue(row.getId(), normalized, role), row.getId(), normalized, role);
    }

    /** 登录：校验 BCrypt 密码后签发 token；用户不存在与密码错误返回同一文案，避免用户名枚举。 */
    public AuthResponse login(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            throw new DietException("用户名或密码错误");
        }
        UserRow user = userMapper.findByUsername(username.trim());
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new DietException("用户名或密码错误");
        }
        return new AuthResponse(jwtService.issue(user.getId(), user.getUsername(), user.getRole()),
                user.getId(), user.getUsername(), user.getRole());
    }
}
