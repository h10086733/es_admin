#!/bin/bash
# Elasticsearch ARM架构下载脚本

set -e

echo "=== Elasticsearch ARM架构下载脚本 ==="

# 检测CPU架构
ARCH=$(uname -m)
case $ARCH in
    x86_64)
        ARCH_NAME="x86_64"
        ES_ARCH="x86_64"
        ;;
    aarch64|arm64)
        ARCH_NAME="aarch64"
        ES_ARCH="aarch64"
        ;;
    *)
        echo "警告: 不支持的CPU架构: $ARCH"
        echo "Elasticsearch官方只支持 x86_64 和 aarch64"
        echo "对于其他ARM架构，建议使用Docker方式部署"
        exit 1
        ;;
esac

echo "检测到CPU架构: $ARCH ($ARCH_NAME)"

# Elasticsearch版本
ES_VERSION="7.17.9"

# 根据架构下载对应版本
case $ARCH_NAME in
    "x86_64")
        ES_DOWNLOAD_URL="https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-${ES_VERSION}-linux-x86_64.tar.gz"
        ES_FILENAME="elasticsearch-${ES_VERSION}-linux-x86_64.tar.gz"
        ;;
    "aarch64")
        ES_DOWNLOAD_URL="https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-${ES_VERSION}-linux-aarch64.tar.gz"
        ES_FILENAME="elasticsearch-${ES_VERSION}-linux-aarch64.tar.gz"
        ;;
esac

echo ""
echo "下载Elasticsearch ${ES_VERSION} for ${ARCH_NAME}..."
echo "下载地址: $ES_DOWNLOAD_URL"

# 下载Elasticsearch
if [ ! -f "$ES_FILENAME" ]; then
    echo "开始下载..."
    wget -O "$ES_FILENAME" "$ES_DOWNLOAD_URL"
    echo "下载完成: $ES_FILENAME"
else
    echo "文件已存在: $ES_FILENAME"
fi

# 验证下载
if [ -f "$ES_FILENAME" ]; then
    echo "验证文件大小..."
    FILE_SIZE=$(stat -c%s "$ES_FILENAME")
    if [ $FILE_SIZE -gt 100000000 ]; then  # 大于100MB
        echo "✓ 文件下载成功 (${FILE_SIZE} 字节)"
    else
        echo "✗ 文件可能下载不完整，请检查"
        exit 1
    fi
else
    echo "✗ 下载失败"
    exit 1
fi

# 创建ARM架构安装说明
cat > "elasticsearch_${ARCH_NAME}_install.md" << EOF
# Elasticsearch ${ES_VERSION} - ${ARCH_NAME} 架构安装说明

## 文件信息
- 架构: ${ARCH_NAME}
- 版本: ${ES_VERSION}
- 文件: ${ES_FILENAME}

## ARM架构特别注意事项

### 1. 内存要求
- 最小内存: 2GB
- 推荐内存: 4GB+
- JVM堆内存建议设置为物理内存的25-50%

### 2. 性能调优
ARM架构建议的JVM参数：
\`\`\`
# ARM优化的JVM参数
-Xms1g
-Xmx1g
-XX:+UseG1GC
-XX:+UnlockExperimentalVMOptions
-XX:+UseJVMCICompiler  # 如果支持GraalVM
\`\`\`

### 3. 安装步骤
\`\`\`bash
# 解压
tar -xzf ${ES_FILENAME} -C /usr/local/
cd /usr/local/elasticsearch-${ES_VERSION}

# 设置权限
useradd elasticsearch
chown -R elasticsearch:elasticsearch .

# 启动测试
sudo -u elasticsearch ./bin/elasticsearch
\`\`\`

### 4. 配置优化
针对ARM架构的elasticsearch.yml配置：
\`\`\`yaml
# 集群配置
cluster.name: es-arm-cluster
node.name: es-arm-node-1

# 内存锁定（ARM架构重要）
bootstrap.memory_lock: true

# 网络配置
network.host: 0.0.0.0
http.port: 9200

# 数据路径
path.data: /data/elasticsearch/data
path.logs: /data/elasticsearch/logs

# ARM架构优化
index.number_of_shards: 1
index.number_of_replicas: 0
indices.memory.index_buffer_size: 20%

# 刷新频率调整（ARM性能优化）
index.refresh_interval: 30s
\`\`\`

### 5. 性能预期
| 指标 | x86_64 | aarch64 | 说明 |
|------|--------|---------|------|
| 索引速度 | 100% | 80-90% | ARM性能略低 |
| 查询速度 | 100% | 85-95% | 查询性能较好 |
| 内存使用 | 标准 | 相似 | 内存效率相当 |
| 启动时间 | 快 | 稍慢 | JVM预热时间长 |

### 6. 故障排除
常见ARM架构问题：
1. **内存不足**: 减少JVM堆内存设置
2. **启动慢**: 正常现象，ARM架构JVM启动较慢
3. **CPU使用率高**: 调整refresh_interval和批处理参数
4. **磁盘IO**: 使用SSD存储提升性能

### 7. 兼容性
- ✅ 支持所有ES核心功能
- ✅ 支持中文分词插件
- ✅ 支持X-Pack安全功能
- ⚠️  某些第三方插件可能不兼容ARM
EOF

echo ""
echo "=== 下载完成 ==="
echo "文件: $ES_FILENAME"
echo "架构: $ARCH_NAME"
echo "安装说明: elasticsearch_${ARCH_NAME}_install.md"
echo ""
echo "下一步:"
echo "1. 将文件传输到目标ARM服务器"
echo "2. 参考安装说明进行部署"
echo "3. 注意ARM架构的性能调优建议"