package com.reviewbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Code Review Bot - Spring Boot Application
 * Ralph Loop 방식으로 자동 개발된 코드 리뷰 봇
 */
@SpringBootApplication
@EnableScheduling
public class ReviewBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewBotApplication.class, args);
    }
}
