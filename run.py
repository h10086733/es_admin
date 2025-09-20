#!/usr/bin/env python3
"""
ES数据管理系统启动脚本
"""
import os
import sys

# 添加项目根目录到Python路径
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app import create_app

if __name__ == '__main__':
    app = create_app()
    
    # 获取配置
    host = os.getenv('FLASK_HOST', '0.0.0.0')
    port = int(os.getenv('FLASK_PORT', 5000))
    debug = os.getenv('FLASK_DEBUG', 'True').lower() == 'true'
    
    print(f"启动ES数据管理系统...")
    print(f"访问地址: http://{host}:{port}")
    print(f"调试模式: {debug}")
    
    app.run(host=host, port=port, debug=debug)