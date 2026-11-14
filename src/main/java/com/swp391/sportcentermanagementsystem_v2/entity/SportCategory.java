
package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sport_categories")
@Getter
@Setter
@NoArgsConstructor
public class SportCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    // Môn đã ngừng sử dụng (xóa mềm): ẩn khỏi trang chủ, không tạo khóa/lớp/khóa 1-1 mới, không nhận đăng ký mới
    @Column(name = "archived_at")
    private java.time.OffsetDateTime archivedAt;
}
