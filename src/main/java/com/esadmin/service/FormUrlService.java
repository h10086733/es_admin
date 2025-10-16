package com.esadmin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class FormUrlService {
    
    private static final Logger log = LoggerFactory.getLogger(FormUrlService.class);
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Value("${app.seeyon.base-url:http://192.168.31.157/seeyon}")
    private String seeyonBaseUrl;
    
    /**
     * 生成表单跳转URL
     * @param formId 表单ID
     * @return 跳转URL，如果获取失败返回null
     */
    public String generateFormUrl(String formId) {
        try {
            // 1. 查询 cap_form_view_info 获取 view_id
            String viewId = getViewId(formId);
            if (viewId == null) {
                log.warn("无法获取表单{}的view_id", formId);
                return null;
            }
            
            // 2. 查询 CAP_FORM_AUTH_ID_MAPPING 获取 resource_id
            String resourceId = getResourceId(formId, viewId);
            if (resourceId == null) {
                log.warn("无法获取表单{}的resource_id", formId);
                return null;
            }
            
            // 3. 生成URL
            String url = String.format("%s/cap4/businessTemplateController.do?&method=formContent&type=browse&rightId=%s.%s&moduleId=%s&moduleType=42",
                    seeyonBaseUrl, viewId, resourceId, formId);
            
            log.debug("为表单{}生成URL: {}", formId, url);
            return url;
            
        } catch (Exception e) {
            log.error("生成表单{}的跳转URL失败", formId, e);
            return null;
        }
    }
    
    /**
     * 获取表单的view_id
     */
    private String getViewId(String formId) {
        try {
            String sql = "SELECT VIEW_ID FROM cap_form_view_info WHERE FORM_ID = ? AND VIEW_TYPE = 'seeyonform'";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, formId);
            
            if (!results.isEmpty()) {
                Object viewIdObj = results.get(0).get("VIEW_ID");
                return viewIdObj != null ? viewIdObj.toString() : null;
            }
            
        } catch (Exception e) {
            log.error("查询表单{}的view_id失败", formId, e);
        }
        
        return null;
    }
    
    /**
     * 获取表单的resource_id
     */
    private String getResourceId(String formId, String viewId) {
        try {
            String sql = "SELECT resource_id FROM CAP_FORM_AUTH_ID_MAPPING WHERE FORM_ID = ? AND view_id = ? LIMIT 1";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, formId, viewId);
            
            if (!results.isEmpty()) {
                Object resourceIdObj = results.get(0).get("resource_id");
                return resourceIdObj != null ? resourceIdObj.toString() : null;
            }
            
        } catch (Exception e) {
            log.error("查询表单{}的resource_id失败", formId, e);
        }
        
        return null;
    }
}