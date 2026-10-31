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

    // Lịch tuần của manager: mọi buổi của mọi lớp trong khoảng ngày, một query thay cho gọi từng lớp
    @EntityGraph(attributePaths = { "sportClass", "room", "coach" })
    List<ClassSession> findBySessionDateBetweenOrderBySessionDateAscStartTimeAsc(LocalDate from, LocalDate to);

    @EntityGraph(attributePaths = { "sportClass", "room", "coach" })
    Optional<ClassSession> findWithDetailsById(Long id);

    long countBySportClassId(Long classId);

    long countBySportClassIdAndStatusIn(Long classId, java.util.Collection<String> statuses);

    // Số buổi chưa học: còn SCHEDULED và có ngày từ hôm nay trở đi
    long countBySportClassIdAndStatusAndSessionDateGreaterThanEqual(Long classId, String status, LocalDate date);

    long countBySportClassIdAndMakeupTrueAndStatusIn(Long classId, java.util.Collection<String> statuses);

    // Số buổi gốc của class (không tính buổi bù), kể cả buổi đã hủy: mẫu số để chia giá trị một buổi
    long countBySportClassIdAndMakeupFalse(Long classId);

    // Các buổi sắp tới của một coach trong khoảng ngày, thuộc class còn hoạt động (coach nghỉ tạm)
    @EntityGraph(attributePaths = { "sportClass", "room", "coach" })
    @Query("""
            select s from ClassSession s
            where s.coach.id = :coachId and s.status = 'SCHEDULED'
              and s.sessionDate between :from and :to
              and s.sportClass.status in ('OPEN', 'IN_PROGRESS')
            order by s.sportClass.id, s.sessionNo
            """)
    List<ClassSession> findCoachUpcoming(@Param("coachId") Long coachId, @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    // Thời khóa biểu của member: buổi của các class member đang tham gia (REGISTERED), kể cả buổi bị trung tâm hủy để member biết
    @EntityGraph(attributePaths = { "sportClass", "room", "coach" })
    @Query("""
            select s from ClassSession s
            where s.sportClass.id in (select r.sportClass.id from ClassRegistration r
                                      where r.user.id = :memberId and r.status = 'REGISTERED')
              and s.status in ('SCHEDULED', 'DONE', 'CANCELED_BY_CENTER')
              and s.sessionDate between :from and :to
            order by s.sessionDate, s.startTime
            """)
    List<ClassSession> findMemberSessions(@Param("memberId") Long memberId, @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    // Các buổi dạy của một coach trong khoảng ngày (kể cả buổi được gán riêng), không tính class nháp, class đã hủy
    // và buổi bị trung tâm hủy
    @EntityGraph(attributePaths = { "sportClass", "sportClass.coach", "room", "coach" })
    @Query("""
            select s from ClassSession s
            where s.coach.id = :coachId and s.status in ('SCHEDULED', 'PROPOSED', 'DONE')
              and s.sessionDate between :from and :to
              and s.sportClass.status in ('OPEN', 'IN_PROGRESS', 'COMPLETED')
            order by s.sessionDate, s.startTime
            """)
    List<ClassSession> findCoachSessions(@Param("coachId") Long coachId, @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    // Các buổi làm coach bận trong khoảng ngày: cùng điều kiện với trigger kiểm trùng của database (SCHEDULED, DONE)
    @Query("""
            select s from ClassSession s
            where s.coach.id = :coachId and s.status in ('SCHEDULED', 'DONE')
              and s.sessionDate between :from and :to
            """)
    List<ClassSession> findCoachBusySessions(@Param("coachId") Long coachId, @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    // Mọi buổi làm phòng và coach bận trong khoảng ngày (xem lịch rảnh khi xếp lịch)
    @EntityGraph(attributePaths = { "room", "coach" })
    @Query("""
            select s from ClassSession s
            where s.status in ('SCHEDULED', 'DONE') and s.sessionDate between :from and :to
            """)
    List<ClassSession> findBusySessionsBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // Các buổi chưa diễn ra trong một phòng, thuộc class còn hoạt động (phòng bảo trì)
    @EntityGraph(attributePaths = { "sportClass", "room", "coach" })
    @Query("""
            select s from ClassSession s
            where s.room.id = :roomId and s.status = 'SCHEDULED' and s.sessionDate >= :today
              and s.sportClass.status in ('OPEN', 'IN_PROGRESS')
            order by s.sportClass.id, s.sessionNo
            """)
    List<ClassSession> findRoomUpcoming(@Param("roomId") Long roomId, @Param("today") LocalDate today);

    // Ngày của buổi cuối còn hiệu lực của class: buổi bù được xếp sau ngày này
    @Query("select max(s.sessionDate) from ClassSession s where s.sportClass.id = :classId and s.status in ('SCHEDULED', 'DONE')")
    Optional<LocalDate> findLastActiveDate(@Param("classId") Long classId);

    @Query("select coalesce(max(s.sessionNo), 0) from ClassSession s where s.sportClass.id = :classId")
    int findMaxSessionNo(@Param("classId") Long classId);

    // BR-MKP-01: số học viên của class bị trùng giờ nếu xếp buổi bù vào ngày và giờ này
    // (có class khác hoặc buổi 1-1 đã đặt chồng giờ hoặc cách dưới bufferMinutes phút)
    @Query(value = """
            SELECT count(DISTINCT r.user_id)
            FROM class_registrations r
            WHERE r.class_id = :classId AND r.status IN ('PENDING_PAYMENT', 'REGISTERED')
              AND (EXISTS (
                       SELECT 1 FROM class_registrations r2
                       JOIN class_sessions b ON b.class_id = r2.class_id AND b.class_id <> :classId
                            AND b.status IN ('SCHEDULED', 'DONE') AND b.session_date = :date
                       WHERE r2.user_id = r.user_id AND r2.status IN ('PENDING_PAYMENT', 'REGISTERED')
                         AND CAST(:startTime AS time) < b.end_time + make_interval(mins => :buf)
                         AND b.start_time < CAST(:endTime AS time) + make_interval(mins => :buf))
                   OR EXISTS (
                       SELECT 1 FROM coach_slots s
                       WHERE s.member_id = r.user_id AND s.status IN ('HELD', 'BOOKED')
                         AND tstzrange(s.start_at, s.end_at + make_interval(mins => :buf))
                          && tstzrange((CAST(:date AS date) + CAST(:startTime AS time)) AT TIME ZONE 'Asia/Ho_Chi_Minh',
                                       ((CAST(:date AS date) + CAST(:endTime AS time)) AT TIME ZONE 'Asia/Ho_Chi_Minh')
                                       + make_interval(mins => :buf))))
            """, nativeQuery = true)
    long countMembersBusy(@Param("classId") Long classId, @Param("date") LocalDate date,
                          @Param("startTime") LocalTime startTime, @Param("endTime") LocalTime endTime,
                          @Param("buf") int bufferMinutes);

    // BR-MKP-03: member đã bấm "không đi được" buổi bù này chưa (bảng điểm danh thuộc Flow 4, ở đây chỉ ghi một dòng)
    @Query(value = "SELECT count(*) > 0 FROM class_attendances WHERE session_id = :sessionId AND user_id = :userId",
            nativeQuery = true)
    boolean existsAttendance(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Modifying
    @Query(value = """
            INSERT INTO class_attendances (session_id, user_id, status, recorded_by, notes)
            VALUES (:sessionId, :userId, 'DECLINED_MAKEUP', :userId, 'Member cannot attend the makeup session')
            """, nativeQuery = true)
    void insertDeclinedMakeup(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    // Số buổi đã học của class: đã DONE, hoặc còn SCHEDULED nhưng ngày đã qua (chưa có gì đánh dấu DONE ở Flow 4)
    @Query("""
            select count(s) from ClassSession s
            where s.sportClass.id = :classId
              and (s.status = 'DONE' or (s.status = 'SCHEDULED' and s.sessionDate < :today))
            """)
    long countStudied(@Param("classId") Long classId, @Param("today") LocalDate today);

    // Manager hủy class: các buổi chưa diễn ra thành CANCELED_BY_CENTER (BR-MKP-04)
    @Modifying
    @Query("""
            update ClassSession s set s.status = 'CANCELED_BY_CENTER', s.cancelReason = :reason
            where s.sportClass.id = :classId and s.status = 'SCHEDULED' and s.sessionDate >= :today
            """)
    int cancelUpcoming(@Param("classId") Long classId, @Param("today") LocalDate today,
                       @Param("reason") String reason);

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
