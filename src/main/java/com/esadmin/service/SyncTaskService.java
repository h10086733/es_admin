package com.esadmin.service;

import com.esadmin.dto.FormDto;
import com.esadmin.dto.SyncResult;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(SyncTaskService.class);

    @Autowired
    private SyncService syncService;

    @Autowired
    private FormService formService;

    @Autowired
    private RestHighLevelClient esClient;

    @Autowired
    private MemberService memberService;

    @Scheduled(cron = "0 0 0 * * ?") // 每天0点执行一次
    public void executeSync() {
        log.info("开始执行定时同步任务");
        
        try {
            // 1. 首先同步org_member表
            log.info("开始同步org_member表");
            boolean memberSyncResult = memberService.syncMemberData();
            if (!memberSyncResult) {
                log.warn("org_member表同步失败，但继续执行表单同步");
            }
            
            // 2. 获取所有表单
            List<FormDto> forms = formService.getAllForms();
            
            if (forms == null || forms.isEmpty()) {
                log.info("没有找到需要同步的表单");
                return;
            }
            
            int successCount = 0;
            int totalCount = forms.size();
            
            for (FormDto form : forms) {
                try {
                    String formId = form.getId();
                    log.info("开始同步表单: formId={}, formName={}", formId, form.getName());
                    
                    // 判断是否为首次同步（检查ES索引是否存在且有数据）
                    boolean isFirstSync = isFirstSync(formId);
                    
                    // 执行同步
                    SyncResult result = syncService.syncFormData(formId, isFirstSync);
                    
                    if (result.isSuccess()) {
                        successCount++;
                        log.info("表单同步成功: formId={}, 同步条数={}, 同步类型={}, 耗时={} 秒, 速度={} 条/秒", 
                                formId, result.getCount(), isFirstSync ? "全量" : "增量",
                                String.format("%.1f", result.getElapsedTime()),
                                String.format("%.1f", result.getRate()));
                    } else {
                        log.error("表单同步失败: formId={}, 错误信息={}", formId, result.getMessage());
                    }
                    
                } catch (Exception e) {
                    log.error("同步表单时发生异常: formId={}", form.getId(), e);
                }
            }
            
            log.info("定时同步任务完成: 成功={}/{}", successCount, totalCount);
            
        } catch (Exception e) {
            log.error("执行定时同步任务时发生异常", e);
        }
    }

    /**
     * 判断是否为首次同步 - 检查ES索引是否存在且有数据
     */
    private boolean isFirstSync(String formId) {
        try {
            String indexName = "form_" + formId;
            
            // 检查索引是否存在
            GetIndexRequest getRequest = new GetIndexRequest(indexName);
            boolean indexExists = esClient.indices().exists(getRequest, RequestOptions.DEFAULT);
            
            if (!indexExists) {
                log.debug("索引不存在，执行全量同步: {}", indexName);
                return true;
            }
            
            // 索引存在，检查是否有数据 - 通过SyncService中已有的方法
            Long latestRecordId = getLatestRecordIdFromES(formId);
            boolean hasData = latestRecordId != null && latestRecordId > 0;
            
            log.debug("索引 {} 存在，有数据: {}, 执行{}同步", indexName, hasData, hasData ? "增量" : "全量");
            return !hasData;
            
        } catch (Exception e) {
            log.error("检查ES索引状态失败: formId={}, 默认执行全量同步", formId, e);
            return true; // 出错时默认全量同步
        }
    }

    /**
     * 从ES获取最新记录ID - 复用SyncService中的逻辑
     */
    private Long getLatestRecordIdFromES(String formId) {
        try {
            // 这里可以直接调用SyncService中的私有方法逻辑，但为了简化，直接判断索引是否为空
            String indexName = "form_" + formId;
            
            CountRequest countRequest = new CountRequest(indexName);
            countRequest.query(QueryBuilders.matchAllQuery());
            CountResponse countResponse = esClient.count(countRequest, RequestOptions.DEFAULT);
            long totalHits = countResponse.getCount();
            return totalHits > 0 ? 1L : 0L; // 有数据返回1，无数据返回0

        } catch (Exception e) {
            log.debug("获取ES记录数失败: formId={}", formId, e);
            return 0L;
        }
    }
}
