from flask import Flask, send_from_directory
from flask_cors import CORS
from dotenv import load_dotenv
import os

def create_app():
    load_dotenv()
    
    # 设置静态文件目录
    app = Flask(__name__, static_folder='../static', static_url_path='/static')
    app.config['SECRET_KEY'] = os.getenv('SECRET_KEY', 'dev-secret-key')
    
    CORS(app)
    
    # 注册蓝图
    from app.views.sync_views import sync_bp
    from app.views.search_views import search_bp
    
    app.register_blueprint(sync_bp, url_prefix='/api/sync')
    app.register_blueprint(search_bp, url_prefix='/api/search')
    
    # 注册首页路由
    @app.route('/')
    def index():
        return send_from_directory(app.static_folder, 'index.html')
    
    return app