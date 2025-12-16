package com.esadmin.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PerformanceMonitor {
    
    private static final Logger log = LoggerFactory.getLogger(PerformanceMonitor.class);
    
    private final ConcurrentHashMap<String, AtomicLong> totalTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> maxTimes = new ConcurrentHashMap<>();
    
    public void recordTime(String operation, long timeMs) {
        totalTimes.computeIfAbsent(operation, k -> new AtomicLong(0)).addAndGet(timeMs);
        counts.computeIfAbsent(operation, k -> new AtomicInteger(0)).incrementAndGet();
        
        AtomicLong maxTime = maxTimes.computeIfAbsent(operation, k -> new AtomicLong(0));
        maxTime.accumulateAndGet(timeMs, Math::max);
        
        // 记录慢查询
        if (timeMs > 1000) {
            log.warn("慢查询检测: {} 耗时 {}ms", operation, timeMs);
        } else if (timeMs > 500) {
            log.info("性能监控: {} 耗时 {}ms", operation, timeMs);
        }
    }
    
    public void printStatistics() {
        log.info("=== 性能统计报告 ===");
        for (String operation : totalTimes.keySet()) {
            long total = totalTimes.get(operation).get();
            int count = counts.get(operation).get();
            long max = maxTimes.get(operation).get();
            double avg = count > 0 ? (double) total / count : 0;
            
            log.info("操作: {}, 总次数: {}, 总耗时: {}ms, 平均耗时: {:.2f}ms, 最大耗时: {}ms", 
                    operation, count, total, avg, max);
        }
        log.info("=== 性能统计报告结束 ===");
    }
    
    public void clearStatistics() {
        totalTimes.clear();
        counts.clear();
        maxTimes.clear();
        log.info("性能统计数据已清除");
    }
}