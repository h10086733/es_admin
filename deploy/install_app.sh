#!/bin/bash
# ES管理服务应用安装脚本

set -e

echo "=== ES管理服务应用安装脚本 ==="

# 配置变量
APP_NAME="es_admin"
APP_USER="esadmin"
APP_HOME="/opt/es_admin"
APP_LOG_DIR="/var/log/es_admin"
PYTHON_VERSION="python3"

# 检查是否为root用户
if [ "$EUID" -ne 0 ]; then
    echo "请使用root用户运行此脚本"
    exit 1
fi

echo "1. 检查系统环境..."

# 检查Python环境
if ! command -v $PYTHON_VERSION &> /dev/null; then
    echo "错误: 未找到 $PYTHON_VERSION"
    echo "请先安装Python 3.7+"
    exit 1
fi

PYTHON_VER=$($PYTHON_VERSION --version)
echo "Python版本: $PYTHON_VER"

# 检查pip
if ! command -v pip3 &> /dev/null; then
    echo "错误: 未找到pip3，请先安装pip"
    exit 1
fi

echo "2. 创建应用用户..."
if ! id "$APP_USER" &>/dev/null; then
    useradd -r -s /bin/bash -m $APP_USER
    echo "创建用户: $APP_USER"
else
    echo "用户已存在: $APP_USER"
fi

echo "3. 创建目录结构..."
mkdir -p $APP_HOME
mkdir -p $APP_LOG_DIR
mkdir -p /etc/$APP_NAME

echo "4. 检查应用源码..."
if [ ! -d "../app" ] || [ ! -f "../app.py" ]; then
    echo "错误: 未找到应用源码"
    echo "请确保在es_admin项目的deploy目录中运行此脚本"
    exit 1
fi

echo "5. 复制应用文件..."
cp -r ../* $APP_HOME/
# 排除部署目录避免循环复制
rm -rf $APP_HOME/deploy

echo "6. 检查离线依赖包..."
if [ -d "es_admin_offline_packages" ]; then
    echo "找到离线依赖包，开始安装..."
    cd es_admin_offline_packages
    
    # 安装Python依赖
    pip3 install --no-index --find-links . *.whl *.tar.gz
    
    cd ..
    echo "✓ Python依赖安装完成"
else
    echo "未找到离线依赖包目录，尝试在线安装..."
    pip3 install -r $APP_HOME/requirements.txt
fi

echo "7. 检查dmPython驱动..."
python3 -c "import dmPython; print('dmPython已安装')" 2>/dev/null || {
    echo "⚠ dmPython未安装，请手动安装达梦数据库驱动"
    echo "参考: es_admin_offline_packages/dmPython_README.md"
}

echo "8. 创建配置文件..."
if [ -f ".env.template" ]; then
    cp .env.template $APP_HOME/.env
    echo "✓ 已创建配置文件模板: $APP_HOME/.env"
    echo "⚠ 请编辑 $APP_HOME/.env 文件，配置数据库和ES连接信息"
else
    echo "⚠ 未找到配置模板文件"
fi

echo "9. 设置目录权限..."
chown -R $APP_USER:$APP_USER $APP_HOME
chown -R $APP_USER:$APP_USER $APP_LOG_DIR
chmod +x $APP_HOME/app.py

echo "10. 创建systemd服务..."
cat > /etc/systemd/system/$APP_NAME.service << EOF
[Unit]
Description=ES Admin Service
After=network.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_HOME
Environment=PATH=/usr/bin:/usr/local/bin
Environment=PYTHONPATH=$APP_HOME
ExecStart=/usr/bin/python3 $APP_HOME/app.py
Restart=always
RestartSec=10

# 日志配置
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$APP_NAME

# 安全配置
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$APP_LOG_DIR /tmp

[Install]
WantedBy=multi-user.target
EOF

echo "11. 启用服务..."
systemctl daemon-reload
systemctl enable $APP_NAME

echo "12. 创建nginx配置（可选）..."
cat > /etc/nginx/sites-available/$APP_NAME << 'EOF'
server {
    listen 80;
    server_name your-domain.com;  # 修改为您的域名

    location / {
        proxy_pass http://127.0.0.1:5000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # 超时设置
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
        
        # 缓冲设置
        proxy_buffering on;
        proxy_buffer_size 8k;
        proxy_buffers 8 8k;
        proxy_busy_buffers_size 16k;
    }
    
    # 静态文件
    location /static/ {
        alias /opt/es_admin/static/;
        expires 1d;
        add_header Cache-Control "public, immutable";
    }
}
EOF

echo ""
echo "=== 应用安装完成 ==="
echo ""
echo "下一步配置："
echo "1. 编辑配置文件: vi $APP_HOME/.env"
echo "2. 启动服务: systemctl start $APP_NAME"
echo "3. 设置成员索引: cd $APP_HOME && python3 setup_member_index.py"
echo "4. 查看状态: systemctl status $APP_NAME"
echo "5. 查看日志: journalctl -u $APP_NAME -f"
echo ""
echo "可选配置："
echo "1. 配置nginx反向代理: ln -s /etc/nginx/sites-available/$APP_NAME /etc/nginx/sites-enabled/"
echo "2. 重启nginx: systemctl restart nginx"
echo ""
echo "应用访问地址: http://服务器IP:5000"
echo ""
echo "重要提醒："
echo "启动服务后，请务必运行成员索引设置脚本以避免同步错误！"