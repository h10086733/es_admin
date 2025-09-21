#!/bin/bash
# ES管理服务一键部署脚本

set -e

echo "=== ES管理服务一键部署脚本 ==="
echo "此脚本将自动部署Elasticsearch和ES管理应用"
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查运行权限
if [ "$EUID" -ne 0 ]; then
    log_error "请使用root用户运行此脚本"
    exit 1
fi

echo "开始部署前检查..."

# 系统环境检查
log_info "检查系统环境..."

# 检查操作系统
OS=$(cat /etc/os-release | grep ^ID= | cut -d'=' -f2 | tr -d '"')
log_info "操作系统: $OS"

# 检查CPU架构
ARCH=$(uname -m)
case $ARCH in
    x86_64)
        ARCH_NAME="x86_64"
        ;;
    aarch64|arm64)
        ARCH_NAME="aarch64"
        log_info "检测到ARM64架构，将应用ARM优化配置"
        ;;
    armv7l)
        ARCH_NAME="armv7l"
        log_warning "检测到ARM32架构，性能可能受限"
        ;;
    *)
        log_warning "未知架构: $ARCH，可能存在兼容性问题"
        ARCH_NAME="unknown"
        ;;
esac
log_info "CPU架构: $ARCH ($ARCH_NAME)"

# 检查内存
MEMORY_GB=$(free -g | awk 'NR==2{print $2}')
log_info "系统内存: ${MEMORY_GB}GB"

# ARM架构内存要求调整
if [ "$ARCH_NAME" = "aarch64" ] || [ "$ARCH_NAME" = "armv7l" ]; then
    if [ $MEMORY_GB -lt 4 ]; then
        log_warning "ARM架构建议4GB以上内存，当前: ${MEMORY_GB}GB"
    fi
elif [ $MEMORY_GB -lt 8 ]; then
    log_warning "x86_64架构建议8GB以上内存，当前: ${MEMORY_GB}GB"
fi

# 检查磁盘空间
DISK_AVAILABLE=$(df / | awk 'NR==2{print $4}')
DISK_AVAILABLE_GB=$((DISK_AVAILABLE / 1024 / 1024))
log_info "可用磁盘空间: ${DISK_AVAILABLE_GB}GB"

if [ $DISK_AVAILABLE_GB -lt 50 ]; then
    log_error "磁盘空间不足50GB，无法继续部署"
    exit 1
fi

# 检查Java
log_info "检查Java环境..."
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | cut -d'"' -f2)
    log_success "Java已安装: $JAVA_VERSION"
    
    # 检查Java版本是否符合要求
    JAVA_MAJOR=$(echo $JAVA_VERSION | cut -d'.' -f1)
    if [ "$JAVA_MAJOR" -lt 11 ]; then
        log_warning "Java版本过低，Elasticsearch需要Java 11+"
    fi
else
    log_error "Java未安装"
    
    # 检查是否有离线Java安装包
    if [ -d "es_admin_runtime_offline" ] && [ -f "es_admin_runtime_offline/install_runtime.sh" ]; then
        log_info "发现离线运行环境，开始安装Java..."
        cd es_admin_runtime_offline
        ./install_runtime.sh
        cd ..
        log_success "Java离线安装完成"
    else
        log_info "尝试在线安装Java..."
        if [ "$OS" = "centos" ] || [ "$OS" = "rhel" ]; then
            yum install -y java-11-openjdk java-11-openjdk-devel
        elif [ "$OS" = "ubuntu" ] || [ "$OS" = "debian" ]; then
            apt update && apt install -y openjdk-11-jdk
        else
            log_error "不支持的操作系统，请先运行 ./download_offline_runtime.sh 准备离线安装包"
            exit 1
        fi
        log_success "Java在线安装完成"
    fi
fi

# 检查Python
log_info "检查Python环境..."
if command -v python3 &> /dev/null; then
    PYTHON_VERSION=$(python3 --version)
    log_success "Python已安装: $PYTHON_VERSION"
    
    # 检查Python版本
    PYTHON_MINOR=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
    if [[ $(echo "$PYTHON_MINOR < 3.8" | bc -l) -eq 1 ]]; then
        log_warning "Python版本过低，推荐使用Python 3.8+"
    fi
else
    log_error "Python3未安装"
    
    # 检查是否有离线Python安装包
    if [ -d "es_admin_runtime_offline" ] && [ -f "es_admin_runtime_offline/install_runtime.sh" ]; then
        log_info "使用离线运行环境安装Python..."
        # Java和Python会一起安装
        if [ ! -f "/tmp/.runtime_installed" ]; then
            cd es_admin_runtime_offline
            ./install_runtime.sh
            cd ..
            touch /tmp/.runtime_installed
        fi
        log_success "Python离线安装完成"
    else
        log_info "尝试在线安装Python..."
        if [ "$OS" = "centos" ] || [ "$OS" = "rhel" ]; then
            yum install -y python38 python38-pip || yum install -y python3 python3-pip
        elif [ "$OS" = "ubuntu" ] || [ "$OS" = "debian" ]; then
            apt update && apt install -y python3.8 python3.8-pip python3.8-dev || apt install -y python3 python3-pip
        else
            log_error "不支持的操作系统，请先运行 ./download_offline_runtime.sh 准备离线安装包"
            exit 1
        fi
        log_success "Python在线安装完成"
    fi
