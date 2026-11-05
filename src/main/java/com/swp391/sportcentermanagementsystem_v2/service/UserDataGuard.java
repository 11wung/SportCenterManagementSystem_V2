package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

// Tài khoản đã có đơn, thanh toán, ghi danh... thì không được xóa.
// Danh sách bảng tham chiếu users lấy từ chính database, nên bảng mới tự được tính vào.
@Service
@RequiredArgsConstructor
public class UserDataGuard {

    // Hồ sơ và thông báo đi theo tài khoản (xóa tài khoản thì xóa theo), không tính là dữ liệu nghiệp vụ
    private static final Set<String> FOLLOWS_ACCOUNT = Set.of(
            "member_profiles", "notifications", "coach_profiles", "staff_profiles", "coach_sports");

    private record Reference(String table, String column) {
    }

    private final JdbcTemplate jdbcTemplate;

    private volatile List<Reference> references;

    public boolean hasBusinessData(Long userId) {
        for (Reference ref : references()) {
            Boolean found = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM " + ref.table() + " WHERE \"" + ref.column() + "\" = ?)",
                    Boolean.class, userId);
            if (Boolean.TRUE.equals(found)) {
                return true;
            }
        }
        return false;
    }

    // Tên bảng và cột lấy từ danh mục hệ thống của PostgreSQL, không đến từ người dùng
    private List<Reference> references() {
        List<Reference> list = references;
        if (list == null) {
            list = jdbcTemplate.query("""
                    SELECT c.conrelid::regclass::text AS tbl, a.attname AS col
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                    WHERE c.confrelid = 'public.users'::regclass AND c.contype = 'f'
                      AND c.conrelid <> 'public.users'::regclass
                    """, (rs, i) -> new Reference(rs.getString("tbl"), rs.getString("col")))
                    .stream()
                    .filter(ref -> !FOLLOWS_ACCOUNT.contains(ref.table().replace("public.", "").replace("\"", "")))
                    .toList();
            references = list;
        }
        return list;
    }
}
