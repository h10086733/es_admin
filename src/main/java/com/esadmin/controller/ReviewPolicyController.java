package com.esadmin.controller;

import com.esadmin.dto.ReviewPolicyDto;
import com.esadmin.service.ReviewPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/review-policy")
public class ReviewPolicyController {
    
    private static final Logger log = LoggerFactory.getLogger(ReviewPolicyController.class);
    
    private final ReviewPolicyService reviewPolicyService;
    
    public ReviewPolicyController(ReviewPolicyService reviewPolicyService) {
        this.reviewPolicyService = reviewPolicyService;
    }
    
    /**
     * 获取所有审核策略
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> getAllReviewPolicies() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            List<ReviewPolicyDto> policies = reviewPolicyService.getAllReviewPolicies();
            
            response.put("success", true);
            response.put("data", policies);
            response.put("message", "获取审核策略列表成功");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("获取审核策略列表失败", e);
            response.put("success", false);
            response.put("message", "获取审核策略列表失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 设置审核策略
     */
    @PostMapping("/set")
    public ResponseEntity<Map<String, Object>> setReviewPolicy(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String sourceType = request.get("sourceType");
            String sourceId = request.get("sourceId");
            String reviewMode = request.get("reviewMode");
            
            if (sourceType == null || sourceId == null || reviewMode == null) {
                response.put("success", false);
                response.put("message", "参数不完整");
                return ResponseEntity.badRequest().body(response);
            }
            
            // 验证reviewMode
            if (!"view_first".equals(reviewMode) && !"review_first".equals(reviewMode)) {
                response.put("success", false);
                response.put("message", "无效的审核模式");
                return ResponseEntity.badRequest().body(response);
            }
            
            reviewPolicyService.setReviewPolicy(sourceType, sourceId, reviewMode);
            
            response.put("success", true);
            response.put("message", "设置审核策略成功");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("设置审核策略失败", e);
            response.put("success", false);
            response.put("message", "设置审核策略失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 批量设置审核策略
     */
    @PostMapping("/batch-set")
    public ResponseEntity<Map<String, Object>> batchSetReviewPolicy(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String sourceType = (String) request.get("sourceType");
            String reviewMode = (String) request.get("reviewMode");
            @SuppressWarnings("unchecked")
            List<String> sourceIds = (List<String>) request.get("sourceIds");
            
            if (sourceType == null || reviewMode == null || sourceIds == null || sourceIds.isEmpty()) {
                response.put("success", false);
                response.put("message", "参数不完整");
                return ResponseEntity.badRequest().body(response);
            }
            
            // 验证reviewMode
            if (!"view_first".equals(reviewMode) && !"review_first".equals(reviewMode)) {
                response.put("success", false);
                response.put("message", "无效的审核模式");
                return ResponseEntity.badRequest().body(response);
            }
            
            reviewPolicyService.batchSetReviewPolicy(sourceType, reviewMode, sourceIds);
            
            response.put("success", true);
            response.put("message", "批量设置审核策略成功，共处理 " + sourceIds.size() + " 个数据源");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("批量设置审核策略失败", e);
            response.put("success", false);
            response.put("message", "批量设置审核策略失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 删除审核策略（重置为默认）
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, Object>> deleteReviewPolicy(@RequestParam String sourceType, 
                                                                 @RequestParam String sourceId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            reviewPolicyService.deleteReviewPolicy(sourceType, sourceId);
            
            response.put("success", true);
            response.put("message", "删除审核策略成功，已重置为默认设置");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("删除审核策略失败", e);
            response.put("success", false);
            response.put("message", "删除审核策略失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 获取指定数据源的审核模式
     */
    @GetMapping("/get")
    public ResponseEntity<Map<String, Object>> getReviewMode(@RequestParam String sourceType,
                                                           @RequestParam String sourceId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String reviewMode = reviewPolicyService.getReviewMode(sourceType, sourceId);
            
            response.put("success", true);
            response.put("reviewMode", reviewMode);
            response.put("message", "获取审核策略成功");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("获取审核策略失败", e);
            response.put("success", false);
            response.put("message", "获取审核策略失败: " + e.getMessage());
            
            return ResponseEntity.status(500).body(response);
        }
    }
}