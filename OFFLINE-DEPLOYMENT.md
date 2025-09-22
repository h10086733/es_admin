# ES数据管理系统离线部署指南

## 系统要求

- CentOS/RHEL 8+ 或 Ubuntu 20.04+
- CPU架构: x86_64 或 ARM64 (aarch64)
- 内存: 4GB+ (推荐8GB)
- 磁盘: 20GB+ (推荐50GB)
- OpenJDK 11.0.19+ (必须，ARM64兼容版本)

## 指定版本说明

**核心组件版本:**
- **Elasticsearch**: 7.17.15 (LTS稳定版)
- **OpenJDK**: 11.0.19 (LTS版本，ARM64原生支持)
- **架构支持**: x86_64、ARM64 (aarch64)

## 1. 离线安装Elasticsearch 7.17.15

### 1.1 下载ES安装包

**指定版本: Elasticsearch 7.17.15 (LTS稳定版本)**

在有网络的机器上下载：
```bash
# x86_64架构 (Intel/AMD)
wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.17.15-linux-x86_64.tar.gz

# ARM64架构 (ARM服务器)
wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-7.17.15-linux-aarch64.tar.gz

# 验证下载
# x86_64 SHA512: 
sha512sum elasticsearch-7.17.15-linux-x86_64.tar.gz
# ARM64 SHA512:
sha512sum elasticsearch-7.17.15-linux-aarch64.tar.gz
```

**文件大小参考:**
- x86_64版本: ~330MB
- ARM64版本: ~330MB

### 1.2 传输到目标服务器

```bash
# 检测目标服务器架构
uname -m
# x86_64 -> 使用 x86_64 版本
# aarch64 -> 使用 ARM64 版本

# 使用scp传输 (根据架构选择对应文件)
scp elasticsearch-7.17.15-linux-x86_64.tar.gz user@target-server:/opt/
# 或
scp elasticsearch-7.17.15-linux-aarch64.tar.gz user@target-server:/opt/

# 或使用U盘等离线方式传输
```

### 1.3 解压和配置

```bash
# 解压 (根据架构选择对应文件)
cd /opt

# x86_64架构
tar -xzf elasticsearch-7.17.15-linux-x86_64.tar.gz
# 或 ARM64架构  
tar -xzf elasticsearch-7.17.15-linux-aarch64.tar.gz

mv elasticsearch-7.17.15 elasticsearch

# 创建ES用户（不能用root运行）
useradd elasticsearch
chown -R elasticsearch:elasticsearch /opt/elasticsearch

# 验证架构兼容性
file /opt/elasticsearch/bin/elasticsearch
```

### 1.4 配置Elasticsearch

编辑配置文件：
```bash
vi /opt/elasticsearch/config/elasticsearch.yml
```

基本配置：
```yaml
# 集群名称
cluster.name: es-admin-cluster

# 节点名称  
node.name: node-1

# 数据存储路径
path.data: /opt/elasticsearch/data
path.logs: /opt/elasticsearch/logs

# 网络配置
network.host: 0.0.0.0
http.port: 9200

# 集群配置（单节点）
discovery.type: single-node

# 安全配置（可选）
xpack.security.enabled: false
xpack.ml.enabled: false
```

### 1.5 配置JVM参数

编辑JVM配置：
```bash
vi /opt/elasticsearch/config/jvm.options
```

**针对不同架构和内存的JVM配置：**

```bash
# === ARM64架构优化 ===
# ARM64特定的JVM参数
-XX:+UnlockExperimentalVMOptions
-XX:+UseZGC
-XX:+UseTransparentHugePages

# === 内存配置 ===
# 4GB服务器配置
-Xms1g
-Xmx1g

# 8GB服务器配置（推荐）
-Xms2g
-Xmx2g

# 16GB服务器配置
-Xms4g
-Xmx4g

# === 通用优化参数 ===
-XX:+UseG1GC
-XX:G1HeapRegionSize=16m
-XX:+DisableExplicitGC
-Djava.io.tmpdir=/opt/elasticsearch/tmp
```

### 1.6 系统配置

编辑系统限制：
```bash
vi /etc/security/limits.conf
```

添加：
```
elasticsearch soft nofile 65536
elasticsearch hard nofile 65536
elasticsearch soft nproc 4096
elasticsearch hard nproc 4096
```

编辑内核参数：
```bash
vi /etc/sysctl.conf
```

添加：
```
vm.max_map_count=262144
```

应用配置：
```bash
sysctl -p
```

### 1.7 创建启动脚本

创建systemd服务：
```bash
vi /etc/systemd/system/elasticsearch.service
```

