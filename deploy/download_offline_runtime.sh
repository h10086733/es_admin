#!/bin/bash
# Java和Python离线运行环境下载脚本 - 支持ARM架构
# 在有网络的环境中运行，为内网部署准备完整的运行环境

set -e

echo "=== Java和Python离线运行环境下载脚本 (支持ARM架构) ==="

# 创建离线运行环境目录
RUNTIME_DIR="es_admin_runtime_offline"
mkdir -p $RUNTIME_DIR/{java,python,packages}

echo "创建离线运行环境目录: $RUNTIME_DIR"

# 检测当前操作系统和架构
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
    VERSION=$VERSION_ID
else
    echo "无法检测操作系统版本"
    exit 1
fi

# 检测CPU架构
ARCH=$(uname -m)
case $ARCH in
    x86_64)
        ARCH_NAME="x64"
        ARCH_SUFFIX="x86_64"
        ;;
    aarch64|arm64)
        ARCH_NAME="aarch64"
        ARCH_SUFFIX="aarch64"
        ;;
    armv7l)
        ARCH_NAME="arm32"
        ARCH_SUFFIX="armv7l"
        ;;
    *)
        echo "不支持的CPU架构: $ARCH"
        exit 1
        ;;
esac

echo "检测到操作系统: $OS $VERSION"
echo "检测到CPU架构: $ARCH ($ARCH_NAME)"

# ================================= Java 下载 =================================
echo ""
echo "=== 下载Java运行环境 ($ARCH_NAME) ==="

JAVA_VERSION="11.0.21"
JAVA_BUILD="9"

# 根据架构选择下载链接
case $ARCH_NAME in
    "x64")
        JAVA_DOWNLOAD_URL="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.21%2B9/OpenJDK11U-jdk_x64_linux_hotspot_11.0.21_9.tar.gz"
        JAVA_FILENAME="openjdk-11-linux-x64.tar.gz"
        JAVA_DIR_NAME="jdk-11.0.21+9"
        ;;
    "aarch64")
        JAVA_DOWNLOAD_URL="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.21%2B9/OpenJDK11U-jdk_aarch64_linux_hotspot_11.0.21_9.tar.gz"
        JAVA_FILENAME="openjdk-11-linux-aarch64.tar.gz"
        JAVA_DIR_NAME="jdk-11.0.21+9"
        ;;
    "arm32")
        JAVA_DOWNLOAD_URL="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.21%2B9/OpenJDK11U-jdk_arm_linux_hotspot_11.0.21_9.tar.gz"
        JAVA_FILENAME="openjdk-11-linux-arm32.tar.gz"
        JAVA_DIR_NAME="jdk-11.0.21+9"
        ;;
esac

cd $RUNTIME_DIR/java

echo "下载OpenJDK 11 for $ARCH_NAME..."
wget -O $JAVA_FILENAME $JAVA_DOWNLOAD_URL

# 创建通用Java安装脚本
cat > install_java.sh << EOF
#!/bin/bash
echo "安装Java 11 ($ARCH_NAME)..."

JAVA_FILENAME="$JAVA_FILENAME"
JAVA_DIR_NAME="$JAVA_DIR_NAME"
ARCH_NAME="$ARCH_NAME"

# 检测操作系统
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=\$ID
else
    echo "无法检测操作系统"
    exit 1
fi

# 方法1：使用系统包管理器（如果可用）
echo "尝试使用系统包管理器安装Java..."
case \$OS in
    "centos"|"rhel"|"fedora")
        if command -v yum &> /dev/null; then
            yum install -y java-11-openjdk java-11-openjdk-devel && exit 0
        elif command -v dnf &> /dev/null; then
            dnf install -y java-11-openjdk java-11-openjdk-devel && exit 0
        fi
        ;;
    "ubuntu"|"debian")
        apt update && apt install -y openjdk-11-jdk && exit 0
        ;;
esac

# 方法2：使用下载的压缩包
if [ -f "\$JAVA_FILENAME" ]; then
    echo "使用离线Java包安装 (\$ARCH_NAME)..."
    
    # 解压到/opt目录
    tar -xzf \$JAVA_FILENAME -C /opt/
    JAVA_HOME=/opt/\$JAVA_DIR_NAME
    
    # 设置环境变量
    echo "export JAVA_HOME=\$JAVA_HOME" >> /etc/profile
    echo "export PATH=\\\$JAVA_HOME/bin:\\\$PATH" >> /etc/profile
    
    # 创建符号链接
    ln -sf \$JAVA_HOME/bin/java /usr/bin/java
    ln -sf \$JAVA_HOME/bin/javac /usr/bin/javac
    
    # 加载环境变量
    source /etc/profile
    
    echo "Java安装完成"
