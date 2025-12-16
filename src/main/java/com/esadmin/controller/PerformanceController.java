package com.esadmin.controller;

import com.esadmin.service.FormDepartmentPermissionServiceUltra;
import com.esadmin.service.PerformanceDiagnosticService;
import com.esadmin.util.PerformanceMonitor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    @Autowired
    private FormDepartmentPermissionServiceUltra permissionService;
    
    @Autowired
    private PerformanceMonitor performanceMonitor;
    
    @Autowired
    private PerformanceDiagnosticService diagnosticService;

    /**
     * 清除权限管理缓存
     */
    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearCache() {
        try {
            permissionService.clearAllCaches();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "缓存清除成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "缓存清除失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 获取性能统计
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        try {
            performanceMonitor.printStatistics();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "性能统计已输出到日志");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "获取统计失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 清除性能统计
     */
    @PostMapping("/clear-statistics")
    public ResponseEntity<Map<String, Object>> clearStatistics() {
        try {
            performanceMonitor.clearStatistics();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "性能统计清除成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "清除统计失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 诊断权限管理性能问题
     */
    @GetMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnosePerformance() {
        try {
            Map<String, Object> diagnosticReport = diagnosticService.diagnosePerformance();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", diagnosticReport);
            response.put("message", "性能诊断完成");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "性能诊断失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 测试查询性能
     */
    @GetMapping("/test-queries")
    public ResponseEntity<Map<String, Object>> testQueryPerformance() {
        try {
            Map<String, Object> testResult = diagnosticService.testQueryPerformance();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", testResult);
            response.put("message", "查询性能测试完成");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "查询性能测试失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 获取系统资源信息
     */
    @GetMapping("/system-info")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        try {
            Map<String, Object> systemInfo = diagnosticService.getSystemInfo();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", systemInfo);
            response.put("message", "系统信息获取成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "系统信息获取失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}