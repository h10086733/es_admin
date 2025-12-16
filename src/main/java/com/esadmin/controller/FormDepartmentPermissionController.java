package com.esadmin.controller;

import com.esadmin.dto.FormDepartmentPermissionDto;
import com.esadmin.entity.OrgDepartment;
import com.esadmin.service.FormDepartmentPermissionServiceUltra;
import com.esadmin.util.PerformanceMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/form-department-permission")
public class FormDepartmentPermissionController {

    private static final Logger log = LoggerFactory.getLogger(FormDepartmentPermissionController.class);

    @Autowired
    private FormDepartmentPermissionServiceUltra permissionService;
    
    @Autowired
    private PerformanceMonitor performanceMonitor;

    /**
     * 获取所有数据源的权限配置（包括表单和Excel）
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getAllPermissions() {
        long startTime = System.currentTimeMillis();
        try {
            List<FormDepartmentPermissionDto> permissions = permissionService.getAllPermissions();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", permissions);
            response.put("message", "获取成功");
            
            long endTime = System.currentTimeMillis();
            performanceMonitor.recordTime("getAllPermissions", endTime - startTime);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            performanceMonitor.recordTime("getAllPermissions_ERROR", endTime - startTime);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 获取所有部门列表
     */
    @GetMapping("/departments")
    public ResponseEntity<Map<String, Object>> getAllDepartments() {
        try {
            List<OrgDepartment> departments = permissionService.getAllDepartments();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", departments);
            response.put("message", "获取成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/departments/tree")
    public ResponseEntity<Map<String, Object>> getDepartmentTree() {
        try {
            List<com.esadmin.dto.DepartmentTreeNode> tree = permissionService.getDepartmentTree();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", tree);
            response.put("message", "获取成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 根据数据源获取权限配置
     */
    @GetMapping("/source/{sourceType}/{sourceId}")
    public ResponseEntity<Map<String, Object>> getSourcePermissions(@PathVariable String sourceType, @PathVariable String sourceId) {
        try {
            FormDepartmentPermissionDto permissions = permissionService.getSourcePermissions(sourceType, sourceId);
            Map<String, Object> response = new HashMap<>();
            if (permissions != null) {
                response.put("success", true);
                response.put("data", permissions);
                response.put("message", "获取成功");
            } else {
                response.put("success", false);
                response.put("message", "数据源不存在");
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 保存数据源权限配置
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveSourcePermissions(@RequestBody Map<String, Object> request) {
        try {
            String sourceType = request.get("sourceType").toString();
            String sourceId = request.get("sourceId").toString();
            @SuppressWarnings("unchecked")
            List<Object> departmentIdObjs = (List<Object>) request.get("departmentIds");
            List<String> departmentIds = new ArrayList<>();
            if (departmentIdObjs != null) {
                for (Object obj : departmentIdObjs) {
                    if (obj == null) {
                        continue;
                    }
                    departmentIds.add(obj.toString());
                }
            }
            Long creatorId = request.get("creatorId") != null ? Long.valueOf(request.get("creatorId").toString()) : null;

            log.info("请求保存数据源权限: sourceType={}, sourceId={}, departments={}, creatorId={}",
                    sourceType, sourceId, departmentIds, creatorId);
            permissionService.saveSourcePermissions(sourceType, sourceId, departmentIds, creatorId);
            log.info("保存数据源权限成功: sourceType={}, sourceId={}, departments={}",
                    sourceType, sourceId, departmentIds);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "保存成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("保存数据源权限失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 检查权限
     */
    @GetMapping("/check/{sourceType}/{sourceId}/{departmentId}")
    public ResponseEntity<Map<String, Object>> checkPermission(
            @PathVariable String sourceType,
            @PathVariable String sourceId,
            @PathVariable Long departmentId) {
        try {
            boolean hasPermission = permissionService.hasPermission(sourceType, sourceId, departmentId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", hasPermission);
            response.put("message", "检查完成");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "检查失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 删除数据源权限配置（设为所有部门可访问）
     */
    @DeleteMapping("/source/{sourceType}/{sourceId}")
    public ResponseEntity<Map<String, Object>> removeSourcePermissions(
            @PathVariable String sourceType, 
            @PathVariable String sourceId) {
        try {
            permissionService.removeSourcePermissions(sourceType, sourceId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "删除成功，数据源现在对所有部门开放");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 搜索权限配置
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchPermissions(@RequestParam(required = false) String keyword) {
        try {
            List<FormDepartmentPermissionDto> permissions = permissionService.searchPermissions(keyword);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", permissions);
            response.put("message", "搜索成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "搜索失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}
