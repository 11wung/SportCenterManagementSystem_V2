package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassAttendance;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ClassAttendanceRepository extends JpaRepository<ClassAttendance, Long> {

    // Có mặt hoặc đến muộn trong lớp kể từ ngày bắt đầu gói membership đầu tiên.
    boolean existsByUser_IdAndStatusInAndSession_SessionDateGreaterThanEqual(
            Long userId, Collection<String> statuses, java.time.LocalDate from);

    @EntityGraph(attributePaths = {"session", "user"})
    List<ClassAttendance> findBySession_IdIn(Collection<Long> sessionIds);

    @EntityGraph(attributePaths = {"session", "session.sportClass"})
    List<ClassAttendance> findBySession_IdInAndStatusIn(
            Collection<Long> sessionIds, Collection<String> statuses);

    @EntityGraph(attributePaths = {"session", "session.sportClass", "user"})
    List<ClassAttendance> findBySession_SportClass_IdInAndStatus(
            Collection<Long> classIds, String status);
}
