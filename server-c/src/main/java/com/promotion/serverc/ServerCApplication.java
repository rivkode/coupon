package com.promotion.serverc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ServerCApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerCApplication.class, args);
    }
}
