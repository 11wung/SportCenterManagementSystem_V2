
package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "facility_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FacilityRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "room_name", nullable = false, unique = true, length = 100)
    private String roomName;

    @Column(name = "floor", length = 20)
    private String floor;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "maintenance_status", nullable = false, length = 20)
    private String maintenanceStatus = "AVAILABLE";

    // Bảng nối room_sports: phòng dùng được cho những môn nào (BR-SCH-04)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "room_sports",
            joinColumns = @JoinColumn(name = "room_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<SportCategory> sports = new HashSet<>();
}