内容：
```ini
[Unit]
Description=Elasticsearch
Documentation=https://www.elastic.co
Wants=network-online.target
After=network-online.target

[Service]
Type=notify
RuntimeDirectory=elasticsearch
PrivateTmp=true
Environment=ES_HOME=/opt/elasticsearch
Environment=ES_PATH_CONF=/opt/elasticsearch/config
Environment=PID_DIR=/var/run/elasticsearch
WorkingDirectory=/opt/elasticsearch
User=elasticsearch
Group=elasticsearch
ExecStart=/opt/elasticsearch/bin/elasticsearch
StandardOutput=journal
StandardError=inherit
LimitNOFILE=65536
LimitNPROC=4096
LimitAS=infinity
LimitFSIZE=infinity
TimeoutStopSec=0
KillSignal=SIGTERM
KillMode=process
SendSIGKILL=no
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

### 1.8 启动Elasticsearch

```bash
# 重载systemd配置
systemctl daemon-reload

# 启动ES
systemctl enable elasticsearch
systemctl start elasticsearch

# 检查状态
systemctl status elasticsearch

# 验证启动
curl http://localhost:9200
```

## 2. 离线运行ES数据管理系统

### 2.1 准备Java环境

**指定版本: OpenJDK 11.0.19 (LTS版本，ARM64兼容)**

#### 2.1.1 下载OpenJDK 11.0.19

在有网络的机器上下载：
```bash
# x86_64架构
wget https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.19%2B7/OpenJDK11U-jdk_x64_linux_hotspot_11.0.19_7.tar.gz

# ARM64架构
wget https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.19%2B7/OpenJDK11U-jdk_aarch64_linux_hotspot_11.0.19_7.tar.gz

# 验证下载
sha256sum OpenJDK11U-jdk_*_linux_hotspot_11.0.19_7.tar.gz
```

#### 2.1.2 离线安装Java

```bash
# 检测架构
uname -m

# 传输到目标服务器并安装
cd /opt

# x86_64架构
tar -xzf OpenJDK11U-jdk_x64_linux_hotspot_11.0.19_7.tar.gz
# 或 ARM64架构
tar -xzf OpenJDK11U-jdk_aarch64_linux_hotspot_11.0.19_7.tar.gz

mv jdk-11.0.19+7 java-11-openjdk

# 设置环境变量
echo 'export JAVA_HOME=/opt/java-11-openjdk' >> /etc/profile
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> /etc/profile
source /etc/profile

# 验证安装
java -version
# 应显示: openjdk version "11.0.19" 2023-04-18

# 验证架构匹配
file $JAVA_HOME/bin/java
```

### 2.2 准备应用文件

确保已有以下文件：
```
es-admin-1.0.0.jar    # 应用程序
.env                  # 环境配置文件
start.sh             # 启动脚本
```

### 2.3 配置环境文件

创建或编辑 `.env` 文件：
```bash
vi .env
```

配置内容：
```properties
# 服务器配置
SERVER_HOST=0.0.0.0
SERVER_PORT=5000

# 达梦数据库配置
DM_HOST=192.168.1.100
DM_PORT=5236
DM_DATABASE=your_database
DM_USER=your_username
DM_PASSWORD=your_password

# Elasticsearch配置
ES_HOST=localhost:9200
ES_USER=
ES_PASSWORD=

