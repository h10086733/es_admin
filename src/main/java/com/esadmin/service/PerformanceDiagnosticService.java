package com.esadmin.service;

import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.FormDto;
import com.esadmin.entity.FormDepartmentPermission;
import com.esadmin.entity.OrgDepartment;
import com.esadmin.repository.FormDepartmentPermissionRepository;
import com.esadmin.repository.OrgDepartmentRepository;
import com.esadmin.util.PerformanceProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class PerformanceDiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceDiagnosticService.class);

    @Autowired
    private FormDepartmentPermissionRepository permissionRepository;

    @Autowired
    private OrgDepartmentRepository departmentRepository;

    @Autowired
    private FormService formService;

    @Autowired
    private ExcelImportService excelImportService;

    @Autowired
    private PerformanceProfiler profiler;

    /**
     * 诊断性能问题
     */
    public Map<String, Object> diagnosePerformance() {
        log.info("开始性能诊断...");
        Map<String, Object> report = new HashMap<>();
        
        try {
            // 测试数据库查询
            long permissionTime = profiler.time("查询权限数据", () -> {
                List<FormDepartmentPermission> permissions = permissionRepository.findAllActivePermissions();
                report.put("权限数据条数", permissions.size());
                return permissions.size();
            });
            
            long departmentTime = profiler.time("查询部门数据", () -> {
                List<OrgDepartment> departments = departmentRepository.findAllDepartments();
                report.put("部门数据条数", departments.size());
                return departments.size();
            });
            
            // 测试外部服务调用 - 这可能是瓶颈
            long formTime = profiler.time("调用FormService", () -> {
                List<FormDto> forms = formService.getAllForms();
                int count = forms != null ? forms.size() : 0;
                report.put("表单数据条数", count);
                return count;
            });
            
            long excelTime = profiler.time("调用ExcelImportService", () -> {
                List<ExcelImportMetadata> excelList = excelImportService.listImports();
                int count = excelList != null ? excelList.size() : 0;
                report.put("Excel数据条数", count);
                return count;
            });
            
            // 分析结果
            report.put("权限查询耗时ms", permissionTime);
            report.put("部门查询耗时ms", departmentTime);
            report.put("表单服务耗时ms", formTime);
            report.put("Excel服务耗时ms", excelTime);
            
            long totalTime = permissionTime + departmentTime + formTime + excelTime;
            report.put("总耗时ms", totalTime);
            
            // 分析瓶颈
            String bottleneck = "未知";
            long maxTime = Math.max(Math.max(permissionTime, departmentTime), Math.max(formTime, excelTime));
            
            if (maxTime == formTime && formTime > 2000) {
                bottleneck = "FormService.getAllForms()调用过慢";
            } else if (maxTime == excelTime && excelTime > 2000) {
                bottleneck = "ExcelImportService.listImports()调用过慢";
            } else if (maxTime == permissionTime && permissionTime > 1000) {
                bottleneck = "权限数据查询过慢";
            } else if (maxTime == departmentTime && departmentTime > 1000) {
                bottleneck = "部门数据查询过慢";
            }
            
            report.put("性能瓶颈", bottleneck);
            
            // 提供优化建议
            StringBuilder suggestions = new StringBuilder();
            if (formTime > 2000) {
                suggestions.append("1. FormService查询过慢，建议检查表单数据源性能\n");
            }
            if (excelTime > 2000) {
                suggestions.append("2. ExcelImportService查询过慢，建议检查Excel导入数据源性能\n");
            }
            if (permissionTime > 500) {
                suggestions.append("3. 权限查询较慢，建议检查form_department_permission表索引\n");
            }
            if (departmentTime > 500) {
                suggestions.append("4. 部门查询较慢，建议检查ORG_UNIT表索引\n");
            }
            if (suggestions.length() == 0) {
                suggestions.append("各项查询时间正常，可能是并发或数据处理逻辑导致的性能问题");
            }
            
            report.put("优化建议", suggestions.toString());
            
            log.info("性能诊断完成: 总耗时{}ms, 瓶颈: {}", totalTime, bottleneck);
            
        } catch (Exception e) {
            log.error("性能诊断失败", e);
            report.put("错误", e.getMessage());
        } finally {
            profiler.cleanup();
        }
        
        return report;
    }

    /**
     * 测试各种查询方法的性能
     */
    public Map<String, Object> testQueryPerformance() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 测试不同的权限查询方法
            long method1Time = profiler.time("findAllActivePermissions", () -> {
                return permissionRepository.findAllActivePermissions().size();
            });
            
            // 测试是否有优化的查询方法可用
            try {
                long method2Time = profiler.time("findAllActivePermissionsOptimized", () -> {
                    return permissionRepository.findAllActivePermissionsOptimized().size();
                });
                result.put("优化查询耗时ms", method2Time);
            } catch (Exception e) {
                result.put("优化查询", "不可用: " + e.getMessage());
            }
            
            result.put("标准查询耗时ms", method1Time);
            
        } catch (Exception e) {
            log.error("查询性能测试失败", e);
            result.put("错误", e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取系统资源使用情况
     */
    public Map<String, Object> getSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        info.put("总内存MB", totalMemory / (1024 * 1024));
        info.put("已用内存MB", usedMemory / (1024 * 1024));
        info.put("空闲内存MB", freeMemory / (1024 * 1024));
        info.put("最大内存MB", maxMemory / (1024 * 1024));
        info.put("内存使用率", String.format("%.1f%%", (double) usedMemory / totalMemory * 100));
        
        info.put("处理器核心数", runtime.availableProcessors());
        
        return info;
    }
}