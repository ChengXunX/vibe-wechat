# Vibe WeChat

微信 ilink 机器人连接 Claude 的中间件。

## 功能

1. 微信扫码登录 - 生成二维码，扫码连接 ilink
2. Claude 对话 - 通过微信与 Claude 对话
3. 消息过滤 - 配置 Claude 输出内容
4. 会话管理 - 新建/切换/清空会话
5. 实时配置 - 通过微信消息配置 API 和 Key

## 部署方式

### 1. 环境要求
- Java 25+
- Maven 3+
- Redis

### 2. 编译打包
```bash
cd /home/chengxun/vibe-wechat
mvn clean package -DskipTests
```

### 3. 启动服务
```bash
java -jar target/vibe-wechat-1.0-SNAPSHOT.jar
```

### 4. 访问二维码
启动后访问: http://localhost:9921/qrcode

## 使用方式

### 微信命令（v- 前缀）

**帮助和状态**
- `v-help` - 显示所有命令
- `v-status` - 显示当前配置

**Claude 配置**
- `v-api <url>` - 设置 Claude API 地址
- `v-key <key>` - 设置 Claude API Key
- `v-model <name>` - 设置 Claude 模型

**消息过滤**
- `v-filter tools true/false` - 工具调用
- `v-filter files true/false` - 文件操作
- `v-filter decisions true/false` - 决策消息
- `v-filter results true/false` - 结果消息
- `v-filter subtasks true/false` - 子任务
- `v-filter tasks true/false` - 任务完成
- `v-filter duration true/false` - 耗时显示
- `v-limit <n>` - 每小时消息限制

**会话管理**
- `v-new` - 新建会话
- `v-clear` - 清空会话
- `v-sessions` - 列出会话
- `v-session <id>` - 切换会话

### 配置文件

编辑 `src/main/resources/application.yml`:
```yaml
vibe-wechat:
  claude:
    api-key: your-api-key
    model: claude-sonnet-4-20250514
  ilink:
    host: localhost
    port: 9090
  filter:
    show-tool-calls: false
    show-file-operations: false
    show-decisions-only: true
    show-results-only: true
    show-subtask-completion: true
    show-task-completion: true
    show-task-duration: true
    max-messages-per-user: 10
```

## 消息处理逻辑

1. 用户发送微信消息
2. 检查是否为 `v-` 命令 → 直接处理
3. 检查消息限制（每用户每小时10条）
4. 发送"正在输入"状态
5. 转发给 Claude API
6. 根据过滤配置筛选消息
7. 返回给用户
