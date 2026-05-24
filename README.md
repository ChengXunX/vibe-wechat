# VibeWeChat

个人微信 vibe coding 工具 - 通过微信 ilink 机器人连接 Claude CLI，实现远程 vibe coding。

## 功能

| 功能 | 说明 |
|------|------|
| 微信扫码登录 | 生成二维码，扫码连接 ilink |
| Claude 对话 | 通过微信与 Claude CLI 对话 |
| 消息过滤 | 配置通知内容（工具调用、文件操作等） |
| 会话管理 | 新建/切换/清空会话 |
| 一键配置 | 微信命令快速配置 API Key 和模型 |
| 配置切换 | 多套配置快速切换 |
| 推理模式 | 支持 Claude thinking 模式 |
| 关键词过滤 | 过滤特定关键词的回复 |
| Token 统计 | 显示每次对话的 Token 消耗 |
| 路径自动识别 | 自动检测 Claude 安装位置 |

## 快速开始

### 环境要求
- Java 25+、Maven 3+、Redis

### 启动服务
```bash
cd /home/chengxun/vibe-wechat
./vibe-wechat.sh start    # 启动
./vibe-wechat.sh stop     # 停止
./vibe-wechat.sh restart  # 重启
./vibe-wechat.sh status   # 状态
./vibe-wechat.sh port 8080 # 修改端口
```

### 访问二维码
启动后访问: http://localhost:9921/qrcode

### 首次配置
扫码连接后，发送：`v-config your-api-key claude-sonnet-4-20250514`

## 微信命令

| 命令 | 说明 |
|------|------|
| `v-help` | 显示所有命令 |
| `v-status` | 显示当前配置 |
| `v-config <key> [url] [model]` | 一键配置 |
| `v-model <name>` | 设置模型 |
| `v-claude <path>` | 设置安装路径 |
| `v-thinking <级别>` | 推理模式 |
| `v-switch <name>` | 切换配置 |
| `v-save <name>` | 保存配置 |
| `v-cd <path>` | 切换工作目录 |
| `v-tools` | 工具调用通知 |
| `v-fileread` | 文件读取通知 |
| `v-fileedit` | 文件编辑通知 |
| `v-block <词>` | 添加过滤关键词 |
| `v-unblock <词>` | 移除过滤关键词 |
| `v-notify` | 消息状态通知 |
| `v-new` | 新建会话 |
| `v-clear` | 清空会话 |
| `v-sessions` | 列出会话 |
| `v-session <id>` | 切换会话 |
| `v-delete <id>` | 删除会话 |

## 配置

### 环境变量
```bash
CLAUDE_API_KEY=your-api-key
CLAUDE_API_URL=https://api.anthropic.com
ILINK_BOT_TOKEN=your-bot-token
```

### 配置文件
`src/main/resources/application.yml`:
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

## 项目结构

```
vibe-wechat/
├── src/main/java/com/chengxun/vibewechat/
│   ├── config/       # 配置类
│   ├── controller/   # REST 控制器
│   ├── model/        # 数据模型
│   ├── service/      # 业务逻辑
│   └── util/         # 工具类
├── src/main/resources/
│   └── application.yml
├── vibe-wechat.sh    # 启动脚本
└── README.md
```

## License

Copyright 2026 ChengXun

GitHub: https://github.com/ChengXunX/vibe-wechat
