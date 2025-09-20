#!/usr/bin/env python3
"""
清空ES中的历史数据脚本
"""

import sys
import os
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from config.database import es_conn
from app.models.form_model import FormModel

def clear_all_form_indices():
    """清空所有表单相关的ES索引"""
    print("=== 清空ES历史数据 ===\n")
    
    try:
        # 连接ES
        client = es_conn.connect()
        if not client:
            print("❌ 无法连接到Elasticsearch")
            return False
        
        print("✅ 已连接到Elasticsearch")
        
        # 获取所有form_*索引
        try:
            all_indices = client.indices.get(index="form_*")
            form_indices = list(all_indices.keys())
            print(f"📋 找到 {len(form_indices)} 个表单索引:")
            for idx in form_indices:
                print(f"   - {idx}")
        except Exception as e:
            if "index_not_found" in str(e).lower():
                print("📋 没有找到任何表单索引")
                return True
            else:
                print(f"❌ 获取索引列表失败: {e}")
                return False
        
        if not form_indices:
            print("✅ 没有需要清理的索引")
            return True
        
        # 确认清理操作
        print(f"\n⚠️  即将删除 {len(form_indices)} 个索引，这将清空所有历史数据！")
        confirm = input("确认删除吗？(输入 'yes' 确认): ")
        
        if confirm.lower() != 'yes':
            print("❌ 操作已取消")
            return False
        
        # 删除所有form索引
        print("\n🗑️  开始删除索引...")
        success_count = 0
        
        for index_name in form_indices:
            try:
                client.indices.delete(index=index_name)
                print(f"   ✅ 已删除: {index_name}")
                success_count += 1
            except Exception as e:
                print(f"   ❌ 删除失败 {index_name}: {e}")
        
        print(f"\n🎉 清理完成！成功删除 {success_count}/{len(form_indices)} 个索引")
        return success_count == len(form_indices)
        
    except Exception as e:
        print(f"❌ 清理过程异常: {e}")
        return False

def clear_specific_form_index(form_id):
    """清空指定表单的索引"""
    print(f"=== 清空表单 {form_id} 的索引 ===\n")
    
    try:
        client = es_conn.connect()
        if not client:
            print("❌ 无法连接到Elasticsearch")
            return False
        
        index_name = f"form_{form_id}"
        
        # 检查索引是否存在
        if not client.indices.exists(index=index_name):
            print(f"📋 索引 {index_name} 不存在")
            return True
        
        # 删除索引
        client.indices.delete(index=index_name)
        print(f"✅ 已删除索引: {index_name}")
        return True
        
    except Exception as e:
        print(f"❌ 删除索引失败: {e}")
        return False

def main():
    """主函数"""
    print("ES数据清理工具\n")
    print("选择操作:")
    print("1. 清空所有表单数据")
    print("2. 清空指定表单数据")
    print("3. 查看当前索引状态")
    
    choice = input("\n请选择 (1-3): ").strip()
    
    if choice == "1":
        success = clear_all_form_indices()
        if success:
            print("\n💡 建议接下来执行: python performance_test.py 重新同步数据")
    
    elif choice == "2":
        # 显示可用表单
        forms = FormModel.get_all_forms()
        if not forms:
            print("❌ 没有找到任何表单")
            return
        
        print("\n可用表单:")
        for i, form in enumerate(forms):
            print(f"{i+1}. {form['name']} (ID: {form['id']})")
        
        try:
            form_idx = int(input(f"\n选择表单 (1-{len(forms)}): ")) - 1
            if 0 <= form_idx < len(forms):
                selected_form = forms[form_idx]
                success = clear_specific_form_index(selected_form['id'])
                if success:
                    print(f"\n💡 建议重新同步表单: {selected_form['name']}")
            else:
                print("❌ 无效选择")
        except ValueError:
            print("❌ 请输入数字")
    
    elif choice == "3":
        # 查看索引状态
        try:
            client = es_conn.connect()
            if not client:
                print("❌ 无法连接到Elasticsearch")
                return
            
            try:
                all_indices = client.indices.get(index="form_*")
                print(f"\n📋 当前索引状态 ({len(all_indices)} 个):")
                for index_name, info in all_indices.items():
                    # 获取文档数量
                    try:
                        count_result = client.count(index=index_name)
                        doc_count = count_result['count']
                        print(f"   {index_name}: {doc_count:,} 条记录")
                    except:
                        print(f"   {index_name}: 无法获取记录数")
            except Exception as e:
                if "index_not_found" in str(e).lower():
                    print("\n📋 没有找到任何表单索引")
                else:
                    print(f"❌ 获取索引信息失败: {e}")
        except Exception as e:
            print(f"❌ 连接ES失败: {e}")
    
    else:
        print("❌ 无效选择")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n⏹️ 操作被用户中断")
    except Exception as e:
        print(f"\n❌ 程序异常: {e}")