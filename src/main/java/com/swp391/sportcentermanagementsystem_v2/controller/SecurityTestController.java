package com.swp391.sportcentermanagementsystem_v2.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class SecurityTestController {

    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(Authentication authentication) {

        return Map.of(
                "email", authentication.getName(),
                "authorities", authentication.getAuthorities()
        );
    }
    @GetMapping("/manager")
    public String managerOnly() {
        return "Welcome Manager!";
    }
}