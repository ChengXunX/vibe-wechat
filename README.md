# Vibe WeChat

微信 ilink 机器人连接 Claude 的中间件。

## 功能

1. **微信扫码登录** - 生成二维码，扫码连接 ilink
2. **Claude 对话** - 通过微信与 Claude 对话
3. **消息过滤** - 配置 Claude 输出内容（工具调用、文件操作等）
4. **会话管理** - 新建/切换/清空会话
5. **一键配置** - 通过微信消息快速配置 API Key 和模型
6. **配置切换** - 类似 ccswitch，支持多套配置快速切换
7. **推理模式** - 支持 Claude thinking 模式
8. **关键词过滤** - 过滤包含关键词的回复，节省微信通知次数
9. **Token 统计** - 显示每次对话的 Token 消耗
10. **Claude 路径自动识别** - 自动检测机器上 Claude 安装位置并读取配置

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

### 5. 首次配置
扫码连接后，在微信中发送：
```
v-config your-api-key claude-sonnet-4-20250514
```
即可一键完成配置。

## 使用方式

### 微信命令（v- 前缀）

**帮助和状态**
- `v-help` - 显示所有命令
- `v-status` - 显示当前配置（表格形式）

**Claude 配置**
- `v-model <name>` - 设置模型（支持 [1m] 配置）
- `v-claude <path>` - 设置 Claude CLI 安装路径
- `v-thinking` - 开关推理模式

**配置切换（类似 ccswitch）**
- `v-switch <name>` - 切换到指定预设配置
- `v-save <name>` - 保存当前配置为预设
- `v-profiles` - 列出所有预设配置

**工作目录**
- `v-cd <path>` - 切换 Claude 工作目录

**消息过滤**
- `v-tools` - 开关工具类消息
- `v-fileread` - 开关读取文件消息
- `v-fileedit` - 开关编辑文件消息
- `v-block <词>` - 添加过滤关键词
- `v-unblock <词>` - 移除过滤关键词

**消息配置**
- `v-limit <n>` - 本账号内部消息数限制（24小时）
- `v-notify` - 收到消息时发送处理确认

**会话管理**
- `v-new` - 新建会话
- `v-clear` - 清空会话
- `v-sessions` - 列出会话
- `v-session <id>` - 切换会话

## 配置说明

### 配置优先级
1. 微信命令（v-config/v-api/v-key/v-model）
2. 环境变量
3. 配置文件
4. Claude 配置文件（自动读取 ~/.claude/settings.json）
5. 自动检测

### 环境变量
```bash
CLAUDE_API_KEY=your-api-key
CLAUDE_API_URL=https://api.anthropic.com
ILINK_BOT_TOKEN=your-bot-token
```

### 配置文件
编辑 `src/main/resources/application.yml`:
```yaml
server:
  port: 9921

vibe-wechat:
  claude:
    api-key: ${CLAUDE_API_KEY:}
    api-url: ${CLAUDE_API_URL:https://api.anthropic.com}
    model: claude-sonnet-4-20250514
  ilink:
    base-url: https://ilinkai.weixin.qq.com
    bot-token: ${ILINK_BOT_TOKEN:}
  filter:
    show-tool-calls: false
    show-file-read: false
    show-file-edit: false
```

## 消息处理逻辑

1. 用户发送微信消息
2. 检查是否为 `v-` 命令 → 直接处理
3. 检查消息限制
4. 发送"正在输入"状态
5. 转发给 Claude API
6. 根据过滤配置筛选消息
7. 格式化 markdown 为纯文本
8. 记录 Token 使用量
9. 返回给用户

## 项目结构

```
vibe-wechat/
├── src/main/java/com/chengxun/vibewechat/
│   ├── config/           # 配置类
│   ├── controller/       # REST 控制器
│   ├── model/            # 数据模型
│   ├── service/          # 业务逻辑
│   └── util/             # 工具类
├── src/main/resources/
│   └── application.yml   # 配置文件
├── vibe-wechat.sh        # 启动脚本
└── README.md             # 项目说明
```

## License

Copyright 2026 ChengXun

GitHub: https://github.com/ChengXunX/vibe-wechat
