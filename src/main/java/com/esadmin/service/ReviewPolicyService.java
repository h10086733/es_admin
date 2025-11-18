package com.esadmin.service;

import com.esadmin.dto.ExcelImportMetadata;
import com.esadmin.dto.FormDto;
import com.esadmin.dto.ReviewPolicyDto;
import com.esadmin.entity.ReviewPolicy;
import com.esadmin.repository.ReviewPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ReviewPolicyService {
    
    private static final Logger log = LoggerFactory.getLogger(ReviewPolicyService.class);
    
    private final ReviewPolicyRepository reviewPolicyRepository;
    private final FormService formService;
    private final ExcelImportService excelImportService;
    
    public ReviewPolicyService(ReviewPolicyRepository reviewPolicyRepository,
                              FormService formService,
                              ExcelImportService excelImportService) {
        this.reviewPolicyRepository = reviewPolicyRepository;
        this.formService = formService;
        this.excelImportService = excelImportService;
    }
    
    /**
     * 获取所有审核策略，包括表单和Excel数据源
     */
    public List<ReviewPolicyDto> getAllReviewPolicies() {
        List<ReviewPolicyDto> result = new ArrayList<>();
        
        try {
            // 获取所有已配置的审核策略
            List<ReviewPolicy> policies = reviewPolicyRepository.findAll();
            Map<String, ReviewPolicy> policyMap = policies.stream()
                .collect(Collectors.toMap(
                    p -> p.getSourceType() + ":" + p.getSourceId(),
                    p -> p
                ));
            
            // 获取所有表单
            List<FormDto> forms = formService.getAllForms();
            if (forms != null) {
                for (FormDto form : forms) {
                    ReviewPolicyDto dto = new ReviewPolicyDto();
                    dto.setSourceType("form");
                    dto.setSourceId(form.getId());
                    dto.setSourceName(form.getName());
                    
                    String key = "form:" + form.getId();
                    ReviewPolicy policy = policyMap.get(key);
                    if (policy != null) {
                        dto.setId(policy.getId());
                        dto.setReviewMode(policy.getReviewMode());
                        dto.setCreatedAt(policy.getCreatedAt());
                        dto.setUpdatedAt(policy.getUpdatedAt());
                    } else {
                        dto.setReviewMode("view_first"); // 默认先看后审
                    }
                    
                    result.add(dto);
                }
            }
            
            // 获取所有Excel数据源
            List<ExcelImportMetadata> excelList = excelImportService.listImports();
            if (excelList != null) {
                for (ExcelImportMetadata excel : excelList) {
                    ReviewPolicyDto dto = new ReviewPolicyDto();
                    dto.setSourceType("excel");
                    dto.setSourceId(excel.getTableName());
                    dto.setSourceName(excel.getDisplayName());
                    
                    String key = "excel:" + excel.getTableName();
                    ReviewPolicy policy = policyMap.get(key);
                    if (policy != null) {
                        dto.setId(policy.getId());
                        dto.setReviewMode(policy.getReviewMode());
                        dto.setCreatedAt(policy.getCreatedAt());
                        dto.setUpdatedAt(policy.getUpdatedAt());
                    } else {
                        dto.setReviewMode("view_first"); // 默认先看后审
                    }
                    
                    result.add(dto);
                }
            }
            
        } catch (Exception e) {
            log.error("获取审核策略列表失败", e);
        }
        
        return result;
    }
    
    /**
     * 设置审核策略
     */
    @Transactional
    public void setReviewPolicy(String sourceType, String sourceId, String reviewMode) {
        try {
            // 使用merge来避免实体冲突
            ReviewPolicy policy = reviewPolicyRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .orElse(null);
            
            if (policy != null) {
                // 更新现有策略
                policy.setReviewMode(reviewMode);
            } else {
                // 创建新策略
                policy = new ReviewPolicy();
                policy.setSourceType(sourceType);
                policy.setSourceId(sourceId);
                policy.setReviewMode(reviewMode);
                
                // 设置数据源名称
                String sourceName = getSourceName(sourceType, sourceId);
                policy.setSourceName(sourceName);
            }
            
            reviewPolicyRepository.save(policy);
            log.info("设置审核策略成功: type={}, id={}, mode={}", sourceType, sourceId, reviewMode);
            
        } catch (Exception e) {
            log.error("设置审核策略失败: type={}, id={}, mode={}", sourceType, sourceId, reviewMode, e);
            throw new RuntimeException("设置审核策略失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定数据源的审核模式
     */
    public String getReviewMode(String sourceType, String sourceId) {
        try {
            Optional<ReviewPolicy> policy = reviewPolicyRepository.findBySourceTypeAndSourceId(sourceType, sourceId);
            return policy.map(ReviewPolicy::getReviewMode).orElse("view_first");
        } catch (Exception e) {
            log.warn("获取审核策略失败: type={}, id={}", sourceType, sourceId, e);
            return "view_first"; // 默认先看后审
        }
    }
    
    /**
     * 删除审核策略
     */
    @Transactional
    public void deleteReviewPolicy(String sourceType, String sourceId) {
        try {
            reviewPolicyRepository.deleteBySourceTypeAndSourceId(sourceType, sourceId);
            log.info("删除审核策略成功: type={}, id={}", sourceType, sourceId);
        } catch (Exception e) {
            log.error("删除审核策略失败: type={}, id={}", sourceType, sourceId, e);
            throw new RuntimeException("删除审核策略失败: " + e.getMessage());
        }
    }
    
    /**
     * 批量设置审核策略 - 简化版本，使用单个事务
     */
    @Transactional
    public void batchSetReviewPolicy(String sourceType, String reviewMode, List<String> sourceIds) {
        try {
            int successCount = 0;
            
            for (String sourceId : sourceIds) {
                try {
                    // 直接在当前事务中处理，避免嵌套事务问题
                    Optional<ReviewPolicy> existingOpt = reviewPolicyRepository.findBySourceTypeAndSourceId(sourceType, sourceId);
                    
                    ReviewPolicy policy;
                    if (existingOpt.isPresent()) {
                        // 更新现有策略
                        policy = existingOpt.get();
                        policy.setReviewMode(reviewMode);
                    } else {
                        // 创建新策略
                        policy = new ReviewPolicy();
                        policy.setSourceType(sourceType);
                        policy.setSourceId(sourceId);
                        policy.setReviewMode(reviewMode);
                        
                        // 设置数据源名称
                        try {
                            String sourceName = getSourceName(sourceType, sourceId);
                            policy.setSourceName(sourceName);
                        } catch (Exception e) {
                            policy.setSourceName("未知数据源");
                            log.warn("获取数据源名称失败，使用默认名称: type={}, id={}", sourceType, sourceId);
                        }
                    }
                    
                    reviewPolicyRepository.save(policy);
                    successCount++;
                    
                } catch (Exception e) {
                    log.warn("设置单个审核策略失败: type={}, id={}, mode={}, error={}", 
                        sourceType, sourceId, reviewMode, e.getMessage());
                    // 继续处理其他项
                }
            }
            
            log.info("批量设置审核策略完成: type={}, mode={}, 成功: {}/{}", 
                sourceType, reviewMode, successCount, sourceIds.size());
                
        } catch (Exception e) {
            log.error("批量设置审核策略失败: type={}, mode={}", sourceType, reviewMode, e);
            throw new RuntimeException("批量设置审核策略失败: " + e.getMessage());
        }
    }
    
    /**
     * 内部设置审核策略方法 - 简化版本，不使用复杂的事务处理
     */
    @Transactional
    public void setReviewPolicyInternal(String sourceType, String sourceId, String reviewMode) {
        try {
            // 查找现有策略
            Optional<ReviewPolicy> existingOpt = reviewPolicyRepository.findBySourceTypeAndSourceId(sourceType, sourceId);
            
            ReviewPolicy policy;
            if (existingOpt.isPresent()) {
                // 更新现有策略
                policy = existingOpt.get();
                policy.setReviewMode(reviewMode);
            } else {
                // 创建新策略
                policy = new ReviewPolicy();
                policy.setSourceType(sourceType);
                policy.setSourceId(sourceId);
                policy.setReviewMode(reviewMode);
                
                // 设置数据源名称
                String sourceName = getSourceName(sourceType, sourceId);
                policy.setSourceName(sourceName);
            }
            
            // 保存策略
            reviewPolicyRepository.save(policy);
            
            log.debug("设置审核策略成功: type={}, id={}, mode={}", sourceType, sourceId, reviewMode);
            
        } catch (Exception e) {
            log.error("内部设置审核策略失败: type={}, id={}, mode={}", sourceType, sourceId, reviewMode, e);
            throw new RuntimeException("设置审核策略失败: " + e.getMessage());
        }
    }
    
    private String getSourceName(String sourceType, String sourceId) {
        try {
            if ("form".equals(sourceType)) {
                FormDto form = formService.getFormById(sourceId);
                return form != null ? form.getName() : "未知表单";
            } else if ("excel".equals(sourceType)) {
                return excelImportService.getDisplayName(sourceId);
            }
        } catch (Exception e) {
            log.warn("获取数据源名称失败: type={}, id={}", sourceType, sourceId, e);
        }
        return "未知数据源";
    }
}