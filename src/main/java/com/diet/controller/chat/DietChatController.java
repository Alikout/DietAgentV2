package com.diet.controller.chat;

import com.diet.model.web.ChatRequest;
import com.diet.model.web.ChatResponse;
import com.diet.security.CurrentUser;
import com.diet.service.orchestrator.DietOrchestratorService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 饮食推荐对话 HTTP 入口。
 * 本层只做参数透传，完整状态机由 {@link DietOrchestratorService#dietChat} 驱动。
 * 身份从 JWT 解析（第四周鉴权），不再信任可伪造的 X-User-Id 请求头。
 */
@RestController
@RequestMapping("/api/v1/diet")
public class DietChatController {

    /** 多 Agent 编排服务，注入后用于处理每轮对话。 */
    private final DietOrchestratorService orchestratorService;

    /** Spring 构造器注入 Orchestrator。 */
    public DietChatController(DietOrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    /**
     * POST /api/v1/diet/chat — 同步对话接口。
     * 接收用户消息，返回澄清追问或推荐结果（含餐食卡片）。
     */
    @PostMapping("/chat")
    public ChatResponse dietChat(
            // 从请求体反序列化 ChatRequest（sessionId、message、sourceMode、requestId）
            @RequestBody ChatRequest request
    ) {
        // 身份取自 JWT subject；service 层属主校验签名不变
        return orchestratorService.dietChat(CurrentUser.id(), request);
    }
}
