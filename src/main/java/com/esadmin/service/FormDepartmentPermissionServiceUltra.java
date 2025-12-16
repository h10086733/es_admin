package com.esadmin.service;

import com.esadmin.dto.DepartmentTreeNode;
import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.FormDepartmentPermissionDto;
import com.esadmin.dto.FormDto;
import com.esadmin.entity.FormDepartmentPermission;
import com.esadmin.entity.OrgDepartment;
import com.esadmin.repository.FormDepartmentPermissionRepository;
import com.esadmin.repository.OrgDepartmentRepository;
import com.esadmin.util.PerformanceMonitor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Primary
public class FormDepartmentPermissionServiceUltra {

    private static final Logger log = LoggerFactory.getLogger(FormDepartmentPermissionServiceUltra.class);
    @Autowired
    private FormDepartmentPermissionRepository permissionRepository;

    @Autowired
    private OrgDepartmentRepository departmentRepository;

    @Autowired
    private FormService formService;

    @Autowired
    private ExcelImportService excelImportService;
    
    @Autowired
    private PerformanceMonitor performanceMonitor;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 超级优化的权限列表获取 - 分层缓存 + 延迟加载
     */
    public List<FormDepartmentPermissionDto> getAllPermissions() {
        long start = System.currentTimeMillis();
        log.info("开始获取权限配置列表（精简版）");

        try {
            List<FormDto> forms = loadAllForms();
            List<ExcelImportMetadata> excelList = loadAllExcelImports();

            Map<String, FormDto> formMap = forms.stream()
                    .filter(Objects::nonNull)
                    .filter(form -> StringUtils.isNotBlank(form.getId()))
                    .collect(Collectors.toMap(FormDto::getId, form -> form, (a, b) -> a, LinkedHashMap::new));

            Map<String, ExcelImportMetadata> excelMap = excelList.stream()
                    .filter(Objects::nonNull)
                    .filter(excel -> StringUtils.isNotBlank(excel.getTableName()))
                    .collect(Collectors.toMap(excel -> excel.getTableName().toUpperCase(Locale.ROOT), excel -> excel,
                            (a, b) -> a, LinkedHashMap::new));

            LinkedHashMap<String, FormDepartmentPermissionDto> dtoMap = new LinkedHashMap<>();

            forms.forEach(form -> {
                if (form == null || StringUtils.isBlank(form.getId())) {
                    return;
                }
                String key = buildSourceKey("form", form.getId());
                dtoMap.put(key, createDefaultPermissionDto("form", form.getId(),
                        safeSourceName(form.getName(), form.getId())));
            });

            excelList.forEach(excel -> {
                if (excel == null || StringUtils.isBlank(excel.getTableName())) {
                    return;
                }
                String sourceId = excel.getTableName();
                String key = buildSourceKey("excel", sourceId);
                dtoMap.put(key, createDefaultPermissionDto("excel", sourceId,
                        safeSourceName(excel.getDisplayName(), sourceId)));
            });

            List<FormDepartmentPermission> permissions = permissionRepository.findAllActivePermissions();
            Map<String, List<FormDepartmentPermission>> permissionMap = permissions.stream()
                    .collect(Collectors.groupingBy(p -> buildSourceKey(p.getSourceType(), p.getSourceId())));

            permissionMap.forEach((key, sourcePermissions) -> {
                String[] parts = key.split(":", 2);
                if (parts.length != 2) {
                    return;
                }
                String sourceType = parts[0];
                String sourceId = parts[1];

                FormDepartmentPermissionDto dto = dtoMap.computeIfAbsent(key, k ->
                        createDefaultPermissionDto(sourceType, sourceId,
                                resolveSourceName(sourceType, sourceId, formMap, excelMap, sourcePermissions)));

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

                sourcePermissions.stream()
                        .map(FormDepartmentPermission::getUpdateTime)
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .ifPresent(dto::setUpdateTime);
            });

            List<FormDepartmentPermissionDto> result = new ArrayList<>(dtoMap.values());
            long totalTime = System.currentTimeMillis() - start;
            performanceMonitor.recordTime("getAllPermissions_total", totalTime);
            log.info("权限配置列表获取完成，总耗时: {}ms，返回{}条记录", totalTime, result.size());
            return result;

        } catch (Exception e) {
            performanceMonitor.recordTime("getAllPermissions_ERROR", System.currentTimeMillis() - start);
            log.error("获取权限配置列表失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 带缓存的表单获取
     */
    private List<FormDto> loadAllForms() {
        try {
            List<FormDto> forms = formService.getAllForms();
            if (forms != null) {
                return forms;
            }
        } catch (Exception e) {
            log.error("获取表单列表失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<ExcelImportMetadata> loadAllExcelImports() {
        try {
            List<ExcelImportMetadata> excelList = excelImportService.listImports();
            if (excelList != null) {
                return excelList;
            }
        } catch (Exception e) {
            log.error("获取Excel列表失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private Optional<FormDto> findFormById(String formId) {
        if (StringUtils.isBlank(formId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(formService.getFormById(formId));
        } catch (Exception e) {
            log.error("根据ID获取表单失败: {}", formId, e);
            return Optional.empty();
        }
    }

    private Optional<ExcelImportMetadata> findExcelByTableName(String tableName) {
        if (StringUtils.isBlank(tableName)) {
            return Optional.empty();
        }
        try {
            List<ExcelImportMetadata> excelList = excelImportService.listImports();
            if (excelList == null) {
                return Optional.empty();
            }
            return excelList.stream()
                    .filter(Objects::nonNull)
                    .filter(excel -> tableName.equalsIgnoreCase(excel.getTableName()))
                    .findFirst();
        } catch (Exception e) {
            log.error("根据表名获取Excel数据源失败: {}", tableName, e);
            return Optional.empty();
        }
    }

    private Optional<SourceMetadata> resolveSourceMetadata(String sourceType, String sourceId) {
        if (StringUtils.isAnyBlank(sourceType, sourceId)) {
            return Optional.empty();
        }
        if ("form".equalsIgnoreCase(sourceType)) {
            return findFormById(sourceId)
                    .map(form -> new SourceMetadata("form", form.getId(),
                            safeSourceName(form.getName(), form.getId())));
        } else if ("excel".equalsIgnoreCase(sourceType)) {
            return findExcelByTableName(sourceId)
                    .map(excel -> new SourceMetadata("excel", excel.getTableName(),
                            safeSourceName(excel.getDisplayName(), excel.getTableName())));
        }
        return Optional.empty();
    }

    private SourceMetadata resolveSourceMetadataOrDefault(String sourceType, String sourceId) {
        return resolveSourceMetadata(sourceType, sourceId)
                .orElse(new SourceMetadata(sourceType, sourceId, safeSourceName(null, sourceId)));
    }

    private Map<String, String> fetchDepartmentNames(List<String> departmentIds) {
        Map<String, String> result = new HashMap<>();
        if (departmentIds == null || departmentIds.isEmpty()) {
            return result;
        }
        List<Long> deptLongs = departmentIds.stream()
                .map(id -> {
                    try {
                        return new BigInteger(id).longValue();
                    } catch (Exception e) {
                        log.warn("[Permission] 部门ID无法解析为Long: {}", id);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (!deptLongs.isEmpty()) {
            List<OrgDepartment> departments = departmentRepository.findDepartmentsByIds(deptLongs);
            departments.forEach(dept -> result.put(String.valueOf(dept.getId()), dept.getName()));
        }
        return result;
    }

    private String buildSourceKey(String sourceType, String sourceId) {
        return (sourceType != null ? sourceType : "") + ":" + (sourceId != null ? sourceId : "");
    }

    private String safeSourceName(String candidate, String sourceId) {
        if (StringUtils.isNotBlank(candidate)) {
            return candidate;
        }
        return "数据源(" + (sourceId != null ? sourceId : "未知") + ")";
    }

    private String resolveSourceName(String sourceType,
                                     String sourceId,
                                     Map<String, FormDto> formMap,
                                     Map<String, ExcelImportMetadata> excelMap,
                                     List<FormDepartmentPermission> sourcePermissions) {
        if ("form".equalsIgnoreCase(sourceType)) {
            FormDto form = formMap.get(sourceId);
            if (form != null && StringUtils.isNotBlank(form.getName())) {
                return form.getName();
            }
        } else if ("excel".equalsIgnoreCase(sourceType)) {
            ExcelImportMetadata excel = excelMap.get(sourceId != null ? sourceId.toUpperCase(Locale.ROOT) : null);
            if (excel != null && StringUtils.isNotBlank(excel.getDisplayName())) {
                return excel.getDisplayName();
            }
        }

        if (sourcePermissions != null) {
            Optional<String> fromPermission = sourcePermissions.stream()
                    .map(FormDepartmentPermission::getSourceName)
                    .filter(StringUtils::isNotBlank)
                    .findFirst();
            if (fromPermission.isPresent()) {
                return fromPermission.get();
            }
        }

        return safeSourceName(null, sourceId);
    }

    /**
     * 创建默认权限DTO（所有部门可访问）
     */
    private FormDepartmentPermissionDto createDefaultPermissionDto(String sourceType, String sourceId, String sourceName) {
        FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
        dto.setSourceType(sourceType);
        dto.setSourceId(sourceId);
        dto.setSourceName(sourceName);
        dto.setAllowAllDepartments(true);
        dto.setDepartments(new ArrayList<>());
        return dto;
    }

    /**
     * 快速构建权限DTO
     */
    private FormDepartmentPermissionDto buildPermissionDtoFast(String sourceType, String sourceId, String sourceName, 
                                                              List<FormDepartmentPermission> sourcePermissions) {
        FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
        dto.setSourceType(sourceType);
        dto.setSourceId(sourceId);
        dto.setSourceName(sourceName);
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
        
        // 快速获取最新更新时间
        Optional<LocalDateTime> latestUpdateTime = sourcePermissions.stream()
            .map(FormDepartmentPermission::getUpdateTime)
            .filter(Objects::nonNull)
            .max(LocalDateTime::compareTo);
        latestUpdateTime.ifPresent(dto::setUpdateTime);

        return dto;
    }

    /**
     * 清除内存缓存
     */
    public void clearAllCaches() {
        log.info("已清除权限相关缓存");
    }

    /**
     * 其他方法保持不变，但使用新的缓存名称
     */
    public List<OrgDepartment> getAllDepartments() {
        return departmentRepository.findAllDepartments();
    }

    public List<DepartmentTreeNode> getDepartmentTree() {
        List<OrgDepartment> units = departmentRepository.findAllUnits();
        log.info("加载组织/部门记录数量: {}", units.size());
        Map<String, DepartmentTreeNode> nodeMap = new LinkedHashMap<>();
        Map<String, OrgDepartment> unitMap = units.stream()
                .collect(Collectors.toMap(u -> String.valueOf(u.getId()), u -> u, (a, b) -> a, LinkedHashMap::new));
        Map<String, OrgDepartment> pathMap = units.stream()
                .filter(u -> StringUtils.isNotBlank(u.getPath()))
                .collect(Collectors.toMap(OrgDepartment::getPath, u -> u, (a, b) -> a, LinkedHashMap::new));

        units.forEach(unit -> nodeMap.put(String.valueOf(unit.getId()),
                new DepartmentTreeNode(String.valueOf(unit.getId()), unit.getName(), unit.getType())));

        List<DepartmentTreeNode> roots = new ArrayList<>();
        for (OrgDepartment unit : units) {
            String nodeId = String.valueOf(unit.getId());
            DepartmentTreeNode node = nodeMap.get(nodeId);
            if (node == null) {
                continue;
            }

            String parentId = resolveParentId(unit, unitMap, pathMap);
            if (parentId != null && nodeMap.containsKey(parentId) && !parentId.equals(nodeId)) {
                nodeMap.get(parentId).addChild(node);
            } else {
                roots.add(node);
            }
        }

        log.info("部门树根节点数: {}", roots.size());
        return roots;
    }

    private String resolveParentId(OrgDepartment unit, Map<String, OrgDepartment> unitMap, Map<String, OrgDepartment> pathMap) {
        String path = unit.getPath();
        if (StringUtils.isNotBlank(path) && path.length() > 4) {
            String parentPath = path.substring(0, path.length() - 4);
            OrgDepartment parentByPath = pathMap.get(parentPath);
            if (parentByPath != null) {
                return String.valueOf(parentByPath.getId());
            }
        }
        Long parentId = unit.getOrgAccountId();
        if (parentId != null && !parentId.equals(unit.getId())) {
            return String.valueOf(parentId);
        }
        return null;
    }

    public FormDepartmentPermissionDto getSourcePermissions(String sourceType, String sourceId) {
        FormDepartmentPermissionDto dto = new FormDepartmentPermissionDto();
        dto.setSourceType(sourceType);
        dto.setSourceId(sourceId);

        String sourceName = getSourceNameFast(sourceType, sourceId);
        if (sourceName == null) {
            return null;
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
                    p.getDepartmentId(),
                    p.getDepartmentName(),
                    p.getPermissionType(),
                    p.getIsActive() == 1
                ))
                .collect(Collectors.toList());
            dto.setDepartments(deptInfos);
        }

        return dto;
    }

    private String getSourceNameFast(String sourceType, String sourceId) {
        try {
            return resolveSourceMetadata(sourceType, sourceId)
                    .map(SourceMetadata::getSourceName)
                    .orElse(null);
        } catch (Exception e) {
            log.error("快速获取数据源名称失败: sourceType={}, sourceId={}", sourceType, sourceId, e);
            return null;
        }
    }

    @Transactional
    public void saveSourcePermissions(String sourceType, String sourceId, List<String> departmentIds, Long creatorId) {
        log.info("[Permission] 开始保存权限: sourceType={}, sourceId={}, departments={}, creatorId={}",
                sourceType, sourceId, departmentIds, creatorId);
        SourceMetadata sourceMetadata = resolveSourceMetadataOrDefault(sourceType, sourceId);
        String normalizedSourceId = sourceMetadata.getSourceId();
        String sourceName = sourceMetadata.getSourceName();


        permissionRepository.deleteBySource(sourceType, normalizedSourceId);
        entityManager.flush();
        entityManager.clear();

        if (departmentIds != null && !departmentIds.isEmpty()) {
            Map<String, String> deptNameMap = fetchDepartmentNames(departmentIds);

            int savedCount = 0;
            for (String departmentId : departmentIds) {
                String departmentName = deptNameMap.getOrDefault(departmentId, "部门(" + departmentId + ")");

                FormDepartmentPermission permission = new FormDepartmentPermission();
                permission.setSourceType(sourceType);
                permission.setSourceId(normalizedSourceId);
                permission.setSourceName(sourceName);
                permission.setDepartmentId(departmentId);
                permission.setDepartmentName(departmentName);
                permission.setPermissionType("read");
                permission.setIsActive(1);
                if (creatorId != null) {
                    permission.setCreatorId(creatorId);
                }
                permissionRepository.saveAndFlush(permission);
                entityManager.detach(permission);
                savedCount++;
            }
            log.info("[Permission] 保存完成: sourceType={}, sourceId={}, 写入{}条记录", sourceType, normalizedSourceId, savedCount);
        } else {
            log.info("[Permission] 无部门限制，sourceType={}, sourceId={} 默认开放", sourceType, normalizedSourceId);
        }
    }

    public boolean hasPermission(String sourceType, String sourceId, Long departmentId) {
        if (sourceType == null || sourceId == null || departmentId == null) {
            return false;
        }

        String deptKey = String.valueOf(departmentId);
        boolean hasAnyPermission = permissionRepository.hasAnyPermissionForSource(sourceType, sourceId);
        if (!hasAnyPermission) {
            return true;
        }

        return permissionRepository.hasPermission(sourceType, sourceId, deptKey);
    }

    @Transactional
    public void removeSourcePermissions(String sourceType, String sourceId) {
        permissionRepository.deleteBySource(sourceType, sourceId);
    }

    public PermissionMatrix buildPermissionMatrix(String departmentId) {
        Set<String> restrictedKeys = new HashSet<>();
        try {
            List<String> restrictedList = permissionRepository.findAllRestrictedSourceKeys();
            if (restrictedList != null) {
                restrictedList.stream()
                        .filter(StringUtils::isNotBlank)
                        .forEach(key -> restrictedKeys.add(key.trim()));
            }
        } catch (Exception e) {
            log.warn("加载受限数据源列表失败", e);
        }

        Set<String> allowedKeys = new HashSet<>();
        if (departmentId != null) {
            try {
                List<String> allowedList = permissionRepository.findActiveSourceKeysByDepartment(departmentId);
                if (allowedList != null) {
                    allowedList.stream()
                            .filter(StringUtils::isNotBlank)
                            .forEach(key -> allowedKeys.add(key.trim()));
                }
            } catch (Exception e) {
                log.warn("加载部门{}的数据源权限失败", departmentId, e);
            }
        }

        return new PermissionMatrix(allowedKeys, restrictedKeys);
    }

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
}

class PermissionMatrix {
    private final Set<String> allowedSourceKeys;
    private final Set<String> restrictedSourceKeys;

    PermissionMatrix(Set<String> allowedSourceKeys, Set<String> restrictedSourceKeys) {
        this.allowedSourceKeys = allowedSourceKeys != null ? allowedSourceKeys : Collections.emptySet();
        this.restrictedSourceKeys = restrictedSourceKeys != null ? restrictedSourceKeys : Collections.emptySet();
    }

    boolean isRestricted(String key) {
        return key != null && restrictedSourceKeys.contains(key);
    }

    boolean isAllowed(String key) {
        return key != null && allowedSourceKeys.contains(key);
    }
}

class SourceMetadata {
    private final String sourceType;
    private final String sourceId;
    private final String sourceName;

    SourceMetadata(String sourceType, String sourceId, String sourceName) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceName = sourceName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceName() {
        return sourceName;
    }
}
