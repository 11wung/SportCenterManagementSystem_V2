package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

/** Reporting-only User counts using Spring Data JPA method-name derivation. */
public interface ReportUserRepository extends JpaRepository<User, Long> {

    long countByRole_CodeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            String roleCode, OffsetDateTime start, OffsetDateTime end);
}
