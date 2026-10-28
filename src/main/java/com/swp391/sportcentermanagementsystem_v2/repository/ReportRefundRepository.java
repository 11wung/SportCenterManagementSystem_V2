package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Reporting-only refund reads; no custom/native query strings. */
public interface ReportRefundRepository extends JpaRepository<Refund, Long> {

    @EntityGraph(attributePaths = "payment")
    List<Refund> findByPayment_IdIn(Collection<Long> paymentIds);

    @EntityGraph(attributePaths = {"payment", "payment.payer", "processedBy"})
    List<Refund> findByStatusOrderByRequestedAtAsc(String status);
}
