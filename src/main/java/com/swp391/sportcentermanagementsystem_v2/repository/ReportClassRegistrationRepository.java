package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** JPA-only read repository dedicated to the Manager report. */
public interface ReportClassRegistrationRepository extends JpaRepository<ClassRegistration, Long> {

    @EntityGraph(attributePaths = "sportClass")
    List<ClassRegistration> findBySportClass_IdInAndStatusIn(
            Collection<Long> classIds, Collection<String> statuses);
}
