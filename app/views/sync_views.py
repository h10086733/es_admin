from flask import Blueprint, request, jsonify
from app.services.sync_service import SyncService
from app.models.form_model import FormModel

sync_bp = Blueprint('sync', __name__)
sync_service = SyncService()

@sync_bp.route('/forms', methods=['GET'])
def get_forms():
    """获取表单列表（支持分页）"""
    try:
        page = int(request.args.get('page', 1))
        page_size = int(request.args.get('page_size', 10))
        search = request.args.get('search', '').strip()
        
        all_forms = FormModel.get_all_forms()
        
        # 搜索过滤
        if search:
            filtered_forms = [form for form in all_forms if search.lower() in form['name'].lower()]
        else:
            filtered_forms = all_forms
        
        total = len(filtered_forms)
        total_pages = (total + page_size - 1) // page_size
        
        # 分页
        start = (page - 1) * page_size
        end = start + page_size
        forms = filtered_forms[start:end]
        
        return jsonify({
            "success": True,
            "data": forms,
            "pagination": {
                "page": page,
                "page_size": page_size,
                "total": total,
                "total_pages": total_pages,
                "has_next": page < total_pages,
                "has_prev": page > 1
            }
        })
    except Exception as e:
        return jsonify({
            "success": False,
            "message": str(e)
        }), 500

@sync_bp.route('/sync/<form_id>', methods=['POST'])
def sync_form(form_id):
    """同步指定表单数据"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        result = sync_service.sync_form_data(form_id, full_sync)
        
        if result['success']:
            return jsonify(result)
        else:
            return jsonify(result), 400
            
    except Exception as e:
        return jsonify({
            "success": False,
            "message": str(e)
        }), 500

@sync_bp.route('/sync/all', methods=['POST'])
def sync_all():
    """同步所有表单数据"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        results = sync_service.sync_all_forms(full_sync)
        
        return jsonify({
            "success": True,
            "results": results
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": str(e)
        }), 500

@sync_bp.route('/status', methods=['GET'])
def sync_status():
    """获取同步状态"""
    try:
        # 这里可以添加同步状态检查逻辑
        return jsonify({
            "success": True,
            "status": "ready",
            "message": "同步服务运行正常"
        })
    except Exception as e:
        return jsonify({
            "success": False,
            "message": str(e)
        }), 500