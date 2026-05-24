# Vibe WeChat

微信 ilink 机器人连接 Claude 的中间件。

## 功能

1. 微信扫码登录 - 生成二维码，扫码连接 ilink
2. Claude 对话 - 通过微信与 Claude 对话
3. 消息过滤 - 配置 Claude 输出内容
4. 会话管理 - 新建/切换/清空会话
5. 实时配置 - 通过微信消息配置 API 和 Key
6. Token 统计 - 显示每次对话的 Token 消耗
7. Claude 路径自动识别 - 自动检测机器上 Claude 安装位置

## 部署方式

### 1. 环境要求
- Java 25+
- Maven 3+
- Redis

### 2. 快速启动（推荐）
```bash
cd /home/chengxun/vibe-wechat

# 启动服务
./vibe-wechat.sh start

# 停止服务
./vibe-wechat.sh stop

# 重启服务
./vibe-wechat.sh restart

# 查看状态
./vibe-wechat.sh status

# 修改端口（会自动重启）
./vibe-wechat.sh port 8080
```

### 3. 手动启动
```bash
# 编译打包
mvn clean package -DskipTests

# 启动服务
java -jar target/vibe-wechat-1.0-SNAPSHOT.jar
```

### 4. 访问二维码
启动后访问: http://localhost:9921/qrcode

## 使用方式

### 微信命令（v- 前缀）

**帮助和状态**
- `v-help` - 显示所有命令
- `v-status` - 显示当前配置和 Token 使用

**Claude 配置**
- `v-api <url>` - 设置 Claude API 地址（默认: https://api.anthropic.com）
- `v-key <key>` - 设置 Claude API Key
- `v-model <name>` - 设置 Claude 模型（默认: claude-sonnet-4-20250514）

**快捷过滤**
- `v-tools` - 开关工具类消息
- `v-fileread` - 开关读取文件类消息
- `v-fileedit` - 开关编辑文件类消息

**高级过滤**
- `v-filter tools true/false` - 工具调用
- `v-filter fileread true/false` - 读取文件
- `v-filter fileedit true/false` - 编辑文件
- `v-filter files true/false` - 所有文件操作
- `v-filter decisions true/false` - 决策消息
- `v-filter results true/false` - 结果消息
- `v-filter subtasks true/false` - 子任务
- `v-filter tasks true/false` - 任务完成
- `v-filter duration true/false` - 耗时显示
- `v-filter token true/false` - Token 统计

**消息配置**
- `v-limit <n>` - 每小时消息限制（默认: 10）
- `v-token` - 查看当前 Token 使用
- `v-token true/false` - 开关 Token 统计显示

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
    api-url: https://api.anthropic.com
    model: claude-sonnet-4-20250514
    max-tokens: 4096
  ilink:
    host: localhost
    port: 9090
  filter:
    show-tool-calls: false
    show-file-read: false
    show-file-edit: false
    show-file-operations: false
    show-decisions-only: true
    show-results-only: true
    show-subtask-completion: true
    show-task-completion: true
    show-task-duration: true
    show-token-usage: true
    max-messages-per-user: 10
```

### 环境变量

```bash
CLAUDE_API_KEY=your-api-key
CLAUDE_API_URL=https://api.anthropic.com
```

### Claude 路径自动识别

程序启动时会自动检测机器上 Claude 的安装位置：
- 检查常见路径：`/usr/local/bin/claude`、`/opt/claude`、`~/claude` 等
- 读取配置文件获取自定义路径
- 用户可通过微信命令 `v-api <url>` 修改 API 地址
- 优先级：微信命令 > 环境变量 > 配置文件 > 自动检测

## 消息处理逻辑

1. 用户发送微信消息
2. 检查是否为 `v-` 命令 → 直接处理
3. 检查消息限制（每用户每小时10条）
4. 发送"正在输入"状态
5. 转发给 Claude API
6. 根据过滤配置筛选消息
7. 记录 Token 使用量
8. 返回给用户
