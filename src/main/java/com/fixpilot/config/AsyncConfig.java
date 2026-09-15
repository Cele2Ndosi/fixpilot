package com.fixpilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Responsibility (single):
 * - Provide the thread pool that InvestigationOrchestrator.run() executes
 *   on, so a slow investigation (a sandbox run plus several Groq calls)
 *   never blocks the webhook endpoint that started it.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "investigationExecutor")
    public Executor investigationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("fixpilot-investigation-");
        executor.initialize();
        return executor;
    }
}
