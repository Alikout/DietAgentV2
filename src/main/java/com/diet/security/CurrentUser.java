package com.diet.security;

import com.diet.exception.DietException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前用户读取工具：Controller 层统一从 SecurityContext 取 userId（JWT subject），
 * 替代可伪造的 X-User-Id 请求头。service 层的属主校验签名不变，改动收敛在 Controller 一层。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 返回当前登录用户 ID；未认证时抛业务异常（正常情况下授权规则已先拦截为 401）。 */
    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new DietException("未登录或凭证无效");
    }
}
