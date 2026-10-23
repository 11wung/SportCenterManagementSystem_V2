package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
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

    // Danh mục class cho khách và member: chỉ class đang mở đăng ký. keyword đã là dạng "%chữ thường%" hoặc null
    @EntityGraph(attributePaths = { "course", "course.category", "course.prerequisiteCourse", "coach" })
    @Query("""
            select c from SportClass c
            where c.status = 'OPEN'
              and (:categoryId is null or c.course.category.id = :categoryId)
              and (:level is null or c.course.level = :level)
              and (:keyword is null or lower(c.name) like :keyword or lower(c.course.name) like :keyword)
            order by c.startDate, c.id
            """)
    List<SportClass> searchOpen(@Param("categoryId") Long categoryId,
                                @Param("level") String level,
                                @Param("keyword") String keyword);

    @EntityGraph(attributePaths = { "course", "course.category", "course.prerequisiteCourse", "coach" })
    @Query("select c from SportClass c where c.id = :id and c.status = 'OPEN'")
    Optional<SportClass> findOpenById(@Param("id") Long id);

    // BR-CLS-06: class mở đăng ký chuyển sang đang học khi đến ngày khai giảng
    @Modifying
    @Query("update SportClass c set c.status = 'IN_PROGRESS' where c.status = 'OPEN' and c.startDate <= :today")
    int startClasses(@Param("today") LocalDate today);

    // BR-CLS-06: đang học chuyển sang kết thúc khi không còn buổi nào từ hôm nay trở đi
    // (buổi PROPOSED là buổi bù đang chờ, class chưa kết thúc)
    @Modifying
    @Query("""
            update SportClass c set c.status = 'COMPLETED'
            where c.status = 'IN_PROGRESS'
              and not exists (select 1 from ClassSession s
                              where s.sportClass = c
                                and s.status in ('SCHEDULED', 'PROPOSED')
                                and s.sessionDate >= :today)
            """)
    int completeFinishedClasses(@Param("today") LocalDate today);

    // Mỗi dòng: [day_of_week (1 = Monday ... 7 = Sunday), start_time dạng "HH:mm:ss"].
    // Dùng cho luật hủy lớp trước giờ học 2 tiếng
    @Query(value = """
            SELECT CAST(day_of_week AS integer), CAST(start_time AS varchar)
            FROM class_schedules
            WHERE class_id = :classId
            """, nativeQuery = true)
    List<Object[]> findSessionSlotsByClassId(@Param("classId") Long classId);
}