else
    echo "错误: 未找到Java安装包 \$JAVA_FILENAME"
    exit 1
fi

# 验证安装
java -version
EOF

chmod +x install_java.sh
cd - > /dev/null

# ================================= Python 下载 ===============================
echo ""
echo "=== 下载Python运行环境 ($ARCH_NAME) ==="

PYTHON_VERSION="3.8.18"

cd $RUNTIME_DIR/python

echo "下载Python ${PYTHON_VERSION}源码..."
wget -O Python-${PYTHON_VERSION}.tgz \
    "https://www.python.org/ftp/python/${PYTHON_VERSION}/Python-${PYTHON_VERSION}.tgz"

# 创建Python安装脚本
cat > install_python.sh << EOF
#!/bin/bash
echo "安装Python 3.8 ($ARCH_NAME)..."

PYTHON_VERSION="$PYTHON_VERSION"
ARCH_NAME="$ARCH_NAME"

# 检测操作系统
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=\$ID
else
    echo "无法检测操作系统"
    exit 1
fi

# 方法1：使用系统包管理器（如果可用）
echo "尝试使用系统包管理器安装Python..."
case \$OS in
    "centos"|"rhel"|"fedora")
        if command -v yum &> /dev/null; then
            # 安装EPEL仓库
            yum install -y epel-release
            yum install -y python38 python38-pip python38-devel && \
            ln -sf /usr/bin/python3.8 /usr/bin/python3 && \
            ln -sf /usr/bin/pip3.8 /usr/bin/pip3 && exit 0
        elif command -v dnf &> /dev/null; then
            dnf install -y python38 python38-pip python38-devel && \
            ln -sf /usr/bin/python3.8 /usr/bin/python3 && \
            ln -sf /usr/bin/pip3.8 /usr/bin/pip3 && exit 0
        fi
        ;;
    "ubuntu"|"debian")
        apt update && apt install -y python3.8 python3.8-pip python3.8-dev python3.8-venv && \
        ln -sf /usr/bin/python3.8 /usr/bin/python3 && \
        ln -sf /usr/bin/pip3.8 /usr/bin/pip3 && exit 0
        ;;
esac

# 方法2：源码编译安装
if [ -f "Python-\${PYTHON_VERSION}.tgz" ]; then
    echo "使用源码编译安装Python (\$ARCH_NAME)..."
    
    # 安装编译依赖
    case \$OS in
        "centos"|"rhel"|"fedora")
            if command -v yum &> /dev/null; then
                yum groupinstall -y "Development Tools"
                yum install -y zlib-devel bzip2-devel openssl-devel ncurses-devel \\
                               sqlite-devel readline-devel tk-devel gdbm-devel \\
                               db4-devel libpcap-devel xz-devel expat-devel \\
                               libffi-devel
            elif command -v dnf &> /dev/null; then
                dnf groupinstall -y "Development Tools"
                dnf install -y zlib-devel bzip2-devel openssl-devel ncurses-devel \\
                               sqlite-devel readline-devel tk-devel gdbm-devel \\
                               libdb-devel libpcap-devel xz-devel expat-devel \\
                               libffi-devel
            fi
            ;;
        "ubuntu"|"debian")
            apt update
            apt install -y build-essential zlib1g-dev libncurses5-dev libgdbm-dev \\
                           libnss3-dev libssl-dev libreadline-dev libffi-dev \\
                           libsqlite3-dev wget libbz2-dev
            ;;
    esac
    
    # 解压和编译
    tar -xzf Python-\${PYTHON_VERSION}.tgz
    cd Python-\${PYTHON_VERSION}
    
    # 配置编译选项，针对ARM架构优化
    CONFIG_ARGS="--prefix=/usr/local --enable-optimizations --with-ensurepip=install"
    
    # ARM架构特殊配置
    if [ "\$ARCH_NAME" = "aarch64" ] || [ "\$ARCH_NAME" = "arm32" ]; then
        CONFIG_ARGS="\$CONFIG_ARGS --build=\$(./config.guess)"
        echo "ARM架构编译配置: \$CONFIG_ARGS"
    fi
    
    ./configure \$CONFIG_ARGS
    
    # 根据CPU核心数并行编译
    NPROC=\$(nproc)
    if [ \$NPROC -gt 4 ]; then
        NPROC=4  # 限制并行数避免内存不足
    fi
    
    make -j\$NPROC
    make altinstall
    
    # 创建符号链接
    ln -sf /usr/local/bin/python3.8 /usr/bin/python3
    ln -sf /usr/local/bin/pip3.8 /usr/bin/pip3
    
    cd ..
    echo "Python编译安装完成"
