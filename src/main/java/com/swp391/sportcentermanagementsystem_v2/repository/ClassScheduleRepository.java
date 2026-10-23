package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassScheduleRepository extends JpaRepository<ClassSchedule, Long> {
    // Lấy toàn bộ lịch hoạt động sắp xếp theo Thứ tăng dần , giờ bắt đầu tăng dần
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.course.category", "room" })
    List<ClassSchedule> findAllByOrderByDayOfWeekAscStartTimeAsc();

    // Lấy chi tiết 1 lịch học kèm sẵn thông tin lớp,HLV,bộ môn,phòng
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.course.category", "room" })
    Optional<ClassSchedule> findWithDetailsById(Long id);

    // Xem lịch học của 1 lớp cụ thể (dành cho member/manager)
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.course.category", "room" })
    List<ClassSchedule> findBySportClassIdOrderByDayOfWeekAscStartTimeAsc(Long classId);

    // Xem lịch hoạt động theo Thứ trong tuần (1 = Thứ 2 ... 7 = Chủ Nhật)
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.course.category", "room" })
    List<ClassSchedule> findByDayOfWeekOrderByStartTimeAsc(Short dayOfWeek);

    // Xem lịch dạy của 1 HLV (dành cho Coach xem lịch dạy)
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "sportClass.course.category", "room" })
    List<ClassSchedule> findBySportClassCoachIdOrderByDayOfWeekAscStartTimeAsc(Long coachId);

    // Xóa ngay bằng một câu lệnh, để các dòng mới cùng transaction không bị xếp sau lệnh xóa
    @Modifying(flushAutomatically = true)
    @Query("delete from ClassSchedule cs where cs.sportClass.id = :classId")
    void deleteByClassId(@Param("classId") Long classId);
}
