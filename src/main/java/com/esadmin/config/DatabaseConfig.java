package com.esadmin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    public static boolean isDatabaseAvailable(DataSource dataSource) {
        try {
            if (dataSource != null) {
                try (Connection connection = dataSource.getConnection()) {
                    return connection != null && !connection.isClosed();
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("数据库连接不可用: {}", e.getMessage());
            return false;
        }
    }
}