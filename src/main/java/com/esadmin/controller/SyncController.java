package com.esadmin.controller;

import com.esadmin.dto.FormDto;
import com.esadmin.dto.SyncRequest;
import com.esadmin.dto.SyncResult;
import com.esadmin.service.AsyncSyncService;
import com.esadmin.service.FormService;
import com.esadmin.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
@Validated
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);
    
    private final SyncService syncService;
    private final FormService formService;
    
    @Autowired
    private AsyncSyncService asyncSyncService;
    
    public SyncController(SyncService syncService, FormService formService) {
        this.syncService = syncService;
        this.formService = formService;
    }

    @GetMapping("/forms")
    public ResponseEntity<Map<String, Object>> getForms(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {
        try {
            Pageable pageable = PageRequest.of(page - 1, pageSize);
            Page<FormDto> forms = formService.getForms(pageable, search);
            
            Map<String, Object> pagination = new HashMap<>();
            pagination.put("page", page);
            pagination.put("total_pages", forms.getTotalPages());
            pagination.put("total", forms.getTotalElements());
            pagination.put("has_prev", forms.hasPrevious());
            pagination.put("has_next", forms.hasNext());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", forms.getContent());
            response.put("pagination", pagination);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("获取表单列表失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/sync/{formId}")
    public ResponseEntity<Map<String, Object>> syncForm(
            @PathVariable String formId,
            @Valid @RequestBody SyncRequest request) {
        try {
            log.info("同步表单: formId={}, fullSync={}", formId, request.getFullSync());
            
            SyncResult result = syncService.syncFormData(formId, request.getFullSync());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("message", result.getMessage());
            if (result.isSuccess()) {
                response.put("count", result.getCount());
                response.put("elapsed_time", result.getElapsedTime());
                response.put("rate", result.getRate());
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("同步表单失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/sync/all")
    public ResponseEntity<Map<String, Object>> syncAllForms(@Valid @RequestBody SyncRequest request) {
        try {
            log.info("同步所有表单: fullSync={}", request.getFullSync());
            
            List<FormDto> forms = formService.getAllForms();
            List<SyncResult> results = new ArrayList<>();
            
            for (FormDto form : forms) {
                SyncResult result = syncService.syncFormData(form.getId(), request.getFullSync());
                result.setFormName(form.getName());
                result.setFormId(form.getId());
                result.setType("form_sync");
                results.add(result);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("results", results);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("同步所有表单失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/async/sync/all")
    public ResponseEntity<Map<String, Object>> asyncSyncAllForms(@Valid @RequestBody SyncRequest request) {
        try {
            log.info("启动异步同步所有表单: fullSync={}", request.getFullSync());
            
            // 启动异步同步任务
            String taskId = asyncSyncService.startAsyncSyncAll(request.getFullSync());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("task_id", taskId);
            response.put("message", "异步同步任务已启动");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("启动异步同步失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/async/status/{taskId}")
    public ResponseEntity<Map<String, Object>> getAsyncSyncStatus(@PathVariable String taskId) {
        try {
            AsyncSyncService.SyncProgress progress = asyncSyncService.getSyncProgress(taskId);
            
            Map<String, Object> response = new HashMap<>();
            if (progress != null) {
                Map<String, Object> status = new HashMap<>();
                status.put("status", progress.getStatus());
                status.put("progress", Math.round(progress.getProgress() * 10) / 10.0);
                status.put("message", progress.getMessage());
                status.put("current_form", progress.getCurrentFormName());
                status.put("current_index", progress.getCurrentIndex());
                status.put("total_forms", progress.getTotalForms());
                status.put("success_count", progress.getSuccessCount());
                status.put("failure_count", progress.getFailureCount());
                status.put("elapsed_time", Math.round(progress.getElapsedTime() * 10) / 10.0);
                
                response.put("success", true);
                response.put("data", status);
            } else {
                response.put("success", false);
                response.put("message", "任务不存在或已过期");
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("获取异步同步状态失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping(value = "/async/progress/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAsyncSyncProgress(@PathVariable String taskId) {
        try {
            log.info("建立SSE连接: taskId={}", taskId);
            return asyncSyncService.registerSseConnection(taskId);
        } catch (Exception e) {
            log.error("建立SSE连接失败: taskId={}", taskId, e);
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("连接失败: " + e.getMessage()));
                emitter.complete();
            } catch (Exception ex) {
                // 忽略发送错误
            }
            return emitter;
        }
    }
}