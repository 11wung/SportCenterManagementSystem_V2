package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassAttendance;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ClassAttendanceRepository extends JpaRepository<ClassAttendance, Long> {

    @EntityGraph(attributePaths = {"session", "session.sportClass", "user"})
    List<ClassAttendance> findBySession_SportClass_IdInAndStatus(
            Collection<Long> classIds, String status);
}
