package com.swp391.sportcentermanagementsystem_v2;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableScheduling
public class SportCenterManagementSystemV2Application {

    public static void main(String[] args) {
        SpringApplication.run(SportCenterManagementSystemV2Application.class, args);
    }

}
