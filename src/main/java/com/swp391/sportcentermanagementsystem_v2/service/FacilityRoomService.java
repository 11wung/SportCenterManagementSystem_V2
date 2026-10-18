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

    public FacilityRoomService(FacilityRoomRepository facilityRoomRepository) {
        this.facilityRoomRepository = facilityRoomRepository;
    }

    public List<FacilityRoomResponse> getAll() {
        List<FacilityRoom> rooms = facilityRoomRepository.findAll();
        List<FacilityRoomResponse> responses = new ArrayList<>();

        for (FacilityRoom room : rooms) {
            responses.add(new FacilityRoomResponse(room));
        }

        return responses;
    }

    public FacilityRoomResponse getById(Long id) {
        FacilityRoom room = facilityRoomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Facility room does not found with ID: " + id));

        return new FacilityRoomResponse(room);
    }

    @Transactional
    public FacilityRoomResponse create(FacilityRoomRequest request) {
        String roomName = request.getRoomName().trim();

        if (facilityRoomRepository.existsByRoomName(roomName)) {
            throw new BusinessRuleException("Facility room already exist");
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
                        "Facility room does not found with ID: " + id));

        String roomName = request.getRoomName().trim();

        if (facilityRoomRepository.existsByRoomNameAndIdNot(roomName, id)) {
            throw new BusinessRuleException("Facility room already exist");
        }

        room.setRoomName(roomName);
        room.setFloor(request.getFloor());
        room.setCapacity(request.getCapacity());
        room.setDescription(request.getDescription());

        FacilityRoom updatedRoom = facilityRoomRepository.save(room);

        return new FacilityRoomResponse(updatedRoom);
    }

    @Transactional
    public void delete(Long id) {
        FacilityRoom room = facilityRoomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Facility room does not found with ID: " + id));

        facilityRoomRepository.delete(room);
    }
}
