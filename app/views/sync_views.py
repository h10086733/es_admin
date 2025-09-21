from flask import Blueprint, request, jsonify
from app.services.sync_service import SyncService
from app.services.fast_sync_service import FastSyncService
from app.services.ultra_fast_sync_service import UltraFastSyncService
from app.services.member_sync_service import MemberSyncService
from app.services.unified_search_service import UnifiedSearchService
from app.services.async_sync_service import get_async_sync_service
from app.services.async_ultra_sync_service import get_async_ultra_sync_service
from app.models.form_model import FormModel

sync_bp = Blueprint('sync', __name__)
sync_service = SyncService()
fast_sync_service = FastSyncService(max_workers=8)
ultra_fast_sync_service = UltraFastSyncService(max_workers=16)
member_sync_service = MemberSyncService()
unified_search_service = UnifiedSearchService()

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

@sync_bp.route('/sync/fast/<form_id>', methods=['POST'])
def sync_form_fast(form_id):
    """高性能同步指定表单数据"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        result = fast_sync_service.sync_form_data_parallel(form_id, full_sync)
        
        if result['success']:
            return jsonify(result)
        else:
            return jsonify(result), 400
            
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"高性能同步失败: {str(e)}"
        }), 500

@sync_bp.route('/sync/fast/all', methods=['POST'])
def sync_all_fast():
    """高性能同步所有表单数据"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        results = fast_sync_service.sync_all_forms_parallel(full_sync)
        
        return jsonify({
            "success": True,
            "results": results,
            "optimization": "并行处理 + 成员缓存 + ES优化"
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"高性能同步失败: {str(e)}"
        }), 500

@sync_bp.route('/sync/ultra/<form_id>', methods=['POST'])
def sync_form_ultra(form_id):
    """超高性能同步指定表单数据 - 适用于大数据量"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        result = ultra_fast_sync_service.ultra_fast_sync_form_data(form_id, full_sync)
        
        if result['success']:
            return jsonify(result)
        else:
            return jsonify(result), 400
            
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"超高性能同步失败: {str(e)}"
        }), 500

@sync_bp.route('/search/ultra', methods=['GET'])
def search_ultra():
    """超高性能搜索 - 适用于大数据量查询"""
    try:
        query = request.args.get('q', '').strip()
        if not query:
            return jsonify({
                "success": False,
                "message": "搜索关键词不能为空"
            }), 400
        
        form_ids = request.args.getlist('form_ids')
        size = int(request.args.get('size', 20))
        from_ = int(request.args.get('from', 0))
        
        result = ultra_fast_sync_service.ultra_fast_search(
            query=query,
            form_ids=form_ids if form_ids else None,
            size=size,
            from_=from_
        )
        
        return jsonify({
            "success": True,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"超高性能搜索失败: {str(e)}"
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

@sync_bp.route('/members/sync', methods=['POST'])
def sync_members():
    """同步人员数据到ES"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        result = member_sync_service.sync_members_to_es(full_sync)
        
        if result['success']:
            return jsonify(result)
        else:
            return jsonify(result), 400
            
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"人员同步失败: {str(e)}"
        }), 500

@sync_bp.route('/search/unified', methods=['GET'])
def unified_search():
    """统一搜索：同时搜索表单数据和人员信息"""
    try:
        query = request.args.get('q', '').strip()
        if not query:
            return jsonify({
                "success": False,
                "message": "搜索关键词不能为空"
            }), 400
        
        form_ids = request.args.getlist('form_ids')
        include_members = request.args.get('include_members', 'true').lower() == 'true'
        size = int(request.args.get('size', 20))
        from_ = int(request.args.get('from', 0))
        
        result = unified_search_service.unified_search(
            query=query,
            form_ids=form_ids if form_ids else None,
            include_members=include_members,
            size=size,
            from_=from_
        )
        
        return jsonify({
            "success": True,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"统一搜索失败: {str(e)}"
        }), 500

@sync_bp.route('/search/members', methods=['GET'])
def search_members():
    """搜索人员信息"""
    try:
        query = request.args.get('q', '').strip()
        if not query:
            return jsonify({
                "success": False,
                "message": "搜索关键词不能为空"
            }), 400
        
        size = int(request.args.get('size', 20))
        
        result = member_sync_service.search_members(query, size)
        
        return jsonify({
            "success": True,
            "data": result
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"人员搜索失败: {str(e)}"
        }), 500

@sync_bp.route('/search/suggest', methods=['GET'])
def search_suggest():
    """搜索建议"""
    try:
        query = request.args.get('q', '').strip()
        size = int(request.args.get('size', 5))
        
        suggestions = unified_search_service.suggest_search_terms(query, size)
        
        return jsonify({
            "success": True,
            "data": suggestions
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"获取搜索建议失败: {str(e)}"
        }), 500
# 异步超高性能同步相关API

@sync_bp.route('/async/ultra/sync/all', methods=['POST'])
def async_ultra_sync_all():
    """异步超高性能同步所有表单数据 - 适用于大数据量"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        async_ultra_service = get_async_ultra_sync_service()
        result = async_ultra_service.start_async_ultra_sync_all(full_sync)
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"启动异步超高性能同步失败: {str(e)}"
        })

@sync_bp.route('/async/ultra/sync/<form_id>', methods=['POST'])
def async_ultra_sync_single(form_id):
    """异步超高性能同步单个表单 - 适用于大数据量"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', False)
        
        async_ultra_service = get_async_ultra_sync_service()
        result = async_ultra_service.start_async_ultra_sync_single(form_id, full_sync)
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"启动异步超高性能同步失败: {str(e)}"
        })

@sync_bp.route('/async/ultra/status', methods=['GET'])
@sync_bp.route('/async/ultra/status/<task_id>', methods=['GET'])
def get_async_ultra_sync_status(task_id=None):
    """获取异步超高性能同步任务状态"""
    try:
        async_ultra_service = get_async_ultra_sync_service()
        status = async_ultra_service.get_task_status(task_id)
        
        return jsonify({
            "success": True,
            "data": status
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"获取超高性能任务状态失败: {str(e)}"
        })

# 异步同步相关API

@sync_bp.route('/async/sync/all', methods=['POST'])
def async_sync_all():
    """异步同步所有表单数据"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', True)
        
        async_service = get_async_sync_service()
        result = async_service.start_async_sync_all(full_sync)
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"启动异步同步失败: {str(e)}"
        })

@sync_bp.route('/async/sync/<form_id>', methods=['POST'])
def async_sync_single(form_id):
    """异步同步单个表单"""
    try:
        data = request.get_json() or {}
        full_sync = data.get('full_sync', False)
        
        async_service = get_async_sync_service()
        result = async_service.start_async_sync_single(form_id, full_sync)
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"启动异步同步失败: {str(e)}"
        })

@sync_bp.route('/async/status', methods=['GET'])
@sync_bp.route('/async/status/<task_id>', methods=['GET'])
def get_async_sync_status(task_id=None):
    """获取异步同步任务状态"""
    try:
        async_service = get_async_sync_service()
        status = async_service.get_task_status(task_id)
        
        return jsonify({
            "success": True,
            "data": status
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"获取任务状态失败: {str(e)}"
        })
