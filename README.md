# VibeWeChat

个人微信 vibe coding 工具 - 通过微信 ilink 机器人连接 Claude CLI，实现远程 vibe coding。

## 功能

| 功能 | 说明 |
|------|------|
| 微信扫码登录 | 生成二维码，扫码连接 ilink |
| Claude 对话 | 通过微信与 Claude CLI 对话 |
| 常驻进程池 | Claude CLI 常驻运行，支持多进程并行 |
| 消息过滤 | 配置通知内容（工具调用、文件操作等） |
| 会话管理 | 新建/切换/清空会话 |
| 一键配置 | 微信命令快速配置 API Key 和模型 |
| 配置切换 | 多套配置快速切换 |
| 推理模式 | 支持 Claude thinking 模式 |
| 关键词过滤 | 过滤特定关键词的回复 |
| Token 统计 | 显示每次对话的 Token 消耗 |
| 路径自动识别 | 自动检测 Claude 安装位置 |
| 磁盘会话恢复 | 重启后恢复历史会话 |
| 孤儿进程清理 | 自动清理异常关闭的子进程 |

## 快速开始

### 环境要求
- Java 25+、Maven 3+

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
扫码连接后，发送：`v-config your-api-key https://api.anthropic.com claude-sonnet-4-20250514`

## 微信命令

### 基础命令

| 命令 | 说明 |
|------|------|
| `v-help` | 显示所有命令 |
| `v-status` | 显示当前配置 |

### Claude 配置

| 命令 | 说明 |
|------|------|
| `v-config <key> <url> <model>` | 一键配置 |
| `v-api <url>` | API 地址 |
| `v-key <key>` | API Key |
| `v-model <name>` | 模型 |
| `v-claude <path>` | 安装路径 |
| `v-thinking <级别>` | 推理模式 (low/medium/high/max/off/default) |
| `v-switch <name>` | 切换配置 |
| `v-save <name>` | 保存配置 |
| `v-profiles` | 列出预设 |

### 工作目录

| 命令 | 说明 |
|------|------|
| `v-cd <path>` | 切换工作目录（保留上下文） |
| `v-cd <path> clear` | 切换工作目录（清空上下文） |

### 通知配置

| 命令 | 说明 |
|------|------|
| `v-notify` | 消息状态通知 |
| `v-tools` | 工具调用通知 |
| `v-fileread` | 文件读取通知 |
| `v-fileedit` | 文件编辑通知 |
| `v-subtask <true/false>` | 子任务状态通知 |
| `v-subtask-done <true/false>` | 子任务完成通知 |
| `v-token` / `v-token <true/false>` | 查看/开关Token统计 |

### 关键词过滤

| 命令 | 说明 |
|------|------|
| `v-block <词>` | 添加过滤关键词 |
| `v-unblock <词>` | 移除过滤关键词 |

### 会话管理

| 命令 | 说明 |
|------|------|
| `v-new` / `v-clear` | 新建/清空会话 |
| `v-sessions` | 列出内存会话 |
| `v-session <id>` | 切换会话 |
| `v-delete <id>` | 删除会话 |
| `v-disk-sessions` | 查看磁盘历史会话 |
| `v-resume <序号>` | 恢复磁盘会话 |

### 进程管理

| 命令 | 说明 |
|------|------|
| `v-processes` | 查看进程状态 |
| `v-maxproc <数量>` | 设置最大进程数 (1-10，默认5) |
| `v-idle <秒>` | 设置空闲超时 (默认86400秒/24小时) |
| `v-prefer new/queue` | 排队策略 (默认new: 优先新进程) |

## 进程池特性

| 特性 | 说明 |
|------|------|
| 常驻进程 | Claude CLI 启动后常驻运行，无需每次重启 |
| 多进程并行 | 支持最多 5 个进程同时运行 |
| 上下文保持 | 修改配置后自动恢复会话上下文 |
| 空闲清理 | 24小时无操作自动销毁 |
| 请求排队 | 进程忙碌时自动排队等待 |
| 排队策略 | 可选优先创建新进程或排队等待 |

