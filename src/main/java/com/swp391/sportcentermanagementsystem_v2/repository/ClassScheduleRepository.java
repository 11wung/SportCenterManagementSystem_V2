package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClassScheduleRepository extends JpaRepository<ClassSchedule, Long> {
    // Lấy toàn bộ lịch hoạt động sắp xếp theo Thứ tăng dần , giờ bắt đầu tăng dần
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.category", "room" })
    List<ClassSchedule> findAllByOrderByDayOfWeekAscStartTimeAsc();

    // Lấy chi tiết 1 lịch học kèm sẵn thông tin lớp,HLV,bộ môn,phòng
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.category", "room" })
    Optional<ClassSchedule> findWithDetailsById(Long id);

    // Xem lịch học của 1 lớp cụ thể (dành cho member/manager)
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.category", "room" })
    List<ClassSchedule> findBySportClassIdOrderByDayOfWeekAscStartTimeAsc(Long classId);

    // Xem lịch hoạt động theo Thứ trong tuần (1 = Thứ 2 ... 7 = Chủ Nhật)
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.category", "room" })
    List<ClassSchedule> findByDayOfWeekOrderByStartTimeAsc(Short dayOfWeek);

    // Xem lịch dạy của 1 HLV (dành cho Coach xem lịch dạy)
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.category", "room" })
    List<ClassSchedule> findBySportClassCoachIdOrderByDayOfWeekAscStartTimeAsc(Long coachId);

    // BR-CLS-02a: Kiểm tra trùng phòng tập trong cùng thứ và khung giờ giao nhau
    @Query("""
                SELECT COUNT(cs) > 0 FROM ClassSchedule cs
                WHERE cs.room.id = :roomId
                  AND cs.dayOfWeek = :dayOfWeek
                  AND (:excludeId IS NULL OR cs.id <> :excludeId)
                  AND cs.startTime < :endTime
                  AND cs.endTime > :startTime
            """)
    boolean existsRoomOverlap(
            @Param("roomId") Long roomId,
            @Param("dayOfWeek") Short dayOfWeek,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("excludeId") Long excludeId);

    // BR-CLS-02b: Kiểm tra trùng lịch dạy của HLV trong cùng thứ và cùng khung giờ
    @Query("""
                SELECT COUNT(cs) > 0 FROM ClassSchedule cs
                WHERE cs.sportClass.coach.id = :coachId
                  AND cs.dayOfWeek = :dayOfWeek
                  AND (:excludeId IS NULL OR cs.id <> :excludeId)
                  AND cs.startTime < :endTime
                  AND cs.endTime > :startTime
            """)
    boolean existsCoachOverlap(
            @Param("coachId") Long coachId,
            @Param("dayOfWeek") Short dayOfWeek,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("excludeId") Long excludeId);
}