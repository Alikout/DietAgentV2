package com.diet.mapper;

import com.diet.common.model.row.UserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    /** 插入新用户，回填自增 id。 */
    int insert(UserRow row);

    /** 按用户名查询（唯一索引），不存在返回 null。 */
    UserRow findByUsername(@Param("username") String username);

    /** 统计指定角色的用户数（注册时判断是否需要自动授予 ADMIN）。 */
    int countByRole(@Param("role") String role);
}