# 日志配置
LOG_LEVEL=INFO
LOG_PATH=./logs
```

### 2.4 数据库准备

确保达梦数据库已启动，并创建必要的表结构：

```sql
-- 创建表单定义表
CREATE TABLE form_definition (
    id VARCHAR(64) PRIMARY KEY,
    form_name VARCHAR(255) NOT NULL,
    form_config TEXT,
    table_name VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 创建组织成员表
CREATE TABLE org_member (
    id VARCHAR(64) PRIMARY KEY,
    member_id VARCHAR(255) NOT NULL,
    member_name VARCHAR(255),
    department VARCHAR(255),
    position VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 2.5 启动应用

使用启动脚本：
```bash
chmod +x start.sh
./start.sh
```

或直接启动：
```bash
# 基本启动
java -Xmx2g -Xms1g -Dfile.encoding=UTF-8 -jar es-admin-1.0.0.jar

# ARM64优化启动参数
java -Xmx2g -Xms1g \
     -Dfile.encoding=UTF-8 \
     -XX:+UseG1GC \
     -XX:+UseContainerSupport \
     -XX:MaxRAMPercentage=75.0 \
     -jar es-admin-1.0.0.jar
```

### 2.6 验证服务

检查服务状态：
```bash
# 检查端口
ss -tlnp | grep :5000

# 检查HTTP响应
curl http://localhost:5000

# 检查日志
tail -f logs/es-admin.log
```

### 2.7 创建系统服务

创建systemd服务：
```bash
vi /etc/systemd/system/es-admin.service
```

内容：
```ini
[Unit]
Description=ES Admin Application
After=network.target elasticsearch.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/es-admin
Environment=JAVA_HOME=/opt/java-11-openjdk
ExecStart=/opt/java-11-openjdk/bin/java -Xmx2g -Xms1g -Dfile.encoding=UTF-8 -XX:+UseG1GC -XX:+UseContainerSupport -jar /opt/es-admin/es-admin-1.0.0.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启动服务：
```bash
systemctl daemon-reload
systemctl enable es-admin
systemctl start es-admin
systemctl status es-admin
```

## 3. 健康检查和维护

### 3.1 健康检查脚本

创建检查脚本：
```bash
vi health-check.sh
chmod +x health-check.sh
```

内容：
```bash
#!/bin/bash
echo "=== ES Admin 健康检查 ==="

# 检查Elasticsearch
if curl -f -s http://localhost:9200 > /dev/null; then
    echo "✓ Elasticsearch服务正常"
else
    echo "✗ Elasticsearch服务异常"
fi

# 检查应用
if curl -f -s http://localhost:5000 > /dev/null; then
    echo "✓ ES Admin应用正常"
else
    echo "✗ ES Admin应用异常"
fi

# 检查进程
if pgrep -f "es-admin.*jar" > /dev/null; then
    echo "✓ Java进程运行中"
else
    echo "✗ Java进程未运行"
fi

# 检查磁盘空间
DISK_USAGE=$(df / | awk 'NR==2 {print $5}' | sed 's/%//')
if [ $DISK_USAGE -lt 90 ]; then
    echo "✓ 磁盘空间充足 ($DISK_USAGE%)"
else
    echo "⚠ 磁盘空间不足 ($DISK_USAGE%)"
fi
```

### 3.2 日志管理

配置日志轮转：
```bash
vi /etc/logrotate.d/es-admin
```

内容：
```
/opt/es-admin/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
```

### 3.3 备份策略

创建备份脚本：
```bash
vi backup.sh
chmod +x backup.sh
```

内容：
```bash
#!/bin/bash
BACKUP_DIR="/backup/es-admin"
DATE=$(date +%Y%m%d_%H%M%S)

mkdir -p $BACKUP_DIR

# 备份ES索引
curl -X POST "localhost:9200/_snapshot/backup_repo/_snapshot/snapshot_$DATE?wait_for_completion=true"

# 备份应用配置
tar -czf $BACKUP_DIR/config_$DATE.tar.gz .env start.sh

# 备份数据库（如果需要）
# mysqldump或达梦数据库备份命令

echo "备份完成: $BACKUP_DIR"
```

## 4. 故障排查

### 4.1 常见问题

**Elasticsearch启动失败：**
- 检查Java版本和JAVA_HOME
- 检查内存设置和系统资源
- 检查文件权限和用户配置
- 查看日志：`tail -f /opt/elasticsearch/logs/es-admin-cluster.log`

**应用连接ES失败：**
- 检查ES服务状态：`systemctl status elasticsearch`
- 检查网络连接：`curl http://localhost:9200`
- 检查.env配置中的ES_HOST设置

**数据库连接失败：**
- 检查达梦数据库服务状态
- 验证连接参数和网络连通性
- 检查数据库用户权限

### 4.2 日志位置

- Elasticsearch日志：`/opt/elasticsearch/logs/`
- 应用日志：`./logs/es-admin.log`
- 系统日志：`journalctl -u es-admin -f`

### 4.3 性能优化

**Elasticsearch优化：**
- 根据数据量调整JVM堆内存
- 配置合适的分片和副本数量
- 定期清理过期索引

**应用优化：**
- 调整Java JVM参数
- 配置连接池大小
- 启用应用缓存

## 5. 安全配置

### 5.1 防火墙配置

```bash
# 只开放必要端口
firewall-cmd --permanent --add-port=5000/tcp
firewall-cmd --permanent --add-port=9200/tcp
firewall-cmd --reload
```

### 5.2 用户权限

- 使用专用用户运行服务
- 限制文件访问权限
- 定期更新系统补丁

这个离线部署指南涵盖了完整的安装、配置、启动和维护流程，确保在无网络环境下也能成功部署和运行ES数据管理系统。