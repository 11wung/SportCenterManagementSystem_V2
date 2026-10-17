package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClassRegistrationRepository extends JpaRepository<ClassRegistration, Long> {

    Optional<ClassRegistration> findBySportClass_IdAndUser_IdAndStatusIn(
            Long classId, Long userId, Collection<String> statuses);

    List<ClassRegistration> findBySportClass_IdAndUser_IdOrderByIdDesc(
            Long classId, Long userId);

    long countBySportClass_IdAndStatus(Long classId, String status);

    long countByUser_IdAndStatusAndRegisteredAtBetween(
            Long userId, String status, OffsetDateTime start, OffsetDateTime end);

    List<ClassRegistration> findByUser_IdOrderByRegisteredAtDesc(Long userId);
}
