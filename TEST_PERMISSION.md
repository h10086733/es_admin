# 权限检查测试指南

## 问题诊断

页面正常显示说明权限检查没有生效。需要排查以下几个方面：

## 测试步骤

### 1. 测试权限检查接口

使用你的用户ID测试权限接口，看返回的 isView 值：

```bash
# 替换 YOUR_USER_ID 为实际的用户ID
curl http://localhost:8088/api/search/permission/check/YOUR_USER_ID
```

**预期返回**:
```json
{
  "success": true,
  "data": {
    "isAdmin": false,
    "isView": false
  }
}
```

如果 `isView` 是 `true`，说明管理员接口返回的值不是 `false`。

### 2. 检查管理员接口返回

查看日志中的权限检查结果：

```bash
# 查看最近的日志
tail -f logs/application.log | grep "用户权限检查结果"
```

应该看到类似这样的日志：
```
用户权限检查结果: userId=YOUR_USER_ID, isAdmin=false, isView=false (isViewObj=false)
```

**重点检查**:
- `isView` 的值是 true 还是 false
- `isViewObj` 是 null、true 还是 false

### 3. 检查管理员接口配置

查看配置文件中的管理员检查URL：

```bash
# 查看配置
cat src/main/resources/application.properties | grep admin.check.base-url
# 或
cat src/main/resources/application.yml | grep base-url
```

应该看到：
```
app.admin.check.base-url=http://192.168.31.157/seeyon/rest/token/dataManage/ifAdmin
```

### 4. 直接测试管理员接口

```bash
# 测试管理员接口是否返回 isView 字段
curl http://192.168.31.157/seeyon/rest/token/dataManage/ifAdmin/YOUR_USER_ID
```

**检查返回数据**:
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "isAdmin": false,
    "isView": false    // 关键：检查这个字段是否存在且为 false
  }
}
```

## 可能的问题和解决方案

### 问题1: 管理员接口没有返回 isView 字段

**现象**: 接口返回的 data 中没有 isView 字段

**原因**: 
- 管理员接口还没有实现 isView 字段
- 后端版本不匹配

**解决方案**:
1. 联系后端开发，确认接口是否已经添加 isView 字段
2. 如果接口确实没有这个字段，需要先升级管理员接口

### 问题2: isView 默认值问题

**现象**: isView 为 null 时被当作 true 处理

**原因**: 代码中默认值设置为 true

**解决方案**: 已在代码中修改，如果需要更严格的控制，可以修改为：

```java
// 更严格：isView 为 null 时也认为无权限
boolean isView = Boolean.TRUE.equals(isViewObj);
```

修改位置：`AdminCheckService.java` 第112行

### 问题3: 权限检查被跳过

**现象**: 日志中看不到"用户权限检查结果"

**原因**: 
- userId 为空
- URL配置错误
- 接口调用失败

**检查步骤**:
```bash
# 查看错误日志
tail -f logs/application.log | grep -E "权限检查|checkUserPermission|AdminCheckService"
```

### 问题4: 前端没有传递 userId

**现象**: 后端日志显示"用户ID为空"

**检查方法**:
1. 打开浏览器开发者工具 (F12)
2. 切换到 Network 标签
3. 执行搜索操作
4. 查看 `/api/search/search` 请求
5. 检查 Request Payload 中是否有 userId 字段

**解决方案**:
确保前端代码中有设置 userId：
```javascript
const payload = {
    query: query,
    userId: currentUserId,  // 确保这个值不为空
    // ...
};
```

## 调试命令

### 查看完整的权限检查流程

```bash
# 实时查看所有权限相关日志
tail -f logs/application.log | grep -E "权限|permission|isView|AdminCheck"
```

### 测试完整流程

```bash
# 1. 测试权限检查
curl http://localhost:8088/api/search/permission/check/-64557199688994460741

# 2. 测试搜索接口
curl -X POST http://localhost:8088/api/search/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "测试",
    "userId": "-64557199688994460741",
    "size": 10,
    "from": 0
  }'
```

## 快速修改方案

如果管理员接口确实返回了 `isView=false`，但页面还是正常显示，可以尝试以下修改：

### 方案1: 更严格的默认值

修改 `AdminCheckService.java` 第112行：

```java
// 原代码
boolean isView = isViewObj != null ? isViewObj : true;

// 改为更严格（null也认为无权限）
boolean isView = Boolean.TRUE.equals(isViewObj);
```

### 方案2: 添加详细日志

在 `SearchService.java` 第79-83行添加更多日志：

```java
AdminCheckService.AdminPermission permission = adminCheckService.checkUserPermission(userIdStr);
log.info("权限检查结果: userId={}, isAdmin={}, isView={}", 
    userIdStr, permission.isAdmin(), permission.isView());
    
if (!permission.isView()) {
    log.warn("用户 {} 没有查看权限，isView=false - 拒绝访问", userIdStr);
    return PermissionFilterResult.deny("您没有权限访问，请联系管理员");
}
log.info("用户 {} 权限检查通过，继续处理", userIdStr);
```

### 方案3: 临时测试 - 强制检查

在 `SearchService.java` 中临时添加强制检查：

```java
// 临时测试代码
if ("-64557199688994460741".equals(userIdStr)) {
    log.warn("测试用户强制拒绝访问");
    return PermissionFilterResult.deny("您没有权限访问，请联系管理员（测试）");
}
```

## 验证方法

修改后，执行搜索操作，应该看到：

1. **后端日志**:
```
用户 -64557199688994460741 没有查看权限，isView=false
```

2. **前端页面显示**:
```
┌──────────────────────────┐
│         🔒               │
│    无权限访问             │
│ 您没有权限访问，请联系管理员│
└──────────────────────────┘
```

3. **Network 响应**:
```json
{
  "success": false,
  "message": "您没有权限访问，请联系管理员"
}
```

## 需要提供的信息

如果还是不行，请提供：

1. 权限检查接口的返回结果
```bash
curl http://localhost:8088/api/search/permission/check/YOUR_USER_ID
```

2. 管理员接口的返回结果
```bash
curl http://192.168.31.157/seeyon/rest/token/dataManage/ifAdmin/YOUR_USER_ID
```

3. 后端日志中的权限检查信息
```bash
grep "用户权限检查结果" logs/application.log | tail -5
```

4. 浏览器控制台中的搜索请求信息（Network标签）
