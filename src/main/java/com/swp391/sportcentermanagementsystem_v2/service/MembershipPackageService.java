
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MembershipPackageResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MembershipPackageService {

    private final MembershipPackageRepository membershipPackageRepository;
    private final EntityManager entityManager;

    // Get All Active Packages
    @Transactional(readOnly = true)
    public List<MembershipPackageResponse> getActivePackages() {

        return toResponses(membershipPackageRepository.findByIsActiveTrue());
    }

    private List<MembershipPackageResponse> toResponses(List<MembershipPackage> packages) {

        List<MembershipPackageResponse> responses = new ArrayList<>();

        for (MembershipPackage membershipPackage : packages) {

            MembershipPackageResponse response = new MembershipPackageResponse();

            response.setId(membershipPackage.getId());
            response.setCode(membershipPackage.getCode());
            response.setName(membershipPackage.getName());
            response.setDescription(membershipPackage.getDescription());
            response.setDurationMonths(membershipPackage.getDurationMonths());
            response.setPrice(membershipPackage.getPrice());
            response.setMaxClassesPerWeek(
                    membershipPackage.getMaxClassesPerWeek());
            response.setTierLevel(membershipPackage.getTierLevel());
            response.setIsActive(membershipPackage.getIsActive());
            response.setCreatedAt(membershipPackage.getCreatedAt());
            response.setUpdatedAt(membershipPackage.getUpdatedAt());

            responses.add(response);
        }

        return responses;
    }

    // Get All Packages (manager): gồm cả gói đang ẩn
    @Transactional(readOnly = true)
    public List<MembershipPackageResponse> getAllPackages() {
        return toResponses(membershipPackageRepository.findAll());
    }

    // Create New Package
    @Transactional
    public MembershipPackageResponse createPackage(
            CreateMembershipPackageRequest request) {

        // 1. Normalize input
        // Code luon duoc luu bang chu IN HOA.
        String code = request.getCode()
                .trim()
                .toUpperCase(Locale.ROOT);

        String name = request.getName().trim();

        // 2. Check duplicate code
        if (membershipPackageRepository.existsByCodeIgnoreCase(code)) {
            throw new BusinessRuleException(
                    "Package code already exists!");
        }

        // 3. Check duplicate name
        if (membershipPackageRepository.existsByNameIgnoreCase(name)) {
            throw new BusinessRuleException(
                    "Package name already exists!");
        }

        // 4. Create Entity
        MembershipPackage membershipPackage = new MembershipPackage();

        membershipPackage.setCode(code);
        membershipPackage.setName(name);
        membershipPackage.setDescription(request.getDescription());
        membershipPackage.setDurationMonths(request.getDurationMonths());
        membershipPackage.setPrice(request.getPrice());
        membershipPackage.setMaxClassesPerWeek(request.getMaxClassesPerWeek());

        // Tier phai >= 1 theo Business Rule.
        membershipPackage.setTierLevel(request.getTierLevel());

        // Package moi duoc mo ban ngay.
        membershipPackage.setIsActive(true);

        // 5. Save to database
        MembershipPackage savedPackage =
                membershipPackageRepository.saveAndFlush(membershipPackage);

        // 6. Reload database-generated values
        entityManager.refresh(savedPackage);

        // 7. Map Entity to Response DTO
        MembershipPackageResponse response = new MembershipPackageResponse();

        response.setId(savedPackage.getId());
        response.setCode(savedPackage.getCode());
        response.setName(savedPackage.getName());
        response.setDescription(savedPackage.getDescription());
        response.setDurationMonths(savedPackage.getDurationMonths());
        response.setPrice(savedPackage.getPrice());
        response.setMaxClassesPerWeek(savedPackage.getMaxClassesPerWeek());
        response.setTierLevel(savedPackage.getTierLevel());
        response.setIsActive(savedPackage.getIsActive());
        response.setCreatedAt(savedPackage.getCreatedAt());
        response.setUpdatedAt(savedPackage.getUpdatedAt());

        return response;
    }

    // Get Package by ID
    @Transactional(readOnly = true)
    public MembershipPackageResponse getPackageById(Long id) {

        // 1. Find active package by ID
        MembershipPackage membershipPackage =
                membershipPackageRepository.findByIdAndIsActiveTrue(id)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Membership package not found!"));

        // 2. Map Entity to Response DTO
        MembershipPackageResponse response = new MembershipPackageResponse();

        response.setId(membershipPackage.getId());
        response.setCode(membershipPackage.getCode());
        response.setName(membershipPackage.getName());
        response.setDescription(membershipPackage.getDescription());
        response.setDurationMonths(membershipPackage.getDurationMonths());
        response.setPrice(membershipPackage.getPrice());
        response.setMaxClassesPerWeek(
                membershipPackage.getMaxClassesPerWeek());
        response.setTierLevel(membershipPackage.getTierLevel());
        response.setIsActive(membershipPackage.getIsActive());
        response.setCreatedAt(membershipPackage.getCreatedAt());
        response.setUpdatedAt(membershipPackage.getUpdatedAt());

        return response;
    }

    // Update Package Information
    @Transactional
    public MembershipPackageResponse updatePackage(
            Long id,
            UpdateMembershipPackageRequest request) {

        // 1. Khong cho thay doi status bang API update information
        if (request.getIsActive() != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Package status must be updated through the status API!");
        }

        // 2. Find package by ID (including inactive packages)
        MembershipPackage membershipPackage =
                membershipPackageRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Membership package not found!"));

        boolean hasChanges = false;

        // 3. Update name
        if (request.getName() != null) {

            String name = request.getName().trim();

            if (name.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Package name cannot be blank!");
            }

            if (!name.equals(membershipPackage.getName())) {

                // Neu ten moi chi khac chu hoa/thuong voi
                // ten hien tai thi khong phai trung voi Package khac.
                if (!name.equalsIgnoreCase(membershipPackage.getName())
                        && membershipPackageRepository
                                .existsByNameIgnoreCase(name)) {

                    throw new BusinessRuleException(
                            "Package name already exists!");
                }

                membershipPackage.setName(name);
                hasChanges = true;
            }
        }

        // 4. Update description
        if (request.getDescription() != null) {

            if (!request.getDescription()
                    .equals(membershipPackage.getDescription())) {

                membershipPackage.setDescription(request.getDescription());
                hasChanges = true;
            }
        }

        // 5. Update duration
        if (request.getDurationMonths() != null) {

            if (!request.getDurationMonths()
                    .equals(membershipPackage.getDurationMonths())) {

                membershipPackage.setDurationMonths(
                        request.getDurationMonths());
                hasChanges = true;
            }
        }

        // 6. Update price
        if (request.getPrice() != null) {

            if (request.getPrice()
                    .compareTo(membershipPackage.getPrice()) != 0) {

                membershipPackage.setPrice(request.getPrice());
                hasChanges = true;
            }
        }

        // 7. Update weekly class limit
        if (request.getMaxClassesPerWeek() != null) {

            if (!request.getMaxClassesPerWeek()
                    .equals(membershipPackage.getMaxClassesPerWeek())) {

                membershipPackage.setMaxClassesPerWeek(
                        request.getMaxClassesPerWeek());
                hasChanges = true;
            }
        }

        // 8. Update tier level
        if (request.getTierLevel() != null) {

            if (!request.getTierLevel()
                    .equals(membershipPackage.getTierLevel())) {

                membershipPackage.setTierLevel(request.getTierLevel());
                hasChanges = true;
            }
        }

        // 9. Save only when data has changed
        if (hasChanges) {
            membershipPackageRepository.saveAndFlush(membershipPackage);
            entityManager.refresh(membershipPackage);
        }

        // 10. Map Entity to Response DTO
        MembershipPackageResponse response = new MembershipPackageResponse();

        response.setId(membershipPackage.getId());
        response.setCode(membershipPackage.getCode());
        response.setName(membershipPackage.getName());
        response.setDescription(membershipPackage.getDescription());
        response.setDurationMonths(membershipPackage.getDurationMonths());
        response.setPrice(membershipPackage.getPrice());
        response.setMaxClassesPerWeek(
                membershipPackage.getMaxClassesPerWeek());
        response.setTierLevel(membershipPackage.getTierLevel());
        response.setIsActive(membershipPackage.getIsActive());
        response.setCreatedAt(membershipPackage.getCreatedAt());
        response.setUpdatedAt(membershipPackage.getUpdatedAt());

        return response;
    }

    // Update Package Status
    @Transactional
    public MembershipPackageResponse updatePackageStatus(
            Long id,
            UpdateMembershipPackageRequest request) {

        // 1. Reject fields that do not belong to this API
        if (request.getName() != null
                || request.getDescription() != null
                || request.getDurationMonths() != null
                || request.getPrice() != null
                || request.getMaxClassesPerWeek() != null
                || request.getTierLevel() != null) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only isActive can be updated through this API!");
        }

        // 2. Check required status
        if (request.getIsActive() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Package status is required!");
        }

        // 3. Find package by ID
        MembershipPackage membershipPackage =
                membershipPackageRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Membership package not found!"));

        // 4. Update only when status changes
        if (!request.getIsActive()
                .equals(membershipPackage.getIsActive())) {

            membershipPackage.setIsActive(request.getIsActive());

            membershipPackageRepository.saveAndFlush(membershipPackage);
            entityManager.refresh(membershipPackage);
        }

        // 5. Map Entity to Response DTO
        MembershipPackageResponse response = new MembershipPackageResponse();

        response.setId(membershipPackage.getId());
        response.setCode(membershipPackage.getCode());
        response.setName(membershipPackage.getName());
        response.setDescription(membershipPackage.getDescription());
        response.setDurationMonths(membershipPackage.getDurationMonths());
        response.setPrice(membershipPackage.getPrice());
        response.setMaxClassesPerWeek(
                membershipPackage.getMaxClassesPerWeek());
        response.setTierLevel(membershipPackage.getTierLevel());
        response.setIsActive(membershipPackage.getIsActive());
        response.setCreatedAt(membershipPackage.getCreatedAt());
        response.setUpdatedAt(membershipPackage.getUpdatedAt());

        return response;
    }
}
