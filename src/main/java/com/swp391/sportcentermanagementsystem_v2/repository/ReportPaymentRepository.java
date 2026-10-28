package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/** Reporting-only read repository. All queries are derived from method names by Spring Data JPA. */
public interface ReportPaymentRepository extends JpaRepository<Payment, Long> {

    @EntityGraph(attributePaths = {"order.subscription", "order.registration", "order.coachSlot"})
    List<Payment> findByStatusInAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtAsc(
            Collection<String> statuses, OffsetDateTime paidAtStart, OffsetDateTime paidAtEnd);
}
