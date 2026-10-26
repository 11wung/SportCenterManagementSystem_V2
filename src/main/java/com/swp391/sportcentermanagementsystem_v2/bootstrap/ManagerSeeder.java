package com.swp391.sportcentermanagementsystem_v2.bootstrap;

import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.RoleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
@Profile("local-manager-seed")
@RequiredArgsConstructor
public class ManagerSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${BOOTSTRAP_MANAGER_EMAIL}")
    private String managerEmail;

    @Value("${BOOTSTRAP_MANAGER_PHONE}")
    private String managerPhone;

    @Value("${BOOTSTRAP_MANAGER_PASSWORD}")
    private String managerPassword;

    @Override
    public void run(String... args) {

        if (userRepository.existsByEmailIgnoreCase(managerEmail)) {
            System.out.println("Manager bootstrap skipped: email already exists");
            return;
        }

        Role managerRole = roleRepository.findByCode("MANAGER")
                .orElseThrow(() -> new IllegalStateException("MANAGER role not found"));

        User manager = new User();
        manager.setFullName("Center Manager");
        manager.setEmail(managerEmail);
        manager.setPhone(managerPhone);
        manager.setPasswordHash(passwordEncoder.encode(managerPassword));
        manager.setRole(managerRole);
        manager.setIsActive(true);
        manager.setEmailVerifiedAt(java.time.OffsetDateTime.now());

        userRepository.save(manager);

        System.out.println("Manager account created successfully");
    }
}