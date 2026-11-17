package com.example.moneybug;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MoneyBugApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoneyBugApplication.class, args);
    }

}
