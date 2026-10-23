package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface ClassSessionRepository extends JpaRepository<ClassSession, Long> {

    @EntityGraph(attributePaths = { "sportClass", "room", "coach" })
    List<ClassSession> findBySportClassIdOrderBySessionNo(Long classId);

    @EntityGraph(attributePaths = { "sportClass", "room", "coach" })
    Optional<ClassSession> findWithDetailsById(Long id);

    // Xóa ngay bằng một câu lệnh, để các buổi sinh lại (cùng session_no) không bị xếp trước lệnh xóa
    @Modifying(flushAutomatically = true)
    @Query("delete from ClassSession s where s.sportClass.id = :classId")
    void deleteByClassId(@Param("classId") Long classId);

    // BR-SCH-01: hai buổi trùng khi chồng giờ hoặc cách nhau dưới 15 phút.
    // Truyền endPlus = giờ kết thúc + 15 phút, startMinus = giờ bắt đầu - 15 phút.
    // excludeClassId / excludeSessionId: bỏ qua các buổi sắp bị thay thế (null thì không bỏ qua)
    @Query("""
            select count(s) > 0 from ClassSession s
            where s.status in ('SCHEDULED', 'DONE')
              and s.sessionDate = :date
              and s.coach.id = :coachId
              and s.startTime < :endPlus and s.endTime > :startMinus
              and (:excludeClassId is null or s.sportClass.id <> :excludeClassId)
              and (:excludeSessionId is null or s.id <> :excludeSessionId)
            """)
    boolean existsCoachConflict(@Param("coachId") Long coachId,
                                @Param("date") LocalDate date,
                                @Param("startMinus") LocalTime startMinus,
                                @Param("endPlus") LocalTime endPlus,
                                @Param("excludeClassId") Long excludeClassId,
                                @Param("excludeSessionId") Long excludeSessionId);

    @Query("""
            select count(s) > 0 from ClassSession s
            where s.status in ('SCHEDULED', 'DONE')
              and s.sessionDate = :date
              and s.room.id = :roomId
              and s.startTime < :endPlus and s.endTime > :startMinus
              and (:excludeClassId is null or s.sportClass.id <> :excludeClassId)
              and (:excludeSessionId is null or s.id <> :excludeSessionId)
            """)
    boolean existsRoomConflict(@Param("roomId") Long roomId,
                               @Param("date") LocalDate date,
                               @Param("startMinus") LocalTime startMinus,
                               @Param("endPlus") LocalTime endPlus,
                               @Param("excludeClassId") Long excludeClassId,
                               @Param("excludeSessionId") Long excludeSessionId);
}
