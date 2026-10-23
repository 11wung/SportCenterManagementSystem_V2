package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SportClassRepository extends JpaRepository<SportClass, Long> {

    boolean existsByCoachId(Long coachId);

    // Khóa dòng lớp (SELECT ... FOR UPDATE) cho tới khi transaction kết thúc.
    // Dùng khi đăng ký lớp và khi sửa lớp để hai thao tác không chạy chen nhau.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SportClass c where c.id = :id")
    Optional<SportClass> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = { "course", "course.category", "coach" })
    List<SportClass> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = { "course", "course.category", "coach" })
    Optional<SportClass> findWithDetailsById(Long id);

    // Mỗi dòng: [day_of_week (1 = Monday ... 7 = Sunday), start_time dạng "HH:mm:ss"].
    // Dùng cho luật hủy lớp trước giờ học 2 tiếng
    @Query(value = """
            SELECT CAST(day_of_week AS integer), CAST(start_time AS varchar)
            FROM class_schedules
            WHERE class_id = :classId
            """, nativeQuery = true)
    List<Object[]> findSessionSlotsByClassId(@Param("classId") Long classId);
}
