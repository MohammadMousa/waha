package com.waha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WahaApplication {
    public static void main(String[] args) {
        SpringApplication.run(WahaApplication.class, args);
    }
}
