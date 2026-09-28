package com.diet.common.model.row;

import lombok.Data;

import java.time.LocalDateTime;

/** diet_user 行对象（第四周鉴权引入的最小用户表；角色体系升级后含 role）。 */
@Data
public class UserRow {
    private Long id;
    private String username;
    private String passwordHash;
    /** 角色：USER（默认）/ ADMIN。 */
    private String role;
    private LocalDateTime createdAt;
}
