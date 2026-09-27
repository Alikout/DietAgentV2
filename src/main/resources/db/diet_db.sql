-- ============================================================================
-- 饮食推荐助手（DietAgent）数据库重建脚本（唯一入口）
-- ============================================================================
-- 用途：项目迁移到新环境时，通过本脚本一次性重建完整数据库（表结构 + 基础种子数据）。
--       已整合历史上所有增量升级脚本（session 乐观锁、消息幂等键、用户表与角色、
--       评估任务表、Trace 评估冗余列、餐食多值索引），建表即为最终形态。
--
-- 使用方法（任选其一）：
--   1) 命令行：mysql -u root -p < diet_db.sql
--   2) 客户端（Navicat / DataGrip 等）：直接运行本文件
--
-- 注意事项：
--   1. 脚本会在现有 diet_db 库上重建全部表（DROP + CREATE）并清空原有数据，
--      请勿对含生产数据的库执行；
--   2. meal_item 使用多值索引（CAST(... AS CHAR(32) ARRAY)），要求 MySQL 8.0.17 及以上；
--   3. 业务库的连接串已带 createDatabaseIfNotExist=true，但表结构仍需本脚本创建；
--   4. 种子数据：槽位标签字典（91 条）+ 公共餐食示例（4 条）+ 个人餐食示例（1 条，
--      归属 user_id=1，可在管理页删除）。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `diet_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `diet_db`;

-- ----------------------------
-- Table structure for diet_messages（对话消息，含第二周 client_msg_id 幂等键）
-- ----------------------------
DROP TABLE IF EXISTS `diet_messages`;
CREATE TABLE `diet_messages`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL,
  `role` varchar(32) NOT NULL,
  `content` text NOT NULL,
  `intent` varchar(64) NULL DEFAULT NULL,
  `agent_trace_id` varchar(128) NULL DEFAULT NULL,
  `client_msg_id` varchar(64) NULL DEFAULT NULL COMMENT '客户端幂等键（sessionId:requestId），重试去重',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_message_session`(`session_id` ASC, `created_at` ASC) USING BTREE,
  UNIQUE INDEX `uk_message_client`(`client_msg_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for diet_request_trace（链路追踪，含第四周评估冗余列）
-- ----------------------------
DROP TABLE IF EXISTS `diet_request_trace`;
CREATE TABLE `diet_request_trace`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trace_id` varchar(128) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `event_count` int NOT NULL DEFAULT 0,
  `duration_ms` bigint NULL DEFAULT NULL,
  `error_message` text NULL,
  `trace_json` json NOT NULL,
  `intent_final` varchar(64) NULL DEFAULT NULL COMMENT '最终意图（INTENT_REVISED 事件聚合）',
  `clarify_action` varchar(32) NULL DEFAULT NULL COMMENT '澄清动作 ASK/READY（CLARIFY_DECISION 事件聚合）',
  `token_total` bigint NULL DEFAULT NULL COMMENT '整轮 token 总量（AGENT_CALL 事件累加）',
  `fallback_used` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否触发 fallback/失败',
  `expected_intent` varchar(64) NULL DEFAULT NULL,
  `expected_slots` json NULL,
  `expected_clarify_action` varchar(32) NULL DEFAULT NULL,
  `labeled_by` bigint NULL DEFAULT NULL,
  `labeled_at` datetime NULL DEFAULT NULL,
  `label_note` varchar(512) NULL DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_request_trace`(`trace_id` ASC) USING BTREE,
  INDEX `idx_request_trace_session`(`session_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_request_trace_user`(`user_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_request_trace_status`(`status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_request_trace_label`(`expected_intent` ASC, `labeled_at` ASC) USING BTREE,
  INDEX `idx_request_trace_token`(`token_total` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for diet_sessions（会话状态，含第一周乐观锁版本号）
-- ----------------------------
DROP TABLE IF EXISTS `diet_sessions`;
CREATE TABLE `diet_sessions`  (
  `id` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `phase` varchar(64) NOT NULL,
  `slots` json NOT NULL,
  `last_recommendations` json NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `version` bigint NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_session_user`(`user_id` ASC, `updated_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for diet_slot_option（槽位标签字典）
-- ----------------------------
DROP TABLE IF EXISTS `diet_slot_option`;
CREATE TABLE `diet_slot_option`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slot_name` varchar(64) NOT NULL,
  `option_value` varchar(64) NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_slot_option`(`slot_name` ASC, `option_value` ASC) USING BTREE,
  INDEX `idx_slot_enabled`(`slot_name` ASC, `enabled` ASC, `sort_order` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of diet_slot_option
-- ----------------------------
INSERT INTO `diet_slot_option` VALUES (1, 'mealTime', '早餐', 10, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (2, 'mealTime', '早午餐', 20, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (3, 'mealTime', '午餐', 30, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (4, 'mealTime', '下午茶', 40, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (5, 'mealTime', '晚餐', 50, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (6, 'mealTime', '夜宵', 60, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (7, 'mealTime', '加餐', 70, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (8, 'mealTime', '三餐', 80, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (9, 'mood', '疲惫', 10, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (10, 'mood', '烦躁', 20, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (11, 'mood', '开心', 30, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (12, 'mood', '焦虑', 40, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (13, 'mood', '低落', 50, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (14, 'mood', '平静', 60, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (15, 'mood', '压力大', 70, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (16, 'mood', '没胃口', 80, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (17, 'mood', '想放松', 90, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (18, 'mood', '想奖励自己', 100, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (19, 'scene', '工作', 10, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (20, 'scene', '校园', 20, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (21, 'scene', '家里', 30, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (22, 'scene', '周末', 40, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (23, 'scene', '加班', 50, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (24, 'scene', '运动后', 60, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (25, 'scene', '通勤', 70, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (26, 'scene', '聚餐', 80, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (27, 'scene', '独处', 90, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (28, 'scene', '旅行', 100, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (30, 'healthGoal', '减脂', 10, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (31, 'healthGoal', '清淡', 20, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (32, 'healthGoal', '养胃', 30, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (33, 'healthGoal', '高蛋白', 40, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (34, 'healthGoal', '均衡', 50, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (35, 'healthGoal', '降火', 60, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (36, 'healthGoal', '低油', 70, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (37, 'healthGoal', '低盐', 80, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (38, 'healthGoal', '低糖', 90, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (39, 'healthGoal', '补能', 100, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (40, 'healthGoal', '增肌', 110, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (41, 'healthGoal', '控碳水', 120, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (42, 'healthGoal', '易消化', 130, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (43, 'healthGoal', '暖胃', 140, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (44, 'cuisine', '川菜', 10, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (45, 'cuisine', '粤菜', 20, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (46, 'cuisine', '湘菜', 30, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (47, 'cuisine', '江浙菜', 40, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (48, 'cuisine', '东北菜', 50, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (49, 'cuisine', '鲁菜', 60, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (50, 'cuisine', '闽南菜', 70, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (51, 'cuisine', '云南菜', 80, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (52, 'cuisine', '新疆菜', 90, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (53, 'cuisine', '轻食', 100, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (54, 'cuisine', '西餐', 110, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (55, 'cuisine', '日料', 120, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (56, 'cuisine', '韩餐', 130, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (57, 'cuisine', '东南亚菜', 140, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (58, 'cuisine', '火锅', 150, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (59, 'cuisine', '烧烤', 160, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (60, 'cuisine', '海鲜', 170, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (61, 'cuisine', '素食', 180, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (62, 'cuisine', '家常', 190, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (63, 'cuisine', '小吃', 200, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (64, 'cuisine', '粉面', 210, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (65, 'cuisine', '粥汤', 220, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (66, 'cuisine', '快餐', 230, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (67, 'cuisine', '甜品', 240, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (69, 'taste', '辣', 20, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (70, 'taste', '微辣', 30, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (71, 'taste', '中辣', 40, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (72, 'taste', '麻辣', 50, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (73, 'taste', '甜', 60, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (74, 'taste', '酸甜', 70, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (75, 'taste', '咸鲜', 80, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (76, 'taste', '鲜香', 90, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (77, 'taste', '酱香', 100, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (78, 'taste', '蒜香', 110, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (79, 'taste', '番茄味', 120, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (80, 'taste', '咖喱味', 130, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (81, 'taste', '奶香', 140, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (82, 'taste', '油香', 150, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (83, 'taste', '烟火气', 160, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (84, 'convenience', '快速', 10, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (85, 'convenience', '慢享', 20, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (86, 'convenience', '外带方便', 30, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (87, 'convenience', '堂食舒服', 40, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (88, 'convenience', '少排队', 50, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (89, 'convenience', '少餐具', 60, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (90, 'convenience', '一人食', 70, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (91, 'convenience', '多人共享', 80, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (92, 'convenience', '适合备餐', 90, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `diet_slot_option` VALUES (93, 'convenience', '适合边走边吃', 100, 1, '2026-06-28 17:37:55', '2026-06-28 17:37:55');

-- ----------------------------
-- Table structure for meal_item（餐食库，含第四周槽位多值索引）
-- ----------------------------
DROP TABLE IF EXISTS `meal_item`;
CREATE TABLE `meal_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_type` varchar(16) NOT NULL,
  `owner_user_id` bigint NULL DEFAULT NULL,
  `name` varchar(128) NOT NULL,
  `meal_time` json NOT NULL,
  `mood` json NOT NULL,
  `scene` json NOT NULL,
  `health_goal` json NOT NULL,
  `cuisine` json NOT NULL,
  `taste` json NOT NULL,
  `convenience` json NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_public_meal_source`(`source_type` ASC) USING BTREE,
  INDEX `idx_private_meal_source`(`owner_user_id` ASC, `source_type` ASC) USING BTREE,
  INDEX `idx_meal_mealtime` ((CAST(`meal_time` AS CHAR(32) ARRAY))),
  INDEX `idx_meal_mood` ((CAST(`mood` AS CHAR(32) ARRAY))),
  INDEX `idx_meal_scene` ((CAST(`scene` AS CHAR(32) ARRAY))),
  INDEX `idx_meal_healthgoal` ((CAST(`health_goal` AS CHAR(32) ARRAY))),
  INDEX `idx_meal_cuisine` ((CAST(`cuisine` AS CHAR(32) ARRAY))),
  INDEX `idx_meal_taste` ((CAST(`taste` AS CHAR(32) ARRAY))),
  INDEX `idx_meal_convenience` ((CAST(`convenience` AS CHAR(32) ARRAY)))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of meal_item
-- ----------------------------
INSERT INTO `meal_item` VALUES (1, 'PUBLIC', NULL, '番茄鸡蛋面', '[\"午餐\", \"晚餐\", \"三餐\"]', '[\"疲惫\", \"低落\"]', '[\"工作\", \"校园\", \"家里\"]', '[\"清淡\", \"养胃\", \"易消化\"]', '[\"家常\", \"粉面\"]', '[\"清淡\", \"番茄味\"]', '[\"快速\", \"一人食\"]', '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `meal_item` VALUES (2, 'PUBLIC', NULL, '清汤馄饨', '[\"早餐\", \"午餐\", \"晚餐\", \"三餐\"]', '[\"疲惫\", \"没胃口\"]', '[\"工作\", \"校园\", \"家里\"]', '[\"清淡\", \"养胃\", \"暖胃\"]', '[\"小吃\", \"粥汤\"]', '[\"清淡\", \"咸鲜\"]', '[\"快速\", \"少餐具\"]', '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `meal_item` VALUES (3, 'PUBLIC', NULL, '鸡胸肉轻食碗', '[\"午餐\", \"晚餐\"]', '[\"平静\", \"想放松\"]', '[\"工作\", \"运动后\"]', '[\"减脂\", \"高蛋白\", \"低油\", \"均衡\"]', '[\"轻食\"]', '[\"清淡\", \"咸鲜\"]', '[\"快速\", \"一人食\"]', '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `meal_item` VALUES (4, 'PUBLIC', NULL, '麻辣香锅', '[\"午餐\", \"晚餐\", \"夜宵\"]', '[\"开心\", \"想奖励自己\"]', '[\"周末\", \"聚餐\", \"夜宵\"]', '[\"均衡\", \"补能\"]', '[\"川菜\", \"小吃\"]', '[\"麻辣\", \"烟火气\"]', '[\"慢享\", \"多人共享\"]', '2026-06-28 17:37:55', '2026-06-28 17:37:55');
INSERT INTO `meal_item` VALUES (5, 'PERSONAL', 1, '土豆炖牛肉', '[\"晚餐\"]', '[\"平静\"]', '[\"校园\"]', '[\"补能\"]', '[\"湘菜\"]', '[\"辣\"]', '[]', '2026-07-01 23:22:49', '2026-07-01 23:22:49');

-- ----------------------------
-- Table structure for recommend_feedback（推荐反馈流水）
-- ----------------------------
DROP TABLE IF EXISTS `recommend_feedback`;
CREATE TABLE `recommend_feedback`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `item_id` bigint NULL DEFAULT NULL,
  `action` varchar(32) NOT NULL,
  `rating` int NULL DEFAULT NULL,
  `reason` varchar(512) NULL DEFAULT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_feedback_user`(`user_id` ASC, `created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for diet_user（用户与角色：USER / ADMIN）
-- ----------------------------
DROP TABLE IF EXISTS `diet_user`;
CREATE TABLE `diet_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password_hash` varchar(100) NOT NULL COMMENT 'BCrypt 哈希',
  `role` varchar(16) NOT NULL DEFAULT 'USER' COMMENT '角色：USER / ADMIN',
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_username`(`username`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Table structure for diet_eval_job（评估批任务）
-- ----------------------------
DROP TABLE IF EXISTS `diet_eval_job`;
CREATE TABLE `diet_eval_job`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `start_at` datetime NOT NULL COMMENT '评估窗口开始',
  `end_at` datetime NOT NULL COMMENT '评估窗口结束',
  `include_llm_judge` tinyint(1) NOT NULL DEFAULT 0,
  `limit_count` int NOT NULL DEFAULT 1000,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/FAILED',
  `total` int NOT NULL DEFAULT 0 COMMENT '本次评估 trace 总数',
  `processed` int NOT NULL DEFAULT 0 COMMENT '已处理条数（前端轮询进度）',
  `report_json` json NULL COMMENT '评估报告（DONE 后写入）',
  `error_message` text NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_eval_job_status`(`status`, `created_at`),
  INDEX `idx_eval_job_user`(`user_id`, `created_at`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci ROW_FORMAT = DYNAMIC;

SET FOREIGN_KEY_CHECKS = 1;
