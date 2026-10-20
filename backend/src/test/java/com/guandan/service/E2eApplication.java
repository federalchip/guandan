package com.guandan.service;

import com.guandan.GuandanApplication;
import org.springframework.boot.SpringApplication;

/** Test-classpath entry point for Playwright. */
public final class E2eApplication {
    private E2eApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[] {GuandanApplication.class, E2eFixtureConfiguration.class}, args);
    }
}
