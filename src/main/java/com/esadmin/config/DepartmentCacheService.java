package com.esadmin.config;

import com.esadmin.dto.DepartmentTreeNode;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class DepartmentCacheService {

    private final CacheManager cacheManager;

    public DepartmentCacheService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Cacheable(value = CacheConfig.CACHE_DEPARTMENT_TREE)
    public List<DepartmentTreeNode> getDepartmentTree(Supplier<List<DepartmentTreeNode>> supplier) {
        return supplier.get();
    }

    public void evictDepartmentTree() {
        if (cacheManager != null) {
            cacheManager.getCache(CacheConfig.CACHE_DEPARTMENT_TREE).clear();
        }
    }
}
