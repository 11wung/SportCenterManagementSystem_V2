package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassEvaluation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClassEvaluationRepository extends JpaRepository<ClassEvaluation, Long> {

    boolean existsByRegistration_Id(Long registrationId);

    @EntityGraph(attributePaths = { "registration", "registration.user", "coach" })
    List<ClassEvaluation> findByRegistration_SportClass_Id(Long classId);

    @EntityGraph(attributePaths = { "registration", "registration.sportClass", "coach" })
    List<ClassEvaluation> findByRegistration_User_IdOrderByCreatedAtDesc(Long userId);
}
