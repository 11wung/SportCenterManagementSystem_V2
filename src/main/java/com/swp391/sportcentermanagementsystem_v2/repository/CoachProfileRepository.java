package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CoachProfileRepository extends JpaRepository<CoachProfile, Long> {
    Optional<CoachProfile> findById(Long userId);

    @Query("select distinct p from CoachProfile p left join fetch p.sports where p.userId in :ids")
    List<CoachProfile> findWithSportsByUserIds(@Param("ids") Collection<Long> ids);
}
