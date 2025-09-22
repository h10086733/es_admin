package com.esadmin.service;

import com.esadmin.dto.FormDto;
import com.esadmin.dto.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Service
public class AsyncSyncService {

    private static final Logger log = LoggerFactory.getLogger(AsyncSyncService.class);

    @Autowired
    private SyncService syncService;

    @Autowired
    private FormService formService;

    // 存储正在进行的同步任务
    private final Map<String, SseEmitter> syncEmitters = new ConcurrentHashMap<>();
    private final Map<String, SyncProgress> syncProgress = new ConcurrentHashMap<>();

    public static class SyncProgress {
        private String status = "preparing";
        private int currentIndex = 0;
        private int totalForms = 0;
        private String currentFormName = "";
        private String currentFormId = "";
        private int successCount = 0;
        private int failureCount = 0;
        private long startTime = System.currentTimeMillis();
        private String message = "准备中...";

        // getters and setters
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getCurrentIndex() { return currentIndex; }
        public void setCurrentIndex(int currentIndex) { this.currentIndex = currentIndex; }
        public int getTotalForms() { return totalForms; }
        public void setTotalForms(int totalForms) { this.totalForms = totalForms; }
        public String getCurrentFormName() { return currentFormName; }
        public void setCurrentFormName(String currentFormName) { this.currentFormName = currentFormName; }
        public String getCurrentFormId() { return currentFormId; }
        public void setCurrentFormId(String currentFormId) { this.currentFormId = currentFormId; }
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        public int getFailureCount() { return failureCount; }
        public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public double getProgress() {
            if (totalForms == 0) return 0;
            return (double) currentIndex / totalForms * 100;
        }

        public double getElapsedTime() {
            return (System.currentTimeMillis() - startTime) / 1000.0;
        }
    }

    /**
     * 启动异步同步所有表单
     */
    public String startAsyncSyncAll(boolean fullSync) {
        String taskId = "sync_" + System.currentTimeMillis();
        
        try {
            // 获取所有表单
            List<FormDto> forms = formService.getAllForms();
            
            // 初始化进度
            SyncProgress progress = new SyncProgress();
            progress.setTotalForms(forms.size());
            progress.setStatus("running");
            progress.setMessage("开始同步 " + forms.size() + " 个表单");
            syncProgress.put(taskId, progress);

            // 异步执行同步任务
            CompletableFuture.runAsync(() -> {
                performAsyncSync(taskId, forms, fullSync);
            });

            log.info("异步同步任务已启动: taskId={}, 表单数量={}", taskId, forms.size());
            return taskId;

        } catch (Exception e) {
            log.error("启动异步同步失败", e);
            throw new RuntimeException("启动异步同步失败: " + e.getMessage());
        }
    }

    /**
     * 注册SSE连接
     */
    public SseEmitter registerSseConnection(String taskId) {
        SseEmitter emitter = new SseEmitter(60000L); // 60秒超时
        
        emitter.onCompletion(() -> {
            log.debug("SSE连接完成: taskId={}", taskId);
            syncEmitters.remove(taskId);
        });
        
        emitter.onTimeout(() -> {
            log.debug("SSE连接超时: taskId={}", taskId);
            syncEmitters.remove(taskId);
        });
        
        emitter.onError((ex) -> {
            log.error("SSE连接错误: taskId={}", taskId, ex);
            syncEmitters.remove(taskId);
        });

        syncEmitters.put(taskId, emitter);
        
        // 发送初始状态
        SyncProgress progress = syncProgress.get(taskId);
        if (progress != null) {
            sendProgressUpdate(taskId, progress);
        }
        
        return emitter;
    }

    /**
     * 获取同步进度
     */
    public SyncProgress getSyncProgress(String taskId) {
        return syncProgress.get(taskId);
    }

