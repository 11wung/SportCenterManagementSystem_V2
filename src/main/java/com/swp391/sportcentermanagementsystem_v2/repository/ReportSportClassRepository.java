package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** JPA-only read repository dedicated to the Manager report. */
public interface ReportSportClassRepository extends JpaRepository<SportClass, Long> {

    @EntityGraph(attributePaths = {"course", "coach"})
    List<SportClass> findByStatusInOrderByStartDateAsc(Collection<String> statuses);
}
