/**
 * 领域对象层：多轮对话与推荐流水线中流转的业务值对象。
 * <p>
 * 本包对象既不映射数据库行（那是 {@code model.row} 的职责），也不是 HTTP 出入参
 * （那是 {@code model.web} 的职责），而是 Orchestrator 与各 AgentService 之间传递的业务语义：
 * 会话状态（SessionState/SlotBundle）、餐食候选（MealItem）、意图与澄清结果（IntentResult/ClarifyResult）、
 * 推荐产物（RecommendResult/RecommendedMealOption/ResponseResult/RiskGuardResult）、
 * 对话轮次摘要（ConversationTurn），以及检索/重排流水线的内部参数（MealSearchRequest/MealRankRequest）。
 */
package com.diet.model.domain;
