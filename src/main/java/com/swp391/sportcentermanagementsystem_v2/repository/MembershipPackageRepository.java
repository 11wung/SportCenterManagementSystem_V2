package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MembershipPackageRepository extends JpaRepository<MembershipPackage, Long> {

    List<MembershipPackage> findByIsActiveTrue();

    Optional<MembershipPackage> findByIdAndIsActiveTrue(Long id);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByTierLevel(Short tierLevel);

    // Dời các hạng từ `tier` trở lên thêm một bậc. Chia hai bước (nhấc lên rồi hạ xuống) vì uq_package_tier
    // không cho hai gói trùng hạng dù chỉ trong lúc đang cập nhật.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MembershipPackage p set p.tierLevel = p.tierLevel + 1000 where p.tierLevel >= :tier")
    int liftFrom(@Param("tier") short tier);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MembershipPackage p set p.tierLevel = p.tierLevel - 999 where p.tierLevel >= 1000")
    int landLifted();

    // Xóa một gói thì các hạng cao hơn tụt xuống một bậc để không còn hạng trống ở giữa (cùng cách hai bước như trên)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MembershipPackage p set p.tierLevel = p.tierLevel + 1000 where p.tierLevel > :tier")
    int liftAbove(@Param("tier") short tier);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MembershipPackage p set p.tierLevel = p.tierLevel - 1001 where p.tierLevel >= 1000")
    int landLoweredByOne();
}