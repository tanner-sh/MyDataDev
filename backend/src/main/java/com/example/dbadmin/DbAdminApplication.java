package com.example.dbadmin;

import com.example.dbadmin.cli.CryptoKeyAdoptionCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DbAdminApplication {
    public static void main(String[] args) {
        if (CryptoKeyAdoptionCommand.matches(args)) {
            int exitCode = CryptoKeyAdoptionCommand.run(args);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }
        SpringApplication.run(DbAdminApplication.class, args);
    }
}
