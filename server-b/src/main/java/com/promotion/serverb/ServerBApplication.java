package com.promotion.serverb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling — OutboxPoller 의 @Scheduled 활성화 (CLAUDE.md ADR-002).
@SpringBootApplication
@EnableScheduling
public class ServerBApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerBApplication.class, args);
    }
}
