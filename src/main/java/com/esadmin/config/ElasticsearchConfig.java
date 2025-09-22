package com.esadmin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.username}")
    private String username;

    @Value("${elasticsearch.password}")
    private String password;

    @Value("${elasticsearch.timeout}")
    private int timeout;

    @Value("${elasticsearch.verify-certs}")
    private boolean verifyCerts;

    @Bean
    public RestHighLevelClient elasticsearchClient() {
        try {
            // 解析主机和端口
            String[] hostParts = host.split(":");
            String hostname = hostParts[0];
            int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : 9200;

            // 创建认证提供者
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(username, password));

            // 构建客户端  
            RestClientBuilder builder = RestClient.builder(new HttpHost(hostname, port, "http"))
                    .setHttpClientConfigCallback(httpClientBuilder -> {
                        httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                        
                        if (!verifyCerts) {
                            try {
                                SSLContext sslContext = SSLContexts.custom()
                                        .loadTrustMaterial(null, (certificate, authType) -> true)
                                        .build();
                                httpClientBuilder.setSSLContext(sslContext);
                            } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
                                log.error("SSL配置失败", e);
                            }
                        }
                        
                        return httpClientBuilder;
                    })
                    .setRequestConfigCallback(requestConfigBuilder ->
                            requestConfigBuilder
                                    .setConnectTimeout(timeout * 1000)
                                    .setSocketTimeout(timeout * 1000));

            RestHighLevelClient client = new RestHighLevelClient(builder);
            
            // 测试连接
            try {
                client.info(org.elasticsearch.client.RequestOptions.DEFAULT);
                log.info("Elasticsearch连接成功: {}", host);
            } catch (IOException e) {
                log.error("Elasticsearch连接失败: {}", e.getMessage());
            }
            
            return client;
            
        } catch (Exception e) {
            log.error("创建Elasticsearch客户端失败", e);
            throw new RuntimeException("无法创建Elasticsearch客户端", e);
        }
    }
}