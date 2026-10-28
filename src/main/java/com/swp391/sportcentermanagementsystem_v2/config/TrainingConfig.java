package com.swp391.sportcentermanagementsystem_v2.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class TrainingConfig {
    @Bean
    public Clock trainingClock() { return Clock.systemUTC(); }
}
