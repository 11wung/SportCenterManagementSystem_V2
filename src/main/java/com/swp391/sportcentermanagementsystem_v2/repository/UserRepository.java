package com.swp391.sportcentermanagementsystem_v2.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);

    // Không phân biệt chữ hoa/thường: dùng khi đăng ký/đăng nhập/tạo nhân viên,
    // để "Lan@x.com" và "lan@x.com" là cùng một tài khoản.
    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "role")
    Optional<User> findByEmailIgnoreCase(String email);

    List<User> findByFullNameContainingIgnoreCase(String fullName);

    List<User> findByRole_CodeAndFullNameContainingIgnoreCase(
            String roleCode,
            String fullName);
            
    // Khóa Member khi tạo đơn hoặc xử lý thanh toán.
    // Ngăn hai transaction cùng thao tác Membership của một Member.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :memberId")
    Optional<User> findByIdForUpdate(
            @Param("memberId") Long memberId);

    // Số tài khoản theo vai trò.
    long countByRole_Code(String roleCode);

    long countByRole_CodeAndIsActive(String roleCode, Boolean isActive);

    long countByRole_CodeAndCreatedAtGreaterThanEqual(String roleCode, OffsetDateTime since);

    // Member đang hoạt động nhưng chưa có gói ACTIVE hoặc SCHEDULED nào.
    @Query("""
            SELECT COUNT(u) FROM User u
            WHERE u.role.code = 'MEMBER'
              AND u.isActive = true
              AND NOT EXISTS (
                  SELECT 1 FROM MemberSubscription s
                  WHERE s.user = u
                    AND s.status IN ('ACTIVE', 'SCHEDULED'))
            """)
    long countActiveMembersWithoutPackage();
}
