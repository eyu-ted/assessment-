package com.safari.practice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Separate thread pool for async bank calls so they never starve
 * the Tomcat request threads (max 50 per instance).
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "payoutExecutor")
    public Executor payoutExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(10);
        exec.setMaxPoolSize(30);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("payout-");
        exec.initialize();
        return exec;
    }

    @Override
    public Executor getAsyncExecutor() {
        return payoutExecutor();
    }
}