    /**
     * 执行异步同步
     */
    private void performAsyncSync(String taskId, List<FormDto> forms, boolean fullSync) {
        SyncProgress progress = syncProgress.get(taskId);
        if (progress == null) {
            log.error("找不到同步进度: taskId={}", taskId);
            return;
        }

        try {
            for (int i = 0; i < forms.size(); i++) {
                FormDto form = forms.get(i);
                
                // 更新进度
                progress.setCurrentIndex(i + 1);
                progress.setCurrentFormName(form.getName());
                progress.setCurrentFormId(form.getId());
                progress.setMessage("正在同步: " + form.getName());
                
                sendProgressUpdate(taskId, progress);

                try {
                    // 执行同步
                    SyncResult result = syncService.syncFormData(form.getId(), fullSync);
                    
                    if (result.isSuccess()) {
                        progress.setSuccessCount(progress.getSuccessCount() + 1);
                        log.info("表单同步成功: formId={}, formName={}, 同步条数={}", 
                                form.getId(), form.getName(), result.getCount());
                    } else {
                        progress.setFailureCount(progress.getFailureCount() + 1);
                        log.error("表单同步失败: formId={}, formName={}, 错误={}", 
                                form.getId(), form.getName(), result.getMessage());
                    }

                } catch (Exception e) {
                    progress.setFailureCount(progress.getFailureCount() + 1);
                    log.error("表单同步异常: formId={}, formName={}", form.getId(), form.getName(), e);
                }

                // 发送进度更新
                sendProgressUpdate(taskId, progress);
            }

            // 完成
            progress.setStatus("completed");
            progress.setMessage(String.format("同步完成: 成功 %d 个，失败 %d 个，耗时 %.1f 秒", 
                    progress.getSuccessCount(), progress.getFailureCount(), progress.getElapsedTime()));
            
            sendProgressUpdate(taskId, progress);

            log.info("异步同步任务完成: taskId={}, 成功={}, 失败={}, 耗时={}秒", 
                    taskId, progress.getSuccessCount(), progress.getFailureCount(), progress.getElapsedTime());

        } catch (Exception e) {
            log.error("异步同步任务失败: taskId={}", taskId, e);
            progress.setStatus("failed");
            progress.setMessage("同步失败: " + e.getMessage());
            sendProgressUpdate(taskId, progress);
        } finally {
            // 清理资源
            cleanupTask(taskId);
        }
    }

    /**
     * 发送进度更新
     */
    private void sendProgressUpdate(String taskId, SyncProgress progress) {
        SseEmitter emitter = syncEmitters.get(taskId);
        if (emitter != null) {
            try {
                // 构建进度数据
                Map<String, Object> data = new HashMap<>();
                data.put("status", progress.getStatus());
                data.put("progress", Math.round(progress.getProgress() * 10) / 10.0);
                data.put("message", progress.getMessage());
                
                // 只有当表单名不为空时才发送
                if (progress.getCurrentFormName() != null && !progress.getCurrentFormName().trim().isEmpty()) {
                    data.put("current_form", progress.getCurrentFormName());
                }
                
                data.put("current_index", progress.getCurrentIndex());
                data.put("total_forms", progress.getTotalForms());
                data.put("success_count", progress.getSuccessCount());
                data.put("failure_count", progress.getFailureCount());
                data.put("elapsed_time", Math.round(progress.getElapsedTime() * 10) / 10.0);

                emitter.send(SseEmitter.event().name("progress").data(data));
                
            } catch (Exception e) {
                log.error("发送SSE进度更新失败: taskId={}", taskId, e);
                syncEmitters.remove(taskId);
            }
        }
    }

    /**
     * 清理任务资源
     */
    private void cleanupTask(String taskId) {
        try {
            // 发送完成事件
            SseEmitter emitter = syncEmitters.get(taskId);
            if (emitter != null) {
                emitter.send(SseEmitter.event().name("complete").data("Task completed"));
                emitter.complete();
            }
        } catch (Exception e) {
            log.debug("清理SSE连接时出错: taskId={}", taskId, e);
        } finally {
            syncEmitters.remove(taskId);
            // 保留进度信息一段时间，便于查询
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(300000); // 5分钟后清理
                    syncProgress.remove(taskId);
                    log.debug("清理同步进度缓存: taskId={}", taskId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}