else
    echo "错误: 未找到Python源码包"
    exit 1
fi

# 验证安装
python3 --version
pip3 --version
EOF

chmod +x install_python.sh
cd - > /dev/null

# ================================= 创建通用安装脚本 ==========================
echo ""
echo "创建通用安装脚本..."

cat > $RUNTIME_DIR/install_runtime.sh << EOF
#!/bin/bash
# Java和Python运行环境通用安装脚本 - 支持ARM架构

set -e

echo "=== Java和Python运行环境安装脚本 (支持ARM架构) ==="

# 检测操作系统和架构
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=\$ID
else
    echo "无法检测操作系统"
    exit 1
fi

ARCH=\$(uname -m)
case \$ARCH in
    x86_64)
        ARCH_NAME="x64"
        ;;
    aarch64|arm64)
        ARCH_NAME="aarch64"
        ;;
    armv7l)
        ARCH_NAME="arm32"
        ;;
    *)
        echo "不支持的CPU架构: \$ARCH"
        exit 1
        ;;
esac

echo "操作系统: \$OS"
echo "CPU架构: \$ARCH (\$ARCH_NAME)"

# 检查权限
if [ "\$EUID" -ne 0 ]; then
    echo "请使用root用户运行此脚本"
    exit 1
fi

echo "1. 安装Java 11..."
if [ -d "java" ] && [ -f "java/install_java.sh" ]; then
    cd java && ./install_java.sh && cd ..
else
    echo "错误: 未找到Java安装文件"
    exit 1
fi

echo "2. 安装Python 3.8..."
if [ -d "python" ] && [ -f "python/install_python.sh" ]; then
    cd python && ./install_python.sh && cd ..
else
    echo "错误: 未找到Python安装文件"
    exit 1
fi

echo "3. 验证安装..."
echo "Java版本:"
java -version

echo "Python版本:"
python3 --version
pip3 --version

echo "运行环境安装完成！"
echo "架构: \$ARCH (\$ARCH_NAME)"
EOF

chmod +x $RUNTIME_DIR/install_runtime.sh

# ================================= 创建ARM优化的README ======================
cat > $RUNTIME_DIR/README_ARM.md << 'EOF'
# Java和Python离线运行环境 - ARM架构支持

## 支持的架构
- **x86_64**: Intel/AMD 64位处理器
- **aarch64**: ARM 64位处理器（如树莓派4、服务器ARM芯片）
- **armv7l**: ARM 32位处理器（如树莓派3）

## ARM架构特别说明

### 1. 性能考虑
- ARM处理器编译时间较长，请耐心等待
- 建议在ARM设备上至少预留2-4小时编译时间
- 内存建议4GB以上，避免编译时内存不足

### 2. 编译优化
- 自动检测ARM架构并应用相应编译参数
- 限制并行编译数量避免资源耗尽
- 包含ARM架构特殊的configure参数

### 3. 常见ARM设备支持
- **树莓派4 (aarch64)**: 完全支持
- **树莓派3 (armv7l)**: 支持，编译时间较长
- **ARM服务器**: 如华为鲲鹏、飞腾等
- **苹果M1/M2**: 通过Rosetta 2或原生aarch64

### 4. 安装建议
```bash
# 检查架构
uname -m

# 增加交换文件（如果内存<4GB）
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 运行安装
sudo ./install_runtime.sh
```

### 5. 故障排除
1. **编译失败**: 检查磁盘空间和内存
2. **依赖缺失**: 安装build-essential或Development Tools
3. **网络问题**: 某些依赖需要在线下载

## 性能对比参考
| 架构 | 编译时间 | 运行性能 | 推荐内存 |
|------|----------|----------|----------|
| x86_64 | 10-20分钟 | 100% | 4GB+ |
| aarch64 | 30-60分钟 | 85-95% | 4GB+ |
| armv7l | 60-120分钟 | 70-80% | 2GB+ |

注意：性能数据仅供参考，实际性能取决于具体硬件配置。
EOF

echo ""
echo "=== ARM架构离线运行环境准备完成 ==="
echo "运行环境目录: $RUNTIME_DIR"
echo "当前架构: $ARCH ($ARCH_NAME)"
echo ""
echo "包含文件："
find $RUNTIME_DIR -type f | sort
echo ""
echo "ARM架构说明文档: $RUNTIME_DIR/README_ARM.md"
echo ""
echo "传输到内网后运行: sudo ./install_runtime.sh"
echo ""
echo "ARM架构注意事项："
echo "1. 编译时间较长，请耐心等待"
echo "2. 建议4GB以上内存"
echo "3. 确保足够的磁盘空间"
echo "4. 可能需要在线下载一些编译依赖"