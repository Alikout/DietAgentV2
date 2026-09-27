package com.diet.security;

import com.diet.exception.DietException;
import com.diet.mapper.UserMapper;
import com.diet.model.row.UserRow;
import com.diet.model.web.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务单元测试：角色授予规则。
 * 注册时系统中尚无任何 ADMIN → 该账号自动成为 ADMIN；已有管理员 → 默认 USER。
 */
class AuthServiceTest {

    /** 被 mock 的用户 Mapper。 */
    private UserMapper userMapper;

    /** 被 mock 的密码编码器。 */
    private PasswordEncoder passwordEncoder;

    /** 被 mock 的 JWT 服务。 */
    private JwtService jwtService;

    /** 被测认证服务。 */
    private AuthService authService;

    /** 每个用例前重置 mock 与被测服务。 */
    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        authService = new AuthService(userMapper, passwordEncoder, jwtService);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(jwtService.issue(any(), any(), any())).thenReturn("token");
    }

    /** 系统中没有任何 ADMIN 时，第一个注册账号自动授予 ADMIN 角色。 */
    @Test
    void firstRegistrationBecomesAdminWhenNoAdminExists() {
        when(userMapper.findByUsername("alice")).thenReturn(null);
        when(userMapper.countByRole("ADMIN")).thenReturn(0);

        AuthResponse response = authService.register("alice", "123456");

        assertEquals("ADMIN", response.role());
        verify(userMapper).insert(any());
    }

    /** 已有管理员时，后续注册默认 USER 角色。 */
    @Test
    void subsequentRegistrationDefaultsToUser() {
        when(userMapper.findByUsername("bob")).thenReturn(null);
        when(userMapper.countByRole("ADMIN")).thenReturn(1);

        AuthResponse response = authService.register("bob", "123456");

        assertEquals("USER", response.role());
    }

    /** 重名注册被拒绝（唯一性校验）。 */
    @Test
    void registerRejectsDuplicateUsername() {
        when(userMapper.findByUsername("alice")).thenReturn(new UserRow());

        assertThrows(DietException.class, () -> authService.register("alice", "123456"));
    }
}
