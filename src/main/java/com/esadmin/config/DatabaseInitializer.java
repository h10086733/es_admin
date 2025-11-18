package com.esadmin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements CommandLineRunner {
    
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    @Override
    public void run(String... args) throws Exception {
        try {
            initializeReviewPolicyTable();
        } catch (Exception e) {
            log.error("数据库初始化失败", e);
        }
    }
    
    private void initializeReviewPolicyTable() {
        try {
            // 检查表是否存在
            boolean tableExists = checkTableExists("review_policy");
            
            if (tableExists) {
                log.info("review_policy 表已存在，跳过初始化");
                return;
            }
            
            log.info("review_policy 表不存在，开始创建...");
            
            // 创建 review_policy 表 - 兼容达梦数据库语法
            String createTableSql = "CREATE TABLE review_policy (" +
                    "id BIGINT NOT NULL IDENTITY(1,1), " +
                    "source_type VARCHAR(20) NOT NULL, " +
                    "source_id VARCHAR(100) NOT NULL, " +
                    "source_name VARCHAR(200), " +
                    "review_mode VARCHAR(20) NOT NULL, " +
                    "created_at DATETIME, " +
                    "updated_at DATETIME, " +
                    "CONSTRAINT pk_review_policy PRIMARY KEY (id)" +
                    ")";
            
            try {
                jdbcTemplate.execute(createTableSql);
            } catch (Exception e) {
                // 如果IDENTITY语法不支持，尝试使用AUTO_INCREMENT
                log.debug("IDENTITY语法失败，尝试AUTO_INCREMENT: {}", e.getMessage());
                String fallbackSql = "CREATE TABLE review_policy (" +
                        "id BIGINT NOT NULL AUTO_INCREMENT, " +
                        "source_type VARCHAR(20) NOT NULL, " +
                        "source_id VARCHAR(100) NOT NULL, " +
                        "source_name VARCHAR(200), " +
                        "review_mode VARCHAR(20) NOT NULL, " +
                        "created_at DATETIME, " +
                        "updated_at DATETIME, " +
                        "PRIMARY KEY (id)" +
                        ")";
                jdbcTemplate.execute(fallbackSql);
            }
            
            // 创建唯一索引
            try {
                String createIndexSql = "CREATE UNIQUE INDEX idx_review_policy_source ON review_policy (source_type, source_id)";
                jdbcTemplate.execute(createIndexSql);
            } catch (Exception e) {
                log.warn("创建唯一索引失败: {}", e.getMessage());
                // 索引创建失败不影响基本功能，只记录警告
            }
            
            log.info("review_policy 表创建成功");
            
        } catch (Exception e) {
            log.error("创建 review_policy 表失败", e);
            throw new RuntimeException("数据库表初始化失败", e);
        }
    }
    
    private boolean checkTableExists(String tableName) {
        try {
            // 首先尝试达梦数据库的系统表查询
            String dmCheckSql = "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = ?";
            
            try {
                Integer count = jdbcTemplate.queryForObject(dmCheckSql, Integer.class, tableName.toUpperCase());
                return count != null && count > 0;
            } catch (Exception dmEx) {
                // 如果达梦查询失败，尝试标准信息模式
                log.debug("达梦数据库表查询失败，尝试标准查询方式: {}", dmEx.getMessage());
            }
            
            // 使用标准信息模式查询表是否存在
            String standardCheckSql = "SELECT COUNT(*) FROM information_schema.tables WHERE UPPER(table_name) = UPPER(?)";
            
            try {
                Integer count = jdbcTemplate.queryForObject(standardCheckSql, Integer.class, tableName);
                return count != null && count > 0;
            } catch (Exception standardEx) {
                log.debug("标准信息模式查询失败，尝试直接查询表: {}", standardEx.getMessage());
            }
            
            // 如果以上都失败，尝试直接查询表
            try {
                jdbcTemplate.queryForObject("SELECT 1 FROM " + tableName + " WHERE 1=0", Integer.class);
                return true;
            } catch (Exception directEx) {
                log.debug("直接查询表失败，表可能不存在: {}", directEx.getMessage());
                return false;
            }
            
        } catch (Exception e) {
            log.warn("检查表是否存在时发生异常: {}", e.getMessage());
            return false;
        }
    }
}