## 通知配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 消息状态 | ❌ | 收到消息时显示处理状态 |
| 工具调用 | ❌ | 显示 Claude 工具调用详情 |
| 文件读取 | ❌ | 显示文件读取操作 |
| 文件编辑 | ❌ | 显示文件编辑操作 |
| 子任务状态 | ✅ | 子任务创建/更新通知 |
| 子任务完成 | ✅ | 子任务完成通知 |
| 任务完成 | ✅ | 任务完成摘要 |
| Token统计 | ✅ | 显示 Token 消耗 |

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
    max-processes-per-user: 5
    process-idle-timeout-ms: 86400000
    prefer-new-process: true
  ilink:
    base-url: https://ilinkai.weixin.qq.com
    bot-token: ${ILINK_BOT_TOKEN:}
  filter:
    show-tool-calls: false
    show-file-read: false
    show-file-edit: false
    show-subtask-completion: true
    show-task-completion: true
    show-token-usage: true
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

## 跨平台打包

使用 jpackage 将应用打包为原生安装包，支持 Windows、macOS、Linux。

### 环境要求

- JDK 25+ (需包含 jpackage 工具)
- 各平台打包要求：
  - **Windows**: WiX Toolset 3.x 或 NSIS
  - **macOS**: Xcode Command Line Tools
  - **Linux**: rpm-build (RPM) 或 fakeroot (DEB)

### 快速打包

```bash
# 打包当前平台
./package.sh

# 或手动打包
mvn clean package -DskipTests
./package-native.sh
```

### 手动打包

```bash
# 1. 先打包 jar
mvn clean package -DskipTests

# 2. 使用 jpackage 打包原生安装包
# Windows (exe)
jpackage --input target --main-jar vibe-wechat-1.0-SNAPSHOT.jar \
  --main-class com.chengxun.vibewechat.VibeWeChatApplication \
  --name VibeWeChat --type exe --win-menu --win-shortcut

# macOS (pkg)
jpackage --input target --main-jar vibe-wechat-1.0-SNAPSHOT.jar \
  --main-class com.chengxun.vibewechat.VibeWeChatApplication \
  --name VibeWeChat --type pkg --mac-package-identifier com.chengxun.vibewechat

# Linux (rpm)
jpackage --input target --main-jar vibe-wechat-1.0-SNAPSHOT.jar \
  --main-class com.chengxun.vibewechat.VibeWeChatApplication \
  --name VibeWeChat --type rpm --linux-package-name vibe-wechat

# Linux (deb)
jpackage --input target --main-jar vibe-wechat-1.0-SNAPSHOT.jar \
  --main-class com.chengxun.vibewechat.VibeWeChatApplication \
  --name VibeWeChat --type deb --linux-package-name vibe-wechat
```

### GitHub Actions 自动打包

在各平台上分别执行打包命令，或使用 GitHub Actions 自动化：

```yaml
# .github/workflows/release.yml
name: Release
on:
  push:
    tags: ['v*']

jobs:
  build:
    strategy:
      matrix:
        include:
          - os: windows-latest
            type: exe
          - os: macos-latest
            type: pkg
          - os: ubuntu-latest
            type: rpm
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
      - run: mvn clean package -DskipTests
      - name: Build native package
        run: |
          jpackage --input target \
            --main-jar vibe-wechat-1.0-SNAPSHOT.jar \
            --main-class com.chengxun.vibewechat.VibeWeChatApplication \
            --name VibeWeChat \
            --type ${{ matrix.type }}
      - uses: actions/upload-artifact@v4
        with:
          name: vibe-wechat-${{ matrix.os }}
          path: VibeWeChat*
```

### 输出文件

| 平台 | 文件格式 | 说明 |
|------|----------|------|
| Windows | `.exe` | 安装程序 |
| macOS | `.pkg` | 安装包 |
| Linux | `.rpm` | RPM 软件包 |
| Linux | `.deb` | DEB 软件包 |

## License

Copyright 2026 ChengXun

GitHub: https://github.com/ChengXunX/vibe-wechat
