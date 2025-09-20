from flask import Blueprint, request, jsonify, render_template
from app.services.sync_service import SyncService

search_bp = Blueprint('search', __name__)
sync_service = SyncService()

@search_bp.route('/search', methods=['POST'])
def search():
    """搜索接口"""
    try:
        data = request.get_json()
        if not data:
            return jsonify({
                "success": False,
                "message": "请求数据为空"
            }), 400
        
        query = data.get('query', '').strip()
        if not query:
            return jsonify({
                "success": False,
                "message": "搜索关键词不能为空"
            }), 400
        
        form_ids = data.get('form_ids')  # 可选：指定搜索的表单ID列表
        size = data.get('size', 10)  # 返回结果数量
        from_ = data.get('from', 0)  # 分页偏移
        
        # 执行搜索
        result = sync_service.search_data(
            query=query,
            form_ids=form_ids,
            size=size,
            from_=from_
        )
        
        return jsonify({
            "success": True,
            "data": {
                "query": query,
                "hits": result["hits"],
                "total": result["total"],
                "max_score": result.get("max_score"),
                "size": size,
                "from": from_
            }
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": str(e)
        }), 500

@search_bp.route('/suggest', methods=['POST'])
def suggest():
    """搜索建议接口"""
    try:
        data = request.get_json()
        query = data.get('query', '').strip()
        
        if len(query) < 2:
            return jsonify({
                "success": True,
                "suggestions": []
            })
        
        # 简单的搜索建议实现
        # 这里可以扩展为更复杂的建议逻辑
        result = sync_service.search_data(
            query=query,
            size=5
        )
        
        suggestions = []
        for hit in result["hits"]:
            # 提取可能的建议词
            for field, value in hit["data"].items():
                if isinstance(value, str) and query.lower() in value.lower():
                    if value not in suggestions and len(suggestions) < 5:
                        suggestions.append(value)
        
        return jsonify({
            "success": True,
            "suggestions": suggestions
        })
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": str(e)
        }), 500

@search_bp.route('/record/<form_id>/<record_id>', methods=['GET'])
def get_record_detail(form_id, record_id):
    """获取记录详情"""
    try:
        # 从ES中获取具体记录
        index_name = f"form_{form_id}"
        doc_id = f"{form_id}_{record_id}"
        
        es_client = sync_service.es_client
        
        try:
            response = es_client.get(index=index_name, id=doc_id)
            record = response['_source']
            
            return jsonify({
                "success": True,
                "data": {
                    "form_id": form_id,
                    "record_id": record_id,
                    "record": record
                }
            })
            
        except Exception as e:
            if "not_found" in str(e).lower():
                return jsonify({
                    "success": False,
                    "message": "记录不存在"
                }), 404
            else:
                raise e
        
    except Exception as e:
        return jsonify({
            "success": False,
            "message": str(e)
        }), 500