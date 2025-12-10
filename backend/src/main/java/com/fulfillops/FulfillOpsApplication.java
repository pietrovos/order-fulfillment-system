package com.fulfillops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FulfillOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillOpsApplication.class, args);
    }
}
