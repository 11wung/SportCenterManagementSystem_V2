package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CoachSlotRepository extends JpaRepository<CoachSlot, Long> {

    // Khóa dòng suất để đặt, thanh toán, hủy và nhả chỗ không chạy chen nhau
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CoachSlot s where s.id = :id")
    Optional<CoachSlot> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = { "coach", "member" })
    @Query("select s from CoachSlot s where s.id = :id")
    Optional<CoachSlot> findWithDetailsById(@Param("id") Long id);

    // Xem công khai: suất 1-1 còn trống ở tương lai. from, to luôn có giá trị (service điền mặc định)
    @EntityGraph(attributePaths = { "coach" })
    @Query("""
            select s from CoachSlot s
            where s.kind = 'ONE_ON_ONE' and s.status = 'OPEN'
              and s.startAt >= :from and s.startAt < :to
              and (:coachId is null or s.coach.id = :coachId)
            order by s.startAt
            """)
    List<CoachSlot> searchOpen(@Param("coachId") Long coachId,
                               @Param("from") OffsetDateTime from,
                               @Param("to") OffsetDateTime to);

    @EntityGraph(attributePaths = { "coach", "member" })
    List<CoachSlot> findByCoach_IdAndStatusInAndEndAtAfterOrderByStartAt(
            Long coachId, Collection<String> statuses, OffsetDateTime after);

    @EntityGraph(attributePaths = { "coach", "member" })
    List<CoachSlot> findByMember_IdAndStatusInAndEndAtAfterOrderByStartAt(
            Long memberId, Collection<String> statuses, OffsetDateTime after);

    // Các suất chưa hủy của coach chồng giờ hoặc cách nhau dưới khoảng nghỉ (startMinus/endPlus đã cộng trừ khoảng nghỉ)
    @Query("""
            select s from CoachSlot s
            where s.coach.id = :coachId and s.status <> 'CANCELED'
              and s.startAt < :endPlus and s.endAt > :startMinus
              and (:excludeId is null or s.id <> :excludeId)
            order by s.startAt
            """)
    List<CoachSlot> findCoachOverlapping(@Param("coachId") Long coachId,
                                         @Param("startMinus") OffsetDateTime startMinus,
                                         @Param("endPlus") OffsetDateTime endPlus,
                                         @Param("excludeId") Long excludeId);

    // Các suất HELD/BOOKED khác của member trùng giờ
    @Query("""
            select s from CoachSlot s
            where s.member.id = :memberId and s.status in ('HELD', 'BOOKED') and s.id <> :slotId
              and s.startAt < :endPlus and s.endAt > :startMinus
            order by s.startAt
            """)
    List<CoachSlot> findMemberOverlapping(@Param("memberId") Long memberId,
                                          @Param("slotId") Long slotId,
                                          @Param("startMinus") OffsetDateTime startMinus,
                                          @Param("endPlus") OffsetDateTime endPlus);

    // Suất OPEN cùng giờ của cùng coach (dòng thay thế sau khi một suất bị hủy)
    Optional<CoachSlot> findFirstByCoach_IdAndStartAtAndEndAtAndKindAndStatus(
            Long coachId, OffsetDateTime startAt, OffsetDateTime endAt, String kind, String status);

    // Đặt suất: chỉ thành công nếu suất vẫn OPEN và chưa bắt đầu. Trả 0 dòng nghĩa là đã có người khác đặt
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CoachSlot s set s.status = 'HELD', s.member = :member, s.heldUntil = :heldUntil, s.price = :price
            where s.id = :id and s.status = 'OPEN' and s.startAt > :now
            """)
    int hold(@Param("id") Long id, @Param("member") User member, @Param("heldUntil") OffsetDateTime heldUntil,
             @Param("price") BigDecimal price, @Param("now") OffsetDateTime now);

    // BR-BEN-03: khung đánh giá thể lực còn trống ở tương lai
    @EntityGraph(attributePaths = { "coach" })
    @Query("""
            select s from CoachSlot s
            where s.kind = 'ASSESSMENT' and s.status = 'OPEN'
              and s.startAt >= :from and s.startAt < :to
            order by s.startAt
            """)
    List<CoachSlot> searchOpenAssessments(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    // Đặt khung đánh giá (miễn phí): chỉ thành công nếu khung vẫn OPEN và chưa bắt đầu
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CoachSlot s set s.status = 'BOOKED', s.member = :member
            where s.id = :id and s.kind = 'ASSESSMENT' and s.status = 'OPEN' and s.startAt > :now
            """)
    int bookAssessment(@Param("id") Long id, @Param("member") User member, @Param("now") OffsetDateTime now);

    // Số khung đánh giá member đã đặt trong khoảng [from, to): lượt của một kỳ
    @Query("""
            select count(s) from CoachSlot s
            where s.kind = 'ASSESSMENT' and s.status = 'BOOKED' and s.member.id = :memberId
              and s.startAt >= :from and s.startAt < :to
            """)
    long countMemberAssessments(@Param("memberId") Long memberId, @Param("from") OffsetDateTime from,
                                @Param("to") OffsetDateTime to);

    long countByKindAndStatusAndStartAtGreaterThanEqualAndStartAtLessThan(String kind, String status,
                                                                          OffsetDateTime from, OffsetDateTime to);

    // Xóa các khung đánh giá còn trống trong khoảng [from, to), có thể chỉ của một coach. Khung đã có người đặt không xóa
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from CoachSlot s
            where s.kind = 'ASSESSMENT' and s.status = 'OPEN'
              and s.startAt >= :from and s.startAt < :to
              and (:coachId is null or s.coach.id = :coachId)
            """)
    int deleteOpenAssessments(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                              @Param("coachId") Long coachId);

    // Quyền lợi đánh giá thể lực của member tại một ngày: [số tháng mỗi lượt, ngày bắt đầu gói]. Rỗng nếu không có.
    // Đọc từ quyền lợi đã chụp lúc mua gói (benefits_snapshot)
    @Query(value = """
            SELECT CAST(s.benefits_snapshot ->> 'assessment_every_months' AS integer), s.start_date
            FROM member_subscriptions s
            WHERE s.user_id = :memberId AND s.status = 'ACTIVE'
              AND CAST(:date AS date) BETWEEN s.start_date AND s.end_date
              AND s.benefits_snapshot ->> 'assessment_every_months' IS NOT NULL
            ORDER BY s.end_date DESC
            LIMIT 1
            """, nativeQuery = true)
    List<Object[]> findAssessmentEntitlement(@Param("memberId") Long memberId, @Param("date") java.time.LocalDate date);

    // Số hội viên đang có quyền lợi đánh giá thể lực
    @Query(value = """
            SELECT count(DISTINCT s.user_id) FROM member_subscriptions s
            WHERE s.status = 'ACTIVE' AND current_date BETWEEN s.start_date AND s.end_date
              AND s.benefits_snapshot ->> 'assessment_every_months' IS NOT NULL
            """, nativeQuery = true)
    long countAssessmentEntitledMembers();

    // Nhu cầu ước tính trong N ngày: mỗi hội viên có quyền lợi dùng N / (30 x số tháng mỗi lượt) lượt
    @Query(value = """
            SELECT COALESCE(SUM(CAST(:days AS numeric) / (30 * CAST(s.benefits_snapshot ->> 'assessment_every_months' AS integer))), 0)
            FROM member_subscriptions s
            WHERE s.status = 'ACTIVE' AND current_date BETWEEN s.start_date AND s.end_date
              AND s.benefits_snapshot ->> 'assessment_every_months' IS NOT NULL
            """, nativeQuery = true)
    java.math.BigDecimal estimateAssessmentDemand(@Param("days") int days);

    // Lớp học thắng suất trống: hủy hàng loạt các suất OPEN trùng giờ lớp
    @Modifying(flushAutomatically = true)
    @Query("""
            update CoachSlot s set s.status = 'CANCELED', s.canceledBy = :by, s.canceledAt = :now
            where s.id in :ids and s.status = 'OPEN'
            """)
    int cancelOpen(@Param("ids") Collection<Long> ids, @Param("by") User by, @Param("now") OffsetDateTime now);

    // Suất HELD đã quá hạn giữ chỗ và không có thanh toán nào chưa ngã ngũ (tiền có thể đang về)
    @Query(value = """
            SELECT s.id FROM coach_slots s
            WHERE s.status = 'HELD' AND s.held_until < :now
              AND NOT EXISTS (
                  SELECT 1 FROM orders o JOIN payments p ON p.order_id = o.id
                  WHERE o.coach_slot_id = s.id AND p.status IN ('CREATING', 'PENDING', 'SUCCESS'))
            """, nativeQuery = true)
    List<Long> findExpiredHoldIds(@Param("now") OffsetDateTime now);

    // BR-SCH-01: buổi học của coach chồng giờ hoặc cách khung dưới bufferMinutes phút. Trả tên class và giờ, rỗng nếu không trùng
    @Query(value = """
            SELECT c.name || ' (' || to_char(cs.session_date, 'DD/MM/YYYY') || ' ' || to_char(cs.start_time, 'HH24:MI') || ')'
            FROM class_sessions cs JOIN sport_classes c ON c.id = cs.class_id
            WHERE cs.coach_id = :coachId AND cs.status IN ('SCHEDULED', 'DONE')
              AND tstzrange((cs.session_date + cs.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh',
                            ((cs.session_date + cs.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') + make_interval(mins => :buf))
               && tstzrange(CAST(:startAt AS timestamptz), CAST(:endAt AS timestamptz) + make_interval(mins => :buf))
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findCoachSessionConflict(@Param("coachId") Long coachId,
                                              @Param("startAt") OffsetDateTime startAt,
                                              @Param("endAt") OffsetDateTime endAt,
                                              @Param("buf") int bufferMinutes);

    // BR-SCH-03: member đang giữ chỗ hoặc đã ghi danh class có buổi trùng giờ suất này. Trả tên class
    @Query(value = """
            SELECT c.name
            FROM class_registrations r
            JOIN class_sessions cs ON cs.class_id = r.class_id AND cs.status IN ('SCHEDULED', 'DONE')
            JOIN sport_classes c ON c.id = r.class_id
            WHERE r.user_id = :memberId AND r.status IN ('PENDING_PAYMENT', 'REGISTERED')
              AND tstzrange((cs.session_date + cs.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh',
                            ((cs.session_date + cs.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') + make_interval(mins => :buf))
               && tstzrange(CAST(:startAt AS timestamptz), CAST(:endAt AS timestamptz) + make_interval(mins => :buf))
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findMemberClassConflict(@Param("memberId") Long memberId,
                                             @Param("startAt") OffsetDateTime startAt,
                                             @Param("endAt") OffsetDateTime endAt,
                                             @Param("buf") int bufferMinutes);

    // Chiều ngược lại: khi member ghi danh class, suất 1-1 HELD/BOOKED của họ có trùng buổi của class không. Trả giờ suất
    @Query(value = """
            SELECT to_char(s.start_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'DD/MM/YYYY HH24:MI')
            FROM class_sessions a
            JOIN coach_slots s ON s.member_id = :memberId AND s.status IN ('HELD', 'BOOKED')
            WHERE a.class_id = :classId AND a.status = 'SCHEDULED'
              AND tstzrange(s.start_at, s.end_at + make_interval(mins => :buf))
               && tstzrange((a.session_date + a.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh',
                            ((a.session_date + a.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') + make_interval(mins => :buf))
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findMemberSlotConflictForClass(@Param("memberId") Long memberId,
                                                    @Param("classId") Long classId,
                                                    @Param("buf") int bufferMinutes);
}
