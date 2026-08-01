package com.example.clawbot.config;

import org.quartz.Job;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Quartz 调度器配置。
 *
 * <p>替代 Spring @Scheduled 的每分钟轮询扫描方式，
 * 支持精确到秒级的任务触发，减少无效数据库查询。</p>
 *
 * <p>通过自定义 {@link JobFactory} 让 Quartz Job 实例也能享受
 * Spring 的依赖注入（@Autowired），避免 repository 为 null 的问题。</p>
 */
@Configuration
public class QuartzConfig {

    /**
     * 自定义 JobFactory — 使用 Spring 的 AutowireCapableBeanFactory
     * 在 Quartz 创建 Job 实例后自动注入依赖。
     */
    public static class SpringAutowiringJobFactory implements JobFactory {
        private final AutowireCapableBeanFactory beanFactory;

        public SpringAutowiringJobFactory(AutowireCapableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) throws SchedulerException {
            try {
                Job job = bundle.getJobDetail().getJobClass().getDeclaredConstructor().newInstance();
                beanFactory.autowireBean(job);
                return job;
            } catch (Exception e) {
                throw new SchedulerException("Failed to create job instance with Spring autowiring", e);
            }
        }
    }

    /**
     * 创建 SchedulerFactoryBean，Spring 自动管理 Quartz Scheduler 生命周期。
     */
    @Bean
    public SchedulerFactoryBean schedulerFactoryBean(AutowireCapableBeanFactory beanFactory) {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setJobFactory(new SpringAutowiringJobFactory(beanFactory));
        factory.setStartupDelay(5); // 应用启动后延迟 5 秒再开始调度
        factory.setOverwriteExistingJobs(true);
        return factory;
    }
}
