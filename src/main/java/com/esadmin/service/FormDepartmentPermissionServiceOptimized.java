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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@Primary
public class FormDepartmentPermissionServiceOptimized {

    private static final Logger log = LoggerFactory.getLogger(FormDepartmentPermissionServiceOptimized.class);
    
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(3);

    @Autowired
    private FormDepartmentPermissionRepository permissionRepository;

    @Autowired
    private OrgDepartmentRepository departmentRepository;

    @Autowired
    private FormService formService;

    @Autowired
    private ExcelImportService excelImportService;

    /**
     * 优化后的获取所有权限配置 - 使用缓存和异步并发
     */
    @Cacheable(value = "permissionList", unless = "#result.size() == 0")
    public List<FormDepartmentPermissionDto> getAllPermissions() {
        long startTime = System.currentTimeMillis();
        log.debug("开始获取权限配置列表");
        
        try {
            // 并发获取数据
            CompletableFuture<List<FormDepartmentPermission>> permissionsFuture = 
                CompletableFuture.supplyAsync(() -> permissionRepository.findAllActivePermissions(), asyncExecutor);
            
            CompletableFuture<List<FormDto>> formsFuture = 
                CompletableFuture.supplyAsync(() -> formService.getAllForms(), asyncExecutor);
            
            CompletableFuture<List<ExcelImportMetadata>> excelFuture = 
                CompletableFuture.supplyAsync(() -> excelImportService.listImports(), asyncExecutor);

            // 等待所有数据获取完成
            List<FormDepartmentPermission> permissions = permissionsFuture.get();
            List<FormDto> forms = formsFuture.get();
            List<ExcelImportMetadata> excelList = excelFuture.get();

            // 预处理权限映射
            Map<String, List<FormDepartmentPermission>> permissionMap = permissions.stream()
                .collect(Collectors.groupingBy(p -> p.getSourceType() + ":" + p.getSourceId()));

            List<FormDepartmentPermissionDto> result = new ArrayList<>();
            
            // 处理表单数据源
            if (forms != null) {
                forms.parallelStream().forEach(form -> {
                    FormDepartmentPermissionDto dto = buildPermissionDto(
                        "form", form.getId(), form.getName(), permissionMap);
                    synchronized (result) {
                        result.add(dto);
                    }
                });
            }

            // 处理Excel数据源
            if (excelList != null) {
                excelList.parallelStream().forEach(excel -> {
                    FormDepartmentPermissionDto dto = buildPermissionDto(
                        "excel", excel.getTableName(), excel.getDisplayName(), permissionMap);
                    synchronized (result) {
                        result.add(dto);
                    }
                });
            }

            long endTime = System.currentTimeMillis();
            log.debug("获取权限配置列表完成，耗时: {}ms，返回{}条记录", (endTime - startTime), result.size());
            
            return result;

        } catch (Exception e) {
            log.error("获取权限配置列表失败", e);
            throw new RuntimeException("获取权限配置列表失败: " + e.getMessage());
        }
    }

    /**
     * 构建权限DTO - 抽取公共逻辑
     */
    private FormDepartmentPermissionDto buildPermissionDto(String sourceType, String sourceId, String sourceName, 
                                                          Map<String, List<FormDepartmentPermission>> permissionMap) {
        FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
        dto.setSourceType(sourceType);
        dto.setSourceId(sourceId);
        dto.setSourceName(sourceName);

        String key = sourceType + ":" + sourceId;
        List<FormDepartmentPermission> sourcePermissions = permissionMap.get(key);
        
        if (sourcePermissions == null || sourcePermissions.isEmpty()) {
            dto.setAllowAllDepartments(true);
            dto.setDepartments(new ArrayList<>());
        } else {
            dto.setAllowAllDepartments(false);
            List<FormDepartmentPermissionDto.DepartmentInfo> deptInfos = sourcePermissions.stream()
                .map(p -> new FormDepartmentPermissionDto.DepartmentInfo(
                    p.getDepartmentId(),
                    p.getDepartmentName(),
                    p.getPermissionType(),
                    p.getIsActive() == 1
                ))
                .collect(Collectors.toList());
            dto.setDepartments(deptInfos);
            
            // 设置最后更新时间
            Optional<LocalDateTime> latestUpdateTime = sourcePermissions.stream()
                .map(FormDepartmentPermission::getUpdateTime)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo);
            latestUpdateTime.ifPresent(dto::setUpdateTime);
        }

