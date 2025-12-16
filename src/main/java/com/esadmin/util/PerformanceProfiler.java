package com.esadmin.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class PerformanceProfiler {
    
    private static final Logger log = LoggerFactory.getLogger(PerformanceProfiler.class);
    
    private final ThreadLocal<Map<String, Long>> threadLocalTimes = ThreadLocal.withInitial(ConcurrentHashMap::new);
    
    /**
     * 开始计时
     */
    public void start(String operation) {
        threadLocalTimes.get().put(operation, System.currentTimeMillis());
        log.debug("开始计时: {}", operation);
    }
    
    /**
     * 结束计时并记录
     */
    public long end(String operation) {
        Long startTime = threadLocalTimes.get().remove(operation);
        if (startTime == null) {
            log.warn("未找到操作的开始时间: {}", operation);
            return 0;
        }
        
        long duration = System.currentTimeMillis() - startTime;
        
        if (duration > 2000) {
            log.error("超慢操作: {} 耗时 {}ms", operation, duration);
        } else if (duration > 1000) {
            log.warn("慢操作: {} 耗时 {}ms", operation, duration);
        } else if (duration > 500) {
            log.info("一般操作: {} 耗时 {}ms", operation, duration);
        } else {
            log.debug("快速操作: {} 耗时 {}ms", operation, duration);
        }
        
        return duration;
    }
    
    /**
     * 计时执行方法
     */
    public <T> T time(String operation, TimeableOperation<T> timeableOperation) {
        start(operation);
        try {
            return timeableOperation.execute();
        } catch (Exception e) {
            log.error("操作执行失败: {}", operation, e);
            throw new RuntimeException("操作执行失败: " + operation, e);
        } finally {
            end(operation);
        }
    }
    
    /**
     * 清理线程本地数据
     */
    public void cleanup() {
        threadLocalTimes.remove();
    }
    
    @FunctionalInterface
    public interface TimeableOperation<T> {
        T execute() throws Exception;
    }
}