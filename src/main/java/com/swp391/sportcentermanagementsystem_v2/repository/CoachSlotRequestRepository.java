package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface CoachSlotRequestRepository extends JpaRepository<CoachSlotRequest, Long> {

    // Khóa dòng yêu cầu để duyệt, hủy, trả tiền và hết hạn không chạy chen nhau
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CoachSlotRequest r where r.id = :id")
    Optional<CoachSlotRequest> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = { "member", "coach", "category", "timeSlot" })
    List<CoachSlotRequest> findByMember_IdOrderByIdDesc(Long memberId);

    // Bảng của manager: lọc tùy chọn theo trạng thái, mới nhất trước
    @EntityGraph(attributePaths = { "member", "coach", "category", "timeSlot" })
    @Query("select r from CoachSlotRequest r where (:status is null or r.status = :status) order by r.id desc")
    List<CoachSlotRequest> search(@Param("status") String status);

    // Yêu cầu chờ duyệt đã quá hạn
    @Query("select r.id from CoachSlotRequest r where r.status = 'PENDING' and r.expiresAt < :now")
    List<Long> findPendingOverdueIds(@Param("now") OffsetDateTime now);

    // Yêu cầu đã duyệt nhưng quá hạn trả tiền
    @Query("select r.id from CoachSlotRequest r where r.status = 'APPROVED' and r.expiresAt < :now")
    List<Long> findApprovedOverdueIds(@Param("now") OffsetDateTime now);
}
