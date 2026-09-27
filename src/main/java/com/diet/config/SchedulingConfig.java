package com.diet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度开关（第四周）：启用 @Scheduled——评估任务 worker 轮询、僵尸任务复位、Trace 保留清理。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
