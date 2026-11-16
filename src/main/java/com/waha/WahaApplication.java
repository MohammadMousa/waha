package com.waha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WahaApplication {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");   // image work (avatars) runs on a server with no screen
        SpringApplication.run(WahaApplication.class, args);
    }
}
