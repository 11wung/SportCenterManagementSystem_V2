package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SupportRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupportRequestRepository extends JpaRepository<SupportRequest, Long> {

    // Khóa dòng để hai lễ tân không cùng đổi trạng thái
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SupportRequest r where r.id = :id")
    Optional<SupportRequest> findByIdForUpdate(@Param("id") Long id);

    // status rỗng = mọi trạng thái, memberId 0 = mọi hội viên; mới nhất trước
    @Query("""
            select r from SupportRequest r
            join fetch r.member
            left join fetch r.handledBy
            where (:status = '' or r.status = :status)
              and (:memberId = 0 or r.member.id = :memberId)
            order by r.createdAt desc
            """)
    List<SupportRequest> search(@Param("status") String status, @Param("memberId") long memberId);
}