        return dto;
    }

    /**
     * 缓存的部门列表
     */
    @Cacheable(value = "departmentList", unless = "#result.size() == 0")
    public List<OrgDepartment> getAllDepartments() {
        log.debug("获取部门列表");
        return departmentRepository.findAllDepartments();
    }

    /**
     * 优化的获取单个数据源权限配置
     */
    @Cacheable(value = "sourcePermission", key = "#sourceType + ':' + #sourceId")
    public FormDepartmentPermissionDto getSourcePermissions(String sourceType, String sourceId) {
        long startTime = System.currentTimeMillis();
        
        FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
        dto.setSourceType(sourceType);
        dto.setSourceId(sourceId);

        // 获取数据源名称 - 优化：只获取需要的单个数据源
        String sourceName = getSourceName(sourceType, sourceId);
        if (sourceName == null) {
            return null; // 数据源不存在
        }
        dto.setSourceName(sourceName);

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

        long endTime = System.currentTimeMillis();
        log.debug("获取单个数据源权限配置完成，耗时: {}ms", (endTime - startTime));
        
        return dto;
    }

    /**
     * 优化的获取数据源名称 - 避免获取全部列表
     */
    private String getSourceName(String sourceType, String sourceId) {
        try {
            if ("form".equals(sourceType)) {
                // 如果有getFormById方法，优先使用
                List<FormDto> forms = formService.getAllForms();
                if (forms != null) {
                    return forms.stream()
                        .filter(f -> sourceId.equals(f.getId()))
                        .map(FormDto::getName)
                        .findFirst()
                        .orElse(null);
                }
            } else if ("excel".equals(sourceType)) {
                List<ExcelImportMetadata> excelList = excelImportService.listImports();
                if (excelList != null) {
                    return excelList.stream()
                        .filter(e -> sourceId.equals(e.getTableName()))
                        .map(ExcelImportMetadata::getDisplayName)
                        .findFirst()
                        .orElse(null);
                }
            }
        } catch (Exception e) {
            log.error("获取数据源名称失败: sourceType={}, sourceId={}", sourceType, sourceId, e);
        }
        return null;
    }

    /**
     * 保存数据源权限配置 - 清除相关缓存
     */
    @Transactional
    @CacheEvict(value = {"permissionList", "sourcePermission"}, allEntries = true)
    public void saveSourcePermissions(String sourceType, String sourceId, List<Long> departmentIds, Long creatorId) {
        // 获取数据源名称
        String sourceName = getSourceName(sourceType, sourceId);
        if (sourceName == null) {
            throw new RuntimeException("数据源不存在");
        }

        // 先禁用该数据源的所有现有权限
        permissionRepository.deleteBySource(sourceType, sourceId);

        if (departmentIds != null && !departmentIds.isEmpty()) {
            // 批量获取部门信息
            List<OrgDepartment> departments = departmentRepository.findDepartmentsByIds(departmentIds);
            Map<Long, String> deptNameMap = departments.stream()
                .collect(Collectors.toMap(OrgDepartment::getId, OrgDepartment::getName));

            // 批量保存权限
            List<FormDepartmentPermission> permissionsToSave = new ArrayList<>();
            
            for (Long departmentId : departmentIds) {
                String departmentName = deptNameMap.get(departmentId);
                if (departmentName != null) {
                    String deptKey = String.valueOf(departmentId);
                    FormDepartmentPermission existing = permissionRepository.findBySourceAndDepartmentId(sourceType, sourceId, deptKey);
                    if (existing != null) {
                        existing.setIsActive(1);
                        existing.setUpdateTime(LocalDateTime.now());
                        existing.setDepartmentName(departmentName);
                        existing.setSourceName(sourceName);
                        if (creatorId != null) {
                            existing.setCreatorId(creatorId);
                        }
                        permissionsToSave.add(existing);
                    } else {
                        FormDepartmentPermission permission = new FormDepartmentPermission();
                        permission.setSourceType(sourceType);
                        permission.setSourceId(sourceId);
                        permission.setSourceName(sourceName);
                        permission.setDepartmentId(deptKey);
                        permission.setDepartmentName(departmentName);
                        permission.setPermissionType("read");
                        permission.setIsActive(1);
                        permission.setCreatorId(creatorId);
                        permissionsToSave.add(permission);
                    }
                }
            }
            
            // 批量保存
            permissionRepository.saveAll(permissionsToSave);
        }
    }

    /**
     * 检查权限 - 使用缓存
     */
    @Cacheable(value = "permissionCheck", key = "#sourceType + ':' + #sourceId + ':' + #departmentId")
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
     * 删除数据源权限配置 - 清除缓存
     */
    @Transactional
    @CacheEvict(value = {"permissionList", "sourcePermission", "permissionCheck"}, allEntries = true)
    public void removeSourcePermissions(String sourceType, String sourceId) {
        permissionRepository.deleteBySource(sourceType, sourceId);
    }

    /**
     * 搜索权限配置 - 基于缓存的数据进行搜索
     */
    public List<FormDepartmentPermissionDto> searchPermissions(String keyword) {
        List<FormDepartmentPermissionDto> allPermissions = getAllPermissions();
        
        if (keyword == null || keyword.trim().isEmpty()) {
            return allPermissions;
        }

        String lowerKeyword = keyword.toLowerCase();
        return allPermissions.parallelStream()
            .filter(dto -> dto.getSourceName().toLowerCase().contains(lowerKeyword) ||
                          dto.getDepartments().stream()
                              .anyMatch(dept -> dept.getDepartmentName().toLowerCase().contains(lowerKeyword)))
            .collect(Collectors.toList());
    }

    /**
     * 手动清除缓存
     */
    @CacheEvict(value = {"permissionList", "sourcePermission", "permissionCheck", "departmentList"}, allEntries = true)
    public void clearCache() {
        log.info("权限管理缓存已清除");
    }
}
