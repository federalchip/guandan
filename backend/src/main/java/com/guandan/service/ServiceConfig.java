package com.guandan.service;

import com.guandan.bots.RandomLegalPolicy;
import com.guandan.engine.GameEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

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
}
