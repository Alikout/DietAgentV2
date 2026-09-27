/**
 * Web 层出入参 DTO：Controller 接收与返回的 HTTP 载荷（对话、餐食管理、反馈、标注、评估请求）。
 * <p>
 * 与领域对象的转换发生在 service 层（如 {@code MealResponse.from(MealItem)}）；
 * 本包对象禁止被 Mapper 直接读写，数据库行对象一律使用 {@code model.row}。
 */
package com.diet.model.web;
