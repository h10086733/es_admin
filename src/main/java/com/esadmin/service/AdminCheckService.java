package com.esadmin.service;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Service
public class AdminCheckService {

    private static final Logger log = LoggerFactory.getLogger(AdminCheckService.class);

    private final RestTemplate restTemplate;
    private final String adminCheckBaseUrl;

    public AdminCheckService(RestTemplateBuilder restTemplateBuilder,
                            @Value("${app.admin.check.base-url:http://192.168.31.157/seeyon/rest/token/dataManage/ifAdmin}") String adminCheckBaseUrl,
                            @Value("${app.admin.check.connect-timeout-ms:3000}") long connectTimeoutMs,
                            @Value("${app.admin.check.read-timeout-ms:5000}") long readTimeoutMs) {
        this.adminCheckBaseUrl = adminCheckBaseUrl;
        Duration connectTimeout = Duration.ofMillis(Math.max(100, connectTimeoutMs));
        Duration readTimeout = Duration.ofMillis(Math.max(100, readTimeoutMs));
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    public boolean checkIfAdmin(String userId) {
        if (StringUtils.isBlank(userId)) {
            log.warn("用户ID为空，默认为非管理员");
            return false;
        }

        if (StringUtils.isBlank(adminCheckBaseUrl)) {
            log.warn("管理员检查URL未配置，默认为非管理员");
            return false;
        }

        try {
            String checkUrl = adminCheckBaseUrl.endsWith("/") 
                ? adminCheckBaseUrl + userId 
                : adminCheckBaseUrl + "/" + userId;
            
            log.debug("检查用户管理员权限: userId={}, url={}", userId, checkUrl);

            ResponseEntity<AdminCheckResponse> response = restTemplate.exchange(
                    checkUrl,
                    HttpMethod.GET,
                    null,
                    AdminCheckResponse.class
            );

            AdminCheckResponse body = response.getBody();
            if (body == null) {
                log.warn("管理员检查返回为空: status={}, userId={}", response.getStatusCode(), userId);
                return false;
            }

            boolean isAdmin = body.getData() != null && Boolean.TRUE.equals(body.getData().getIsAdmin());
            log.info("用户管理员权限检查结果: userId={}, isAdmin={}", userId, isAdmin);
            
            return isAdmin;

        } catch (RestClientException ex) {
            log.error("调用管理员检查接口失败: userId={}", userId, ex);
            return false;
        }
    }

    public AdminPermission checkUserPermission(String userId) {
        if (StringUtils.isBlank(userId)) {
            log.warn("用户ID为空，默认无权限");
            return new AdminPermission(false, false);
        }

        if (StringUtils.isBlank(adminCheckBaseUrl)) {
            log.warn("管理员检查URL未配置，默认无权限");
            return new AdminPermission(false, false);
        }

        try {
            String checkUrl = adminCheckBaseUrl.endsWith("/") 
                ? adminCheckBaseUrl + userId 
                : adminCheckBaseUrl + "/" + userId;
            
            log.debug("检查用户权限: userId={}, url={}", userId, checkUrl);

            ResponseEntity<AdminCheckResponse> response = restTemplate.exchange(
                    checkUrl,
                    HttpMethod.GET,
                    null,
                    AdminCheckResponse.class
            );

            AdminCheckResponse body = response.getBody();
            if (body == null || body.getData() == null) {
                log.warn("权限检查返回为空: status={}, userId={}", response.getStatusCode(), userId);
                return new AdminPermission(false, false);
            }

            boolean isAdmin = Boolean.TRUE.equals(body.getData().getIsAdmin());
            boolean isView = body.getData().getIsView() != null ? body.getData().getIsView() : true;
            
            log.info("用户权限检查结果: userId={}, isAdmin={}, isView={}", userId, isAdmin, isView);
            
            return new AdminPermission(isAdmin, isView);

        } catch (RestClientException ex) {
            log.error("调用权限检查接口失败: userId={}", userId, ex);
            return new AdminPermission(false, false);
        }
    }

    public static class AdminPermission {
        private final boolean isAdmin;
        private final boolean isView;

        public AdminPermission(boolean isAdmin, boolean isView) {
            this.isAdmin = isAdmin;
            this.isView = isView;
        }

        public boolean isAdmin() {
            return isAdmin;
        }

        public boolean isView() {
            return isView;
        }
    }

    private static class AdminCheckResponse {
        private int code;
        private String message;
        private AdminData data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public AdminData getData() {
            return data;
        }

        public void setData(AdminData data) {
            this.data = data;
        }
    }

    private static class AdminData {
        private Boolean isAdmin;
        private Boolean isView;

        public Boolean getIsAdmin() {
            return isAdmin;
        }

        public void setIsAdmin(Boolean isAdmin) {
            this.isAdmin = isAdmin;
        }

        public Boolean getIsView() {
            return isView;
        }

        public void setIsView(Boolean isView) {
            this.isView = isView;
        }
    }
}