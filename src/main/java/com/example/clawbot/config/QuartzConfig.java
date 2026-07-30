package com.example.clawbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Quartz 调度器配置。
 *
 * <p>替代 Spring @Scheduled 的每分钟轮询扫描方式，
 * 支持精确到秒级的任务触发，减少无效数据库查询。</p>
 */
@Configuration
public class QuartzConfig {

    /**
     * 创建 SchedulerFactoryBean，Spring 自动管理 Quartz Scheduler 生命周期。
     */
    @Bean
    public SchedulerFactoryBean schedulerFactoryBean() {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setStartupDelay(5); // 应用启动后延迟 5 秒再开始调度
        factory.setOverwriteExistingJobs(true);
        return factory;
    }
}
