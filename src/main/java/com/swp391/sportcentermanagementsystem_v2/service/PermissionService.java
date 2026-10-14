package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PermissionResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Permission;
import com.swp391.sportcentermanagementsystem_v2.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PermissionService {
    private final PermissionRepository permissionRepository;

    @Transactional(readOnly = true)
    public List<PermissionResponse> getAllPermissions() {

        List<Permission> permissions = permissionRepository.findAll();

        List<PermissionResponse> responses = new ArrayList<>();

        for (Permission permission : permissions) {
            PermissionResponse permissionResponse = new PermissionResponse();
            permissionResponse.setId(permission.getId());
            permissionResponse.setName(permission.getName());
            permissionResponse.setCode(permission.getCode());
            permissionResponse.setDescription(permission.getDescription());
            responses.add(permissionResponse);

        }

        return responses;
    }
}
