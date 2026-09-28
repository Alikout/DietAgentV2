/**
 * 持久化行对象：与 diet_sessions、diet_messages、meal_item、agent_traces、recommend_feedback
 * 等表结构一一对应的 MyBatis 行类。
 * <p>
 * 仅允许出现在 Mapper 接口与 service 的转换边界内（如 SessionStateService#fromRow），
 * 禁止向上层直接返回；MyBatis XML 中的 resultMap/parameterType 引用本包全限定名。
 */
package com.diet.common.model.row;
