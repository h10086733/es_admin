package com.esadmin;

import com.esadmin.service.FormService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 测试FormService的getTableDataBatchByMinId方法修复
 */
public class FormServiceTest {
    
    private static final Logger log = LoggerFactory.getLogger(FormServiceTest.class);
    
    @Test
    public void testGetTableDataBatchByMinIdWithNegativeIds() {
        // 创建数据源
        DataSource dataSource = DataSourceBuilder.create()
                .url("jdbc:dm://192.168.31.157:5236")
                .username("SEEYON0725")
                .password("seeyon@123")
                .driverClassName("dm.jdbc.driver.DmDriver")
                .build();
        
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        FormService formService = new FormService(null, null, jdbcTemplate);
        
        try {
            // 模拟之前会导致无限循环的场景
            // 使用负数ID作为minId参数
            Long negativeMinId = -497849538846635276L;
            
            log.info("测试修复前的问题场景：minId = {}", negativeMinId);
            
            // 执行查询 - 在修复前这会导致无限循环
            List<Map<String, Object>> result = formService.getTableDataBatchByMinId(
                "FORM_DEMO0401_1301", 
                1000, 
                0, 
                negativeMinId
            );
            
            log.info("查询结果：{} 条记录", result.size());
            
            if (!result.isEmpty()) {
                Object firstId = result.get(0).get("ID");
                Object lastId = result.get(result.size() - 1).get("ID");
                log.info("第一条记录ID: {}, 最后一条记录ID: {}", firstId, lastId);
                
                // 验证修复：返回的记录ID应该都大于minId
                for (Map<String, Object> record : result) {
                    Object recordId = record.get("ID");
                    if (recordId instanceof Number) {
                        long id = ((Number) recordId).longValue();
                        if (id <= negativeMinId) {
                            log.error("发现问题：记录ID {} 不大于 minId {}", id, negativeMinId);
                            throw new AssertionError("修复失败：仍然返回了不满足条件的记录");
                        }
                    }
                }
                
                log.info("验证通过：所有返回的记录ID都大于minId");
            } else {
                log.info("查询结果为空，这是正常的");
            }
            
        } catch (Exception e) {
            log.error("测试失败", e);
            throw new RuntimeException("测试执行失败", e);
        }
    }
}