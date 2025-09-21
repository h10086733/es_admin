#!/bin/bash
# Elasticsearch 内网安装脚本

set -e

echo "=== Elasticsearch 内网安装脚本 ==="

# 配置变量
ES_VERSION="7.17.9"
ES_USER="elasticsearch"
ES_HOME="/usr/local/elasticsearch"
ES_DATA_DIR="/data/elasticsearch"
ES_LOG_DIR="/var/log/elasticsearch"

# 检查是否为root用户
if [ "$EUID" -ne 0 ]; then
    echo "请使用root用户运行此脚本"
    exit 1
fi

echo "1. 检查系统环境..."

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java，请先安装Java 8或11"
    echo "推荐安装OpenJDK 11:"
    echo "  CentOS/RHEL: yum install java-11-openjdk"
    echo "  Ubuntu/Debian: apt install openjdk-11-jdk"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n1 | cut -d'"' -f2)
echo "Java版本: $JAVA_VERSION"

echo "2. 创建elasticsearch用户..."
if ! id "$ES_USER" &>/dev/null; then
    useradd -r -s /bin/false $ES_USER
    echo "创建用户: $ES_USER"
else
    echo "用户已存在: $ES_USER"
fi

echo "3. 创建目录结构..."
mkdir -p $ES_HOME
mkdir -p $ES_DATA_DIR/{data,logs}
mkdir -p $ES_LOG_DIR

echo "4. 检查Elasticsearch压缩包..."
if [ ! -f "elasticsearch-${ES_VERSION}-linux-x86_64.tar.gz" ]; then
    echo "错误: 未找到 elasticsearch-${ES_VERSION}-linux-x86_64.tar.gz"
    echo "请下载Elasticsearch压缩包到当前目录"
    echo "下载地址: https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-${ES_VERSION}-linux-x86_64.tar.gz"
    exit 1
fi

echo "5. 解压Elasticsearch..."
tar -xzf elasticsearch-${ES_VERSION}-linux-x86_64.tar.gz -C $ES_HOME --strip-components=1

echo "6. 复制配置文件..."
if [ -f "elasticsearch.yml" ]; then
    cp elasticsearch.yml $ES_HOME/config/
    echo "已复制 elasticsearch.yml"
else
    echo "警告: 未找到 elasticsearch.yml 配置文件"
fi

if [ -f "jvm.options" ]; then
    cp jvm.options $ES_HOME/config/
    echo "已复制 jvm.options"
else
    echo "警告: 未找到 jvm.options 配置文件"
fi

echo "7. 设置目录权限..."
chown -R $ES_USER:$ES_USER $ES_HOME
chown -R $ES_USER:$ES_USER $ES_DATA_DIR
chown -R $ES_USER:$ES_USER $ES_LOG_DIR

echo "8. 配置系统参数..."

# 设置文件描述符限制
cat >> /etc/security/limits.conf << EOF
$ES_USER soft nofile 65536
$ES_USER hard nofile 65536
$ES_USER soft nproc 4096
$ES_USER hard nproc 4096
EOF

# 设置内存映射区域
echo "vm.max_map_count=262144" >> /etc/sysctl.conf
sysctl -p

echo "9. 创建systemd服务..."
cat > /etc/systemd/system/elasticsearch.service << EOF
[Unit]
Description=Elasticsearch
Documentation=https://www.elastic.co
Wants=network-online.target
After=network-online.target

[Service]
Type=notify
RuntimeDirectory=elasticsearch
PrivateTmp=true
Environment=ES_HOME=$ES_HOME
Environment=ES_PATH_CONF=$ES_HOME/config
Environment=PID_DIR=/var/run/elasticsearch
WorkingDirectory=$ES_HOME

User=$ES_USER
Group=$ES_USER

ExecStart=$ES_HOME/bin/elasticsearch

StandardOutput=journal
StandardError=inherit

LimitNOFILE=65535
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
EOF

echo "10. 启用并启动Elasticsearch服务..."
systemctl daemon-reload
systemctl enable elasticsearch

echo "11. 启动Elasticsearch..."
systemctl start elasticsearch

echo "12. 等待Elasticsearch启动..."
sleep 30

# 检查服务状态
if systemctl is-active --quiet elasticsearch; then
    echo "✓ Elasticsearch启动成功"
    
    # 测试连接
    echo "13. 测试Elasticsearch连接..."
    if curl -s http://localhost:9200 > /dev/null; then
        echo "✓ Elasticsearch运行正常"
        curl -s http://localhost:9200 | python -m json.tool
    else
        echo "⚠ Elasticsearch可能未完全启动，请稍后检查"
    fi
else
    echo "✗ Elasticsearch启动失败"
    echo "查看日志: journalctl -u elasticsearch -f"
    exit 1
fi

echo ""
echo "=== Elasticsearch安装完成 ==="
echo "服务状态: systemctl status elasticsearch"
echo "查看日志: journalctl -u elasticsearch -f"
echo "停止服务: systemctl stop elasticsearch"
echo "重启服务: systemctl restart elasticsearch"
echo ""
echo "下一步："
echo "1. 修改 $ES_HOME/config/elasticsearch.yml 中的配置"
echo "2. 设置用户密码: $ES_HOME/bin/elasticsearch-setup-passwords auto"
echo "3. 重启服务: systemctl restart elasticsearch"