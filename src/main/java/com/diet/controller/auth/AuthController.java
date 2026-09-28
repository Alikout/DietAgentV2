package com.diet.controller.auth;

import com.diet.common.model.web.AuthRequest;
import com.diet.common.model.web.AuthResponse;
import com.diet.security.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册与登录（公开端点，返回 JWT）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** 认证服务。 */
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** POST /api/v1/auth/register — 注册并直接登录，返回 token。 */
    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRequest request) {
        return authService.register(request == null ? null : request.username(), request == null ? null : request.password());
    }

    /** POST /api/v1/auth/login — 登录，返回 token。 */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        return authService.login(request == null ? null : request.username(), request == null ? null : request.password());
    }
}
