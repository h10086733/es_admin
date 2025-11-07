package com.esadmin.service;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
public class KeyReviewService {

    private static final Logger log = LoggerFactory.getLogger(KeyReviewService.class);

    private final RestTemplate restTemplate;
    private final boolean enabled;
    private final String reviewUrl;

    public KeyReviewService(RestTemplateBuilder restTemplateBuilder,
                            @Value("${app.search.key-review.enabled:true}") boolean enabled,
                            @Value("${app.search.key-review.url:}") String reviewUrl,
                            @Value("${app.search.key-review.connect-timeout-ms:3000}") long connectTimeoutMs,
                            @Value("${app.search.key-review.read-timeout-ms:5000}") long readTimeoutMs) {
        this.enabled = enabled;
        this.reviewUrl = reviewUrl;
        Duration connectTimeout = Duration.ofMillis(Math.max(100, connectTimeoutMs));
        Duration readTimeout = Duration.ofMillis(Math.max(100, readTimeoutMs));
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    public ReviewDecision reviewKeyword(String userId, String keyword) {
        if (!enabled) {
            return ReviewDecision.approved("审核已关闭");
        }
        if (StringUtils.isBlank(reviewUrl)) {
            log.warn("关键字审核URL未配置，默认允许通过");
            return ReviewDecision.approved("审核未配置");
        }
        if (StringUtils.isAnyBlank(userId, keyword)) {
            return ReviewDecision.denied("用户ID或关键词为空");
        }

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("keyWords", keyword);
            requestBody.put("userId", userId);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody);
            ResponseEntity<KeyReviewResponse> response = restTemplate.exchange(
                    reviewUrl,
                    HttpMethod.POST,
                    entity,
                    KeyReviewResponse.class
            );

            KeyReviewResponse body = response.getBody();
            if (body == null) {
                log.warn("关键字审核返回为空, status: {}", response.getStatusCode());
                return ReviewDecision.denied("审核服务无响应");
            }

            if (body.getCode() != 0 || body.getData() == null) {
                String message = StringUtils.defaultIfBlank(body.getMessage(), "审核失败");
                log.warn("关键字审核失败: code={}, message={}", body.getCode(), message);
                return ReviewDecision.denied(message);
            }

            String result = StringUtils.defaultIfBlank(body.getData().getResult(), "审核失败");
            log.info("关键字审核结果: userId={}, result={}", userId, result);

            if ("审核通过".equals(result)||"审核中".equals(result)) {
                return ReviewDecision.approved(result);
            }
            return ReviewDecision.denied(result);

        } catch (RestClientException ex) {
            log.error("调用关键字审核接口失败", ex);
            return ReviewDecision.denied("审核服务调用失败");
        }
    }

    public static class ReviewDecision {
        private final boolean approved;
        private final String message;

        private ReviewDecision(boolean approved, String message) {
            this.approved = approved;
            this.message = message;
        }

        public static ReviewDecision approved(String message) {
            return new ReviewDecision(true, message);
        }

        public static ReviewDecision denied(String message) {
            return new ReviewDecision(false, message);
        }

        public boolean isApproved() {
            return approved;
        }

        public String getMessage() {
            return message;
        }
    }

    private static class KeyReviewResponse {
        private int code;
        private String message;
        private KeyReviewData data;

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

        public KeyReviewData getData() {
            return data;
        }

        public void setData(KeyReviewData data) {
            this.data = data;
        }
    }

    private static class KeyReviewData {
        private String result;

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }
    }
}
