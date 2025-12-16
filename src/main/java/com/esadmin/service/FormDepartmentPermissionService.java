package com.esadmin.service;

import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.FormDepartmentPermissionDto;
import com.esadmin.dto.FormDto;
import com.esadmin.entity.FormDepartmentPermission;
import com.esadmin.entity.OrgDepartment;
import com.esadmin.repository.FormDepartmentPermissionRepository;
import com.esadmin.repository.OrgDepartmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FormDepartmentPermissionService {

    private static final Logger log = LoggerFactory.getLogger(FormDepartmentPermissionService.class);

    @Autowired
    private FormDepartmentPermissionRepository permissionRepository;

    @Autowired
    private OrgDepartmentRepository departmentRepository;

    @Autowired
    private FormService formService;

    @Autowired
    private ExcelImportService excelImportService;

    /**
     * 获取所有数据源的权限配置（包括表单和Excel）
     */
    public List<FormDepartmentPermissionDto> getAllPermissions() {
        List<FormDepartmentPermissionDto> result = new ArrayList<>();
        
        try {
            // 获取所有已配置的权限
            List<FormDepartmentPermission> permissions = permissionRepository.findAllActivePermissions();
            Map<String, List<FormDepartmentPermission>> permissionMap = permissions.stream()
                .collect(Collectors.groupingBy(p -> p.getSourceType() + ":" + p.getSourceId()));

            // 获取所有表单
            List<FormDto> forms = formService.getAllForms();
            if (forms != null) {
                for (FormDto form : forms) {
                    FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
                    dto.setSourceType("form");
                    dto.setSourceId(form.getId());
                    dto.setSourceName(form.getName());

                    String key = "form:" + form.getId();
                    List<FormDepartmentPermission> formPermissions = permissionMap.get(key);
                    
                    if (formPermissions == null || formPermissions.isEmpty()) {
                        dto.setAllowAllDepartments(true);
                        dto.setDepartments(new ArrayList<>());
                    } else {
                        dto.setAllowAllDepartments(false);
                        List<FormDepartmentPermissionDto.DepartmentInfo> deptInfos = formPermissions.stream()
                            .map(p -> new FormDepartmentPermissionDto.DepartmentInfo(
                                String.valueOf(p.getDepartmentId()),
                                p.getDepartmentName(),
                                p.getPermissionType(),
                                p.getIsActive() == 1
                            ))
                            .collect(Collectors.toList());
                        dto.setDepartments(deptInfos);
                        
                        // 设置最后更新时间
                        Optional<LocalDateTime> latestUpdateTime = formPermissions.stream()
                            .map(FormDepartmentPermission::getUpdateTime)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo);
                        latestUpdateTime.ifPresent(dto::setUpdateTime);
                    }

                    result.add(dto);
                }
            }

            // 获取所有Excel数据源
            List<ExcelImportMetadata> excelList = excelImportService.listImports();
            if (excelList != null) {
                for (ExcelImportMetadata excel : excelList) {
                    FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
                    dto.setSourceType("excel");
                    dto.setSourceId(excel.getTableName());
                    dto.setSourceName(excel.getDisplayName());

                    String key = "excel:" + excel.getTableName();
                    List<FormDepartmentPermission> excelPermissions = permissionMap.get(key);
                    
                    if (excelPermissions == null || excelPermissions.isEmpty()) {
                        dto.setAllowAllDepartments(true);
                        dto.setDepartments(new ArrayList<>());
                    } else {
                        dto.setAllowAllDepartments(false);
                        List<FormDepartmentPermissionDto.DepartmentInfo> deptInfos = excelPermissions.stream()
                            .map(p -> new FormDepartmentPermissionDto.DepartmentInfo(
                                String.valueOf(p.getDepartmentId()),
                                p.getDepartmentName(),
                                p.getPermissionType(),
                                p.getIsActive() == 1
                            ))
                            .collect(Collectors.toList());
                        dto.setDepartments(deptInfos);
                        
                        // 设置最后更新时间
                        Optional<LocalDateTime> latestUpdateTime = excelPermissions.stream()
                            .map(FormDepartmentPermission::getUpdateTime)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo);
                        latestUpdateTime.ifPresent(dto::setUpdateTime);
                    }

                    result.add(dto);
                }
            }

        } catch (Exception e) {
            log.error("获取权限配置列表失败", e);
            throw new RuntimeException("获取权限配置列表失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 获取所有部门列表
     */
    public List<OrgDepartment> getAllDepartments() {
        return departmentRepository.findAllDepartments();
    }

    /**
     * 根据数据源获取权限配置
     */
    public FormDepartmentPermissionDto getSourcePermissions(String sourceType, String sourceId) {
        FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
        dto.setSourceType(sourceType);
        dto.setSourceId(sourceId);

        // 获取数据源名称
        if ("form".equals(sourceType)) {
            List<FormDto> forms = formService.getAllForms();
            if (forms != null) {
                Optional<FormDto> form = forms.stream()
                    .filter(f -> sourceId.equals(f.getId()))
                    .findFirst();
                if (form.isPresent()) {
                    dto.setSourceName(form.get().getName());
                } else {
                    return null; // 表单不存在
                }
            }
        } else if ("excel".equals(sourceType)) {
            List<ExcelImportMetadata> excelList = excelImportService.listImports();
            if (excelList != null) {
                Optional<ExcelImportMetadata> excel = excelList.stream()
                    .filter(e -> sourceId.equals(e.getTableName()))
                    .findFirst();
                if (excel.isPresent()) {
                    dto.setSourceName(excel.get().getDisplayName());
                } else {
                    return null; // Excel数据源不存在
                }
            }
        }

        List<FormDepartmentPermission> permissions = permissionRepository.findBySource(sourceType, sourceId);
        
        if (permissions.isEmpty()) {
            dto.setAllowAllDepartments(true);
            dto.setDepartments(new ArrayList<>());
        } else {
            dto.setAllowAllDepartments(false);
            List<FormDepartmentPermissionDto.DepartmentInfo> deptInfos = permissions.stream()
                .map(p -> new FormDepartmentPermissionDto.DepartmentInfo(
                    String.valueOf(p.getDepartmentId()),
                    p.getDepartmentName(),
                    p.getPermissionType(),
                    p.getIsActive() == 1
                ))
                .collect(Collectors.toList());
            dto.setDepartments(deptInfos);
        }

        return dto;
    }

    /**
     * 保存数据源权限配置
     */
    @Transactional
    public void saveSourcePermissions(String sourceType, String sourceId, List<Long> departmentIds, Long creatorId) {
        // 获取数据源名称
        String sourceName = null;
        if ("form".equals(sourceType)) {
            List<FormDto> forms = formService.getAllForms();
            if (forms != null) {
                Optional<FormDto> form = forms.stream()
                    .filter(f -> sourceId.equals(f.getId()))
                    .findFirst();
                if (form.isPresent()) {
                    sourceName = form.get().getName();
                } else {
                    throw new RuntimeException("表单不存在");
                }
            }
        } else if ("excel".equals(sourceType)) {
            List<ExcelImportMetadata> excelList = excelImportService.listImports();
            if (excelList != null) {
                Optional<ExcelImportMetadata> excel = excelList.stream()
                    .filter(e -> sourceId.equals(e.getTableName()))
                    .findFirst();
                if (excel.isPresent()) {
                    sourceName = excel.get().getDisplayName();
                } else {
                    throw new RuntimeException("Excel数据源不存在");
                }
            }
        }

        // 先禁用该数据源的所有现有权限
        permissionRepository.deleteBySource(sourceType, sourceId);

        if (departmentIds != null && !departmentIds.isEmpty()) {
            // 获取部门信息
            List<OrgDepartment> departments = departmentRepository.findDepartmentsByIds(departmentIds);
            Map<Long, String> deptNameMap = departments.stream()
                .collect(Collectors.toMap(OrgDepartment::getId, OrgDepartment::getName));

            // 添加新的权限配置
            for (Long departmentId : departmentIds) {
                String departmentName = deptNameMap.get(departmentId);
                if (departmentName != null) {
                    String deptKey = String.valueOf(departmentId);
                    // 检查是否已存在
                    FormDepartmentPermission existing = permissionRepository.findBySourceAndDepartmentId(sourceType, sourceId, deptKey);
                    if (existing != null) {
                        // 更新现有记录
                        existing.setIsActive(1);
                        existing.setUpdateTime(LocalDateTime.now());
                        existing.setDepartmentName(departmentName);
                        existing.setSourceName(sourceName);
                        if (creatorId != null) {
                            existing.setCreatorId(creatorId);
                        }
                        permissionRepository.save(existing);
                    } else {
                        // 创建新记录
                        FormDepartmentPermission permission = new FormDepartmentPermission();
                        permission.setSourceType(sourceType);
                        permission.setSourceId(sourceId);
                        permission.setSourceName(sourceName);
                        permission.setDepartmentId(deptKey);
                        permission.setDepartmentName(departmentName);
                        permission.setPermissionType("read");
                        permission.setIsActive(1);
                        permission.setCreatorId(creatorId);
                        permissionRepository.save(permission);
                    }
                }
            }
        }
        // 如果departmentIds为空，表示允许所有部门访问（不添加任何权限记录）
    }

    /**
     * 检查用户所在部门是否有权限访问指定数据源
     */
    public boolean hasPermission(String sourceType, String sourceId, Long departmentId) {
        if (sourceType == null || sourceId == null || departmentId == null) {
            return false;
        }

        // 如果没有任何权限配置，默认所有部门都可以访问
        boolean hasAnyPermission = permissionRepository.hasAnyPermissionForSource(sourceType, sourceId);
        if (!hasAnyPermission) {
            return true;
        }

        // 检查特定部门权限
        return permissionRepository.hasPermission(sourceType, sourceId, String.valueOf(departmentId));
    }

    /**
     * 删除数据源权限配置（设为所有部门可访问）
     */
    @Transactional
    public void removeSourcePermissions(String sourceType, String sourceId) {
        permissionRepository.deleteBySource(sourceType, sourceId);
    }

    /**
     * 搜索权限配置
     */
    public List<FormDepartmentPermissionDto> searchPermissions(String keyword) {
        List<FormDepartmentPermissionDto> allPermissions = getAllPermissions();
        
        if (keyword == null || keyword.trim().isEmpty()) {
            return allPermissions;
        }

        String lowerKeyword = keyword.toLowerCase();
        return allPermissions.stream()
            .filter(dto -> dto.getSourceName().toLowerCase().contains(lowerKeyword) ||
                          dto.getDepartments().stream()
                              .anyMatch(dept -> dept.getDepartmentName().toLowerCase().contains(lowerKeyword)))
            .collect(Collectors.toList());
    }
}
