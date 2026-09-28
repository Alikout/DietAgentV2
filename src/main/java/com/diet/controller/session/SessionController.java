package com.diet.controller.session;

import com.diet.model.web.CreateSessionResponse;
import com.diet.model.row.SessionMessageRow;
import com.diet.security.CurrentUser;
import com.diet.session.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/diet/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 身份取自 JWT subject（第四周鉴权）。 */
    @PostMapping
    public CreateSessionResponse create() {
        return new CreateSessionResponse(sessionService.createSession(CurrentUser.id()));
    }

    /**
     * GET /{sessionId}/messages — 会话历史消息（时间正序，最多 200 条）。
     * 第三周会话恢复：前端刷新后凭 localStorage 中的 sessionId 拉取历史重建对话流；
     * 服务端 SQL join diet_sessions 校验归属，越权 sessionId 返回空列表。
     */
    @GetMapping("/{sessionId}/messages")
    public List<SessionMessageRow> history(@PathVariable String sessionId) {
        return sessionService.findMessages(sessionId, CurrentUser.id(), 200);
    }
}
