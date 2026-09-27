package com.diet.mapper;

import com.diet.model.row.SessionMessageRow;
import com.diet.model.row.SessionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SessionMapper {
    int insert(SessionRow row);

    SessionRow findById(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    int update(SessionRow row);

    int insertMessage(
            @Param("sessionId") String sessionId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("intent") String intent,
            @Param("traceId") String traceId
    );

    /**
     * 幂等插入用户消息：client_msg_id 非空时携带唯一键 + ON DUPLICATE KEY UPDATE（重复重试只影响 0 行）。
     */
    int insertMessageIdempotent(
            @Param("sessionId") String sessionId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("intent") String intent,
            @Param("traceId") String traceId,
            @Param("clientMsgId") String clientMsgId
    );

    /**
     * 读取会话全部历史消息（时间正序），供前端刷新后恢复会话（第三周新增，join diet_sessions 校验归属）。
     */
    List<SessionMessageRow> findMessages(
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    List<SessionMessageRow> listRecentMessages(
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("limit") int limit
    );
}




