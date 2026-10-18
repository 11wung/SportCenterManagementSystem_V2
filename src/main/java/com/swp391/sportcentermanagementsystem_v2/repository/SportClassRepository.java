package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SportClassRepository extends JpaRepository<SportClass, Long> {

    // Khóa dòng lớp (SELECT ... FOR UPDATE) cho tới khi transaction kết thúc.
    // Dùng khi đăng ký lớp và khi sửa lớp để hai thao tác không chạy chen nhau.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SportClass c where c.id = :id")
    Optional<SportClass> findByIdForUpdate(@Param("id") Long id);

    // Sức chứa nhỏ nhất trong các phòng đã xếp lịch cho lớp. Null nếu lớp chưa có lịch (BR-CLS-01)
    @Query(value = """
            SELECT CAST(MIN(fr.capacity) AS integer)
            FROM class_schedules cs
            JOIN facility_rooms fr ON fr.id = cs.room_id
            WHERE cs.class_id = :classId
            """, nativeQuery = true)
    Integer findMinRoomCapacityByClassId(@Param("classId") Long classId);

    // Số buổi của lớp này trùng giờ với các buổi của lớp khác do cùng coach dạy (BR-CLS-02)
    @Query(value = """
            SELECT COUNT(*)
            FROM class_schedules mine
            JOIN class_schedules other ON other.class_id <> mine.class_id
                 AND other.day_of_week = mine.day_of_week
                 AND other.start_time < mine.end_time
                 AND other.end_time > mine.start_time
            JOIN sport_classes oc ON oc.id = other.class_id
            WHERE mine.class_id = :classId
              AND oc.coach_id = :coachId
            """, nativeQuery = true)
    long countCoachScheduleConflicts(@Param("classId") Long classId, @Param("coachId") Long coachId);

    // Mỗi dòng: [day_of_week (1 = Monday ... 7 = Sunday), start_time dạng "HH:mm:ss"].
    // Dùng cho luật hủy lớp trước giờ học 2 tiếng
    @Query(value = """
            SELECT CAST(day_of_week AS integer), CAST(start_time AS varchar)
            FROM class_schedules
            WHERE class_id = :classId
            """, nativeQuery = true)
    List<Object[]> findSessionSlotsByClassId(@Param("classId") Long classId);
}