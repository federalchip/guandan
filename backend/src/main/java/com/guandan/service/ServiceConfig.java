package com.guandan.service;

import com.guandan.bots.RandomLegalPolicy;
import com.guandan.engine.GameEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 生产 seam 装配：安全随机游戏 seed、随机合法机器人策略、真实引擎推进。 */
@Configuration
public class ServiceConfig {

    @Bean
    GameSeedSource gameSeedSource() {
        SecureRandom secureRandom = new SecureRandom();
        return secureRandom::nextLong;
    }

    @Bean
    BotPolicy botPolicy() {
        return RandomLegalPolicy::chooseAction;
    }

    @Bean
    ActionAdvancer actionAdvancer() {
        return GameEngine::applyAction;
    }

    @Bean
    AsyncCommandConfig asyncCommandConfig(
            @Value("${guandan.commands.async-enabled:false}") boolean asyncEnabled,
            @Value("${guandan.commands.batch-timeout-ms:12000}") long batchTimeoutMillis,
            @Value("${guandan.commands.poll-after-ms:500}") long pollAfterMillis) {
        return new AsyncCommandConfig(asyncEnabled, batchTimeoutMillis, pollAfterMillis);
    }

    @Bean(destroyMethod = "shutdown")
    Executor commandExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
