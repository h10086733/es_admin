import json
from config.database import dm_conn

class FormModel:
    @staticmethod
    def get_all_forms():
        """获取所有有效的表单配置"""
        conn = dm_conn.connect()
        if not conn:
            return []
        
        try:
            cursor = conn.cursor()
            sql = """
            SELECT ID, NAME, FIELD_INFO, VIEW_INFO, APPBIND_INFO, EXTENSIONS_INFO
            FROM CAP_FORM_DEFINITION 
            WHERE DELETE_FLAG = 0
            """
            cursor.execute(sql)
            forms = cursor.fetchall()
            
            result = []
            for form in forms:
                form_data = {
                    'id': str(form[0]),  # 转换为字符串避免JavaScript精度丢失
                    'name': form[1],
                    'field_info': json.loads(form[2]) if form[2] else {},
                    'view_info': json.loads(form[3]) if form[3] else {},
                    'appbind_info': json.loads(form[4]) if form[4] else {},
                    'extensions_info': json.loads(form[5]) if form[5] else {}
                }
                result.append(form_data)
            
            cursor.close()
            return result
            
        except Exception as e:
            print(f"获取表单配置失败: {e}")
            return []
        finally:
            conn.close()
    
    @staticmethod
    def get_form_by_id(form_id):
        """根据ID获取表单配置"""
        conn = dm_conn.connect()
        if not conn:
            return None
        
        try:
            cursor = conn.cursor()
            sql = """
            SELECT ID, NAME, FIELD_INFO, VIEW_INFO, APPBIND_INFO, EXTENSIONS_INFO
            FROM CAP_FORM_DEFINITION 
            WHERE ID = ? AND DELETE_FLAG = 0
            """
            cursor.execute(sql, (form_id,))
            form = cursor.fetchone()
            
            if form:
                return {
                    'id': str(form[0]),  # 转换为字符串避免JavaScript精度丢失
                    'name': form[1],
                    'field_info': json.loads(form[2]) if form[2] else {},
                    'view_info': json.loads(form[3]) if form[3] else {},
                    'appbind_info': json.loads(form[4]) if form[4] else {},
                    'extensions_info': json.loads(form[5]) if form[5] else {}
                }
            
            cursor.close()
            return None
            
        except Exception as e:
            print(f"获取表单配置失败: {e}")
            return None
        finally:
            conn.close()
    
    @staticmethod
    def get_form_table_name(form_id):
        """从表单配置中提取数据表名"""
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return None
        
        try:
            field_info = form['field_info']
            if 'front_formmain' in field_info:
                return field_info['front_formmain'].get('tableName')
            return None
        except Exception as e:
            print(f"提取表名失败: {e}")
            return None
    
    @staticmethod
    def get_form_fields(form_id):
        """获取表单字段定义"""
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return []
        
        try:
            field_info = form['field_info']
            if 'front_formmain' in field_info:
                return field_info['front_formmain'].get('fieldInfo', [])
            return []
        except Exception as e:
            print(f"获取字段定义失败: {e}")
            return []
    
    @staticmethod
    def get_table_data(table_name, limit=None, offset=0, modify_date_after=None):
        """获取表单数据表中的数据（支持分批处理）"""
        conn = dm_conn.connect()
        if not conn:
            return []
        
        try:
            cursor = conn.cursor()
            
            # 构建SQL
            sql = f"SELECT * FROM {table_name}"
            params = []
            
            # 增量同步条件
            if modify_date_after:
                sql += " WHERE modify_date > ?"
                params.append(modify_date_after)
            
            # 分页 - 使用ID排序提高性能
            sql += " ORDER BY ID"
            if limit:
                sql += f" LIMIT {limit} OFFSET {offset}"
            
            cursor.execute(sql, params)
            columns = [desc[0] for desc in cursor.description]
            rows = cursor.fetchall()
            
            result = []
            for row in rows:
                record = {}
                for i, col in enumerate(columns):
                    record[col] = row[i]
                result.append(record)
            
            cursor.close()
            return result
            
        except Exception as e:
            print(f"获取表数据失败: {e}")
            return []
        finally:
            conn.close()
    
    @staticmethod
    def get_table_data_iterator(table_name, batch_size=5000, modify_date_after=None):
        """分批迭代获取表数据，适用于大数据量"""
        offset = 0
        while True:
            batch_data = FormModel.get_table_data(
                table_name, 
                limit=batch_size, 
                offset=offset, 
                modify_date_after=modify_date_after
            )
            
            if not batch_data:
                break
                
            yield batch_data
            offset += batch_size
            
            # 如果返回的数据少于batch_size，说明已经是最后一批
            if len(batch_data) < batch_size:
                break
    
    @staticmethod
    def get_last_sync_time(form_id):
        """获取最后同步时间"""
        # 这里可以存储在文件或数据库中，暂时返回None表示全量同步
        return None
    
    @staticmethod
    def get_field_labels(form_id):
        """获取表单字段的中文标签映射"""
        form = FormModel.get_form_by_id(form_id)
        if not form:
            return {}
        
        try:
            field_info = form['field_info']
            field_labels = {}
            
            if 'front_formmain' in field_info:
                fields = field_info['front_formmain'].get('fieldInfo', [])
                for field in fields:
                    field_name = field.get('name', field.get('columnName', ''))
                    # 优先使用 display 字段，然后是 label 和 title
                    field_label = field.get('display', field.get('label', field.get('title', '')))
                    if field_name and field_label:
                        field_labels[field_name] = field_label
            
            return field_labels
        except Exception as e:
            print(f"获取字段标签失败: {e}")
            return {}
    
    @staticmethod
    def get_member_name(member_id):
        """根据成员ID获取成员姓名"""
        if not member_id or member_id == 0:
            return None
            
        conn = dm_conn.connect()
        if not conn:
            return None
        
        try:
            cursor = conn.cursor()
            sql = "SELECT NAME FROM ORG_MEMBER WHERE ID = ?"
            cursor.execute(sql, (member_id,))
            result = cursor.fetchone()
            cursor.close()
            
            if result:
                return result[0]
            return None
            
        except Exception as e:
            print(f"获取成员姓名失败: {e}")
            return None
        finally:
            conn.close()