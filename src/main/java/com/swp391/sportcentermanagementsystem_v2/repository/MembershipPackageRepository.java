package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MembershipPackageRepository extends JpaRepository<MembershipPackage, Long> {

    List<MembershipPackage> findByIsActiveTrue();

    Optional<MembershipPackage> findByIdAndIsActiveTrue(Long id);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByNameIgnoreCase(String name);
}