fi

# 检查必要文件
log_info "检查部署文件..."

# 根据架构检查对应的ES文件
case $ARCH_NAME in
    "x86_64")
        ES_FILE="elasticsearch-7.17.9-linux-x86_64.tar.gz"
        ;;
    "aarch64")
        ES_FILE="elasticsearch-7.17.9-linux-aarch64.tar.gz"
        ;;
    *)
        ES_FILE="elasticsearch-7.17.9-linux-x86_64.tar.gz"
        log_warning "未知架构，使用x86_64版本的ES（可能不兼容）"
        ;;
esac

REQUIRED_FILES=(
    "$ES_FILE"
    "install_elasticsearch.sh"
    "install_app.sh"
    "elasticsearch.yml"
    "jvm.options"
    ".env.template"
)

MISSING_FILES=()
for file in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$file" ]; then
        MISSING_FILES+=("$file")
    fi
done

if [ ${#MISSING_FILES[@]} -gt 0 ]; then
    log_error "缺少必要文件:"
    for file in "${MISSING_FILES[@]}"; do
        echo "  - $file"
    done
    exit 1
fi

log_success "所有必要文件检查完成"

# 询问用户确认
echo ""
echo "=== 部署配置确认 ==="
echo "操作系统: $OS"
echo "内存: ${MEMORY_GB}GB"
echo "可用磁盘: ${DISK_AVAILABLE_GB}GB"
echo ""
read -p "确认开始部署? (y/N): " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    log_info "部署已取消"
    exit 0
fi

# 开始部署
echo ""
echo "=== 开始部署 ==="

# 第一步：部署Elasticsearch
log_info "步骤 1/4: 部署Elasticsearch..."
./install_elasticsearch.sh
if [ $? -eq 0 ]; then
    log_success "Elasticsearch部署完成"
else
    log_error "Elasticsearch部署失败"
    exit 1
fi

# 等待ES启动
log_info "等待Elasticsearch完全启动..."
sleep 30

# 检查ES是否启动成功
ES_HEALTH=$(curl -s http://localhost:9200/_cluster/health 2>/dev/null | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
if [ "$ES_HEALTH" = "green" ] || [ "$ES_HEALTH" = "yellow" ]; then
    log_success "Elasticsearch启动成功"
else
    log_warning "Elasticsearch可能未完全启动，继续部署..."
fi

# 第二步：安装dmPython
log_info "步骤 2/4: 检查dmPython驱动..."
if [ -d "es_admin_offline_packages" ]; then
    DMPYTHON_FILE=$(find es_admin_offline_packages -name "dmPython*.whl" -o -name "dmPython*.tar.gz" | head -n1)
    if [ -n "$DMPYTHON_FILE" ]; then
        log_info "安装dmPython驱动..."
        pip3 install "$DMPYTHON_FILE"
        log_success "dmPython驱动安装完成"
    else
        log_warning "未找到dmPython驱动文件，请手动安装"
    fi
else
    log_warning "未找到离线依赖包，dmPython需要手动安装"
fi

# 第三步：部署应用
log_info "步骤 3/4: 部署ES管理应用..."
./install_app.sh
if [ $? -eq 0 ]; then
    log_success "ES管理应用部署完成"
else
    log_error "ES管理应用部署失败"
    exit 1
fi

# 第四步：配置检查和启动
log_info "步骤 4/4: 最终配置..."

# 检查配置文件
if [ ! -f "/opt/es_admin/.env" ]; then
    log_warning "配置文件不存在，创建默认配置..."
    cp .env.template /opt/es_admin/.env
fi

# 性能优化配置（根据架构和内存）
log_info "应用性能配置优化..."

# ARM架构性能调优
if [ "$ARCH_NAME" = "aarch64" ] || [ "$ARCH_NAME" = "armv7l" ]; then
    log_info "应用ARM架构优化配置..."
    
    if [ $MEMORY_GB -ge 8 ]; then
        # ARM 8GB+ 内存配置
        sed -i 's/ULTRA_FAST_WORKERS=.*/ULTRA_FAST_WORKERS=8/' /opt/es_admin/.env
        sed -i 's/DM_POOL_SIZE=.*/DM_POOL_SIZE=12/' /opt/es_admin/.env
        sed -i 's/ES_BULK_SIZE=.*/ES_BULK_SIZE=2000/' /opt/es_admin/.env
        log_info "已应用ARM高性能配置（8GB+内存）"
    elif [ $MEMORY_GB -ge 4 ]; then
        # ARM 4GB+ 内存配置
        sed -i 's/ULTRA_FAST_WORKERS=.*/ULTRA_FAST_WORKERS=4/' /opt/es_admin/.env
        sed -i 's/DM_POOL_SIZE=.*/DM_POOL_SIZE=8/' /opt/es_admin/.env
        sed -i 's/ES_BULK_SIZE=.*/ES_BULK_SIZE=1500/' /opt/es_admin/.env
        log_info "已应用ARM标准配置（4GB+内存）"
    else
        # ARM 小内存配置
        sed -i 's/ULTRA_FAST_WORKERS=.*/ULTRA_FAST_WORKERS=2/' /opt/es_admin/.env
        sed -i 's/DM_POOL_SIZE=.*/DM_POOL_SIZE=5/' /opt/es_admin/.env
        sed -i 's/ES_BULK_SIZE=.*/ES_BULK_SIZE=1000/' /opt/es_admin/.env
        log_info "已应用ARM保守配置（小内存）"
    fi
    
    # ARM架构特殊优化
    echo "# ARM架构优化配置" >> /opt/es_admin/.env
    echo "ARM_OPTIMIZED=true" >> /opt/es_admin/.env
    
else
    # x86_64架构配置
    if [ $MEMORY_GB -ge 16 ]; then
        # 16GB+ 内存高性能配置
        sed -i 's/ULTRA_FAST_WORKERS=.*/ULTRA_FAST_WORKERS=16/' /opt/es_admin/.env
        sed -i 's/DM_POOL_SIZE=.*/DM_POOL_SIZE=20/' /opt/es_admin/.env
        sed -i 's/ES_BULK_SIZE=.*/ES_BULK_SIZE=3000/' /opt/es_admin/.env
        log_info "已应用x86_64高性能配置（16GB+内存）"
    elif [ $MEMORY_GB -ge 8 ]; then
        # 8GB+ 内存标准配置
        sed -i 's/ULTRA_FAST_WORKERS=.*/ULTRA_FAST_WORKERS=8/' /opt/es_admin/.env
        sed -i 's/DM_POOL_SIZE=.*/DM_POOL_SIZE=10/' /opt/es_admin/.env
        sed -i 's/ES_BULK_SIZE=.*/ES_BULK_SIZE=2000/' /opt/es_admin/.env
        log_info "已应用x86_64标准配置（8GB+内存）"
    else
        # 小内存保守配置
        sed -i 's/ULTRA_FAST_WORKERS=.*/ULTRA_FAST_WORKERS=4/' /opt/es_admin/.env
        sed -i 's/DM_POOL_SIZE=.*/DM_POOL_SIZE=5/' /opt/es_admin/.env
        sed -i 's/ES_BULK_SIZE=.*/ES_BULK_SIZE=1000/' /opt/es_admin/.env
        log_info "已应用x86_64保守配置（小内存）"
    fi
fi

# 启动应用服务
log_info "启动ES管理服务..."
systemctl start es_admin
sleep 10

# 设置成员索引
log_info "设置成员索引..."
cd /opt/es_admin
if python3 setup_member_index.py; then
    log_success "成员索引设置完成"
else
    log_warning "成员索引设置失败，请手动运行: cd /opt/es_admin && python3 setup_member_index.py"
fi
cd - > /dev/null

# 部署完成检查
echo ""
echo "=== 部署完成检查 ==="

# 检查Elasticsearch
ES_STATUS=$(systemctl is-active elasticsearch)
if [ "$ES_STATUS" = "active" ]; then
    log_success "✓ Elasticsearch服务运行正常"
else
    log_error "✗ Elasticsearch服务异常"
fi

# 检查应用服务
APP_STATUS=$(systemctl is-active es_admin)
if [ "$APP_STATUS" = "active" ]; then
    log_success "✓ ES管理服务运行正常"
else
    log_error "✗ ES管理服务异常"
fi

# 检查应用API
if curl -s http://localhost:5000/api/sync/status > /dev/null; then
    log_success "✓ 应用API响应正常"
else
    log_warning "⚠ 应用API可能未准备就绪"
fi

echo ""
echo "=== 部署完成 ==="
log_success "ES管理服务部署完成！"
echo ""
echo "下一步配置："
echo "1. 编辑配置文件: vi /opt/es_admin/.env"
echo "   - 配置达梦数据库连接信息"
echo "   - 配置Elasticsearch认证信息"
echo ""
echo "2. 重启服务: systemctl restart es_admin"
echo ""
echo "3. 访问应用: http://$(hostname -I | awk '{print $1}'):5000"
echo ""
echo "超高性能API接口："
echo "  异步超高性能同步: POST /api/sync/async/sync/all"
echo "  查看同步状态: GET /api/sync/async/status"
echo "  超高性能搜索: GET /api/search/ultra?q=关键词"
echo ""
echo "常用命令："
echo "  查看服务状态: systemctl status es_admin"
echo "  查看应用日志: journalctl -u es_admin -f"
echo "  查看ES日志:   tail -f /var/log/elasticsearch/es-admin-cluster.log"
echo ""
echo "API测试："
echo "  curl http://localhost:5000/api/sync/forms"
echo "  curl http://localhost:5000/api/sync/status"
echo ""

# 显示重要提醒
log_warning "重要提醒："
echo "1. 请立即修改Elasticsearch的默认密码"
echo "2. 请配置 /opt/es_admin/.env 中的数据库连接信息"
echo "3. 建议配置防火墙规则限制访问"
echo "4. 定期备份配置文件和数据"