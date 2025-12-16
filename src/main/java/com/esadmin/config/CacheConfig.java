package com.esadmin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@EnableCaching
public class CacheConfig implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    public static final String CACHE_DEPARTMENT_TREE = "departmentTree";
    public static final String CACHE_PERMISSION_LIST = "permissionList";
    public static final String CACHE_SOURCE_PERMISSION = "sourcePermission";

    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
    public CacheManager cacheManager() {
        log.info("启用内存缓存: {}", Arrays.asList(
                CACHE_DEPARTMENT_TREE,
                CACHE_PERMISSION_LIST,
                CACHE_SOURCE_PERMISSION
        ));
        return new ConcurrentMapCacheManager(
                CACHE_DEPARTMENT_TREE,
                CACHE_PERMISSION_LIST,
                CACHE_SOURCE_PERMISSION
        );
    }

    @Override
    public void afterPropertiesSet() {
        log.info("缓存配置初始化完成");
    }
}
