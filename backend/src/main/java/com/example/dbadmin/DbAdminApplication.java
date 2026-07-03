package com.example.dbadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DbAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbAdminApplication.class, args);
    }
}
