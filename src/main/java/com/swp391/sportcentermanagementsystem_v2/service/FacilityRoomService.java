package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.FacilityRoomRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FacilityRoomResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.ArrayList;
import java.util.List;

@Service
public class FacilityRoomService {

    private final FacilityRoomRepository facilityRoomRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public FacilityRoomService(FacilityRoomRepository facilityRoomRepository, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.facilityRoomRepository = facilityRoomRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<FacilityRoomResponse> getAll() {
        List<FacilityRoom> rooms = facilityRoomRepository.findAll();
        List<FacilityRoomResponse> responses = new ArrayList<>();

        for (FacilityRoom room : rooms) {
            responses.add(new FacilityRoomResponse(room));
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public FacilityRoomResponse getById(Long id) {
        FacilityRoom room = facilityRoomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy phòng có mã " + id));

        return new FacilityRoomResponse(room);
    }

    @Transactional
    public FacilityRoomResponse create(FacilityRoomRequest request) {
        String roomName = request.getRoomName().trim();

        if (facilityRoomRepository.existsByRoomName(roomName)) {
            throw new BusinessRuleException("Tên phòng đã tồn tại");
        }

        FacilityRoom room = new FacilityRoom();
        room.setRoomName(roomName);
        room.setFloor(request.getFloor());
        room.setCapacity(request.getCapacity());
        room.setDescription(request.getDescription());
        room.setMaintenanceStatus("AVAILABLE");

        FacilityRoom savedRoom = facilityRoomRepository.save(room);

        return new FacilityRoomResponse(savedRoom);
    }

    @Transactional
    public FacilityRoomResponse update(Long id, FacilityRoomRequest request) {
        FacilityRoom room = facilityRoomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy phòng có mã " + id));

        String roomName = request.getRoomName().trim();

        if (facilityRoomRepository.existsByRoomNameAndIdNot(roomName, id)) {
            throw new BusinessRuleException("Tên phòng đã tồn tại");
        }

        room.setRoomName(roomName);
        room.setFloor(request.getFloor());
        room.setCapacity(request.getCapacity());
        room.setDescription(request.getDescription());

        FacilityRoom updatedRoom = facilityRoomRepository.save(room);

        return new FacilityRoomResponse(updatedRoom);
    }

    @Transactional
    public com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RemovalResponse delete(Long id) {
        FacilityRoom room = facilityRoomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Không tìm thấy phòng có mã " + id));
        var usage = usage(id);
        if ("DELETED".equals(usage.result())) {
            facilityRoomRepository.delete(room);
            return usage;
        }
        // Đã có lớp hoặc buổi 1-1 dùng phòng: chỉ ngừng sử dụng, đóng phòng để không xếp lịch mới vào
        room.setArchivedAt(java.time.OffsetDateTime.now());
        room.setMaintenanceStatus("CLOSED");
        facilityRoomRepository.save(room);
        return usage;
    }

    @org.springframework.transaction.annotation.Transactional
    public FacilityRoomResponse restore(Long id) {
        FacilityRoom room = facilityRoomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy phòng có mã " + id));
        room.setArchivedAt(null);
        room.setMaintenanceStatus("AVAILABLE");
        return new FacilityRoomResponse(facilityRoomRepository.save(room));
    }

    // Chưa từng có lịch lớp, buổi lớp hay buổi 1-1 thì xóa hẳn được; kèm số buổi sắp tới còn dùng phòng
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RemovalResponse usage(Long id) {
        Long used = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM class_schedules WHERE room_id = ?)
                     + (SELECT count(*) FROM class_sessions WHERE room_id = ?)
                     + (SELECT count(*) FROM coach_slots WHERE room_id = ?)
                """, Long.class, id, id, id);
        Long upcoming = jdbcTemplate.queryForObject("""
                SELECT (SELECT count(*) FROM class_sessions WHERE room_id = ? AND status = 'SCHEDULED' AND session_date >= current_date)
                     + (SELECT count(*) FROM coach_slots WHERE room_id = ? AND status IN ('HELD', 'BOOKED') AND start_at > now())
                """, Long.class, id, id);
        return new com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RemovalResponse(
                used == null || used == 0 ? "DELETED" : "ARCHIVED", upcoming == null ? 0 : upcoming);
    }
}
