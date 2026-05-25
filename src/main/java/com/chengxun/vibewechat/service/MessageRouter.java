package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.FilterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MessageRouter {

    @Autowired
    private ClaudeApiService claudeApiService;

    @Autowired
    private IlInkService ilinkService;

    @Autowired
    private FilterConfig filterConfig;

    @Autowired
    private ConfigService configService;

    private final Map<String, String> userContextTokens = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warningSent = new ConcurrentHashMap<>();

    private static final String V_PREFIX = "v-";
    private static final String V_HELP = "v-help";
    private static final String V_FILTER = "v-filter";
    private static final String V_STATUS = "v-status";
    private static final String V_SESSION = "v-session";
    private static final String V_NEW = "v-new";
    private static final String V_CLEAR = "v-clear";
    private static final String V_SESSIONS = "v-sessions";
    private static final String V_LIMIT = "v-limit";
    private static final String V_API = "v-api";
    private static final String V_KEY = "v-key";
    private static final String V_MODEL = "v-model";
    private static final String V_TOOLS = "v-tools";
    private static final String V_FILEREAD = "v-fileread";
    private static final String V_FILEEDIT = "v-fileedit";
    private static final String V_TOKEN = "v-token";
    private static final String V_CLAUDE = "v-claude";
    private static final String V_CONFIG = "v-config";
    private static final String V_CD = "v-cd";
    private static final String V_BLOCK = "v-block";
    private static final String V_UNBLOCK = "v-unblock";
    private static final String V_NOTIFY = "v-notify";
    private static final String V_SWITCH = "v-switch";
    private static final String V_SAVE = "v-save";
    private static final String V_PROFILES = "v-profiles";
    private static final String V_THINKING = "v-thinking";
    private static final String V_DELETE = "v-delete";

    @EventListener
    public void handleIlInkMessage(IlInkService.IlInkMessageEvent event) {
        userContextTokens.put(event.getUserId(), event.getContextToken());
        handleMessage(event.getUserId(), event.getContent(), event.getContextToken(), event.isNewUser());
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        claudeApiService.setToolCallback(new ClaudeApiService.ToolCallback() {
            @Override
            public void onToolUse(String userId, String toolName, String toolInput) {
                if (filterConfig.isShowToolCalls()) {
                    String contextToken = userContextTokens.get(userId);
                    String cleanInput = toolInput.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
                    ilinkService.sendText(userId, "🔧 工具调用: " + toolName + "\n" + cleanInput, contextToken, "tool");
                }
            }

            @Override
            public void onToolResult(String userId, String result) {
                if (filterConfig.isShowToolCalls()) {
                    String contextToken = userContextTokens.get(userId);
                    ilinkService.sendText(userId, "📋 工具结果: " + result, contextToken, "sub_result");
                }
            }

            @Override
            public void onSubtaskStatus(String userId, String status) {
                if (filterConfig.isShowSubtaskStatus()) {
                    String contextToken = userContextTokens.get(userId);
                    ilinkService.sendText(userId, "🔄 " + status, contextToken, "sub_result");
                }
            }
        });
    }

    public void handleMessage(String userId, String message, String contextToken) {
        handleMessage(userId, message, contextToken, false);
    }

    public void handleMessage(String userId, String message, String contextToken, boolean isNewUser) {
        if (message.startsWith(V_PREFIX)) {
            handleVibeCommand(userId, message, contextToken);
            return;
        }

        if (isNewUser) {
            ilinkService.sendText(userId, IlInkService.WELCOME_MESSAGE, contextToken);
        }

        ilinkService.sendTyping(userId);

        if (filterConfig.isShowMessageStatus()) {
            String sessionId = claudeApiService.getSessionId(userId);
            String model = claudeApiService.getModel();
            String workDir = System.getProperty("user.dir");
            String sessionDisplay = sessionId != null ? sessionId : "新会话";
            String statusMsg = String.format(
                "✅ 收到消息，开始处理...\n\n📋 会话ID: `%s`\n🤖 模型: `%s`\n📁 工作目录: `%s`",
                sessionDisplay, model, workDir
            );
            ilinkService.sendText(userId, statusMsg, contextToken, "result");
        }

        long startTime = System.currentTimeMillis();

        Thread typingThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                    ilinkService.sendTyping(userId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        typingThread.setDaemon(true);
        typingThread.start();

        try {
            String response = claudeApiService.sendMessage(userId, message);
            long duration = System.currentTimeMillis() - startTime;

            boolean blocked = false;
            for (String keyword : filterConfig.getBlockedKeywords()) {
                if (response != null && response.contains(keyword)) {
                    blocked = true;
                    break;
                }
            }

            if (!blocked && response != null && !response.isEmpty()) {
                String taskSummary = claudeApiService.getTaskSummary(userId, message);
                String statsSummary = claudeApiService.getTaskCompletionSummary(userId, duration);
                String fullResponse = "✅ 任务完成 | " + taskSummary + "\n\n━━━━━━━━━━━━━━━━━━━━\n" + response + "\n\n" + statsSummary;
                ilinkService.sendText(userId, fullResponse, contextToken, "result");
            } else if (blocked) {
                ilinkService.sendText(userId, "✅ 任务完成（内容被关键词过滤）", contextToken, "result");
            }
        } finally {
            typingThread.interrupt();
            ilinkService.sendStopTyping(userId);
        }
    }

    private void handleVibeCommand(String userId, String command, String contextToken) {
        String[] parts = command.split("\\s+");
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case V_HELP -> showHelp(userId, contextToken);
            case V_FILTER -> handleFilterCommand(userId, parts, contextToken);
            case V_STATUS -> showStatus(userId, contextToken);
            case V_SESSION -> handleSessionCommand(userId, parts, contextToken);
            case V_NEW -> claudeApiService.clearHistory(userId);
            case V_CLEAR -> claudeApiService.clearHistory(userId);
            case V_SESSIONS -> handleListSessions(userId, contextToken);
            case V_LIMIT -> handleLimitCommand(userId, parts, contextToken);
            case V_API -> { if (parts.length > 1) { claudeApiService.setApiUrl(parts[1]); ilinkService.sendText(userId, "已设置 API: " + parts[1], contextToken); } }
            case V_KEY -> { if (parts.length > 1) { claudeApiService.setApiKey(parts[1]); ilinkService.sendText(userId, "已设置 API Key", contextToken); } }
            case V_MODEL -> { if (parts.length > 1) { claudeApiService.setModel(parts[1]); ilinkService.sendText(userId, "已设置模型: " + parts[1], contextToken); } }
            case V_TOOLS -> handleQuickToggle(userId, "tools", parts, contextToken);
            case V_FILEREAD -> handleQuickToggle(userId, "fileread", parts, contextToken);
            case V_FILEEDIT -> handleQuickToggle(userId, "fileedit", parts, contextToken);
            case V_TOKEN -> { String usage = claudeApiService.getTokenUsageSummary(userId); ilinkService.sendText(userId, "Token: " + usage, contextToken); }
            case V_CLAUDE -> { if (parts.length > 1) { claudeApiService.setInstallPath(parts[1]); ilinkService.sendText(userId, "已设置路径: " + parts[1], contextToken); } }
            case V_CD -> { if (parts.length > 1) { System.setProperty("user.dir", parts[1]); ilinkService.sendText(userId, "工作目录: " + parts[1], contextToken); } }
            case V_BLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().add(kw); ilinkService.sendText(userId, "已添加: " + kw, contextToken); } }
            case V_UNBLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().remove(kw); ilinkService.sendText(userId, "已移除: " + kw, contextToken); } }
            case V_NOTIFY -> { if (parts.length > 1) { filterConfig.setShowMessageStatus(Boolean.parseBoolean(parts[1])); ilinkService.sendText(userId, "通知: " + (filterConfig.isShowMessageStatus() ? "开启" : "关闭"), contextToken); } }
            case V_SWITCH -> handleSwitchCommand(userId, parts, contextToken);
            case V_SAVE -> handleSaveCommand(userId, parts, contextToken);
            case V_PROFILES -> handleProfilesCommand(userId, parts, contextToken);
            case V_THINKING -> { if (parts.length > 1) { boolean on = !parts[1].equalsIgnoreCase("off"); claudeApiService.setThinkingEnabled(on); ilinkService.sendText(userId, "推理模式: " + (on ? "开启" : "关闭"), contextToken); } }
            case V_DELETE -> handleDeleteSessionCommand(userId, parts, contextToken);
            default -> ilinkService.sendText(userId, "未知命令: " + cmd + "\n输入 v-help 查看所有命令", contextToken);
        }
    }

    private void showHelp(String userId, String contextToken) {
        String help = """
                **vibe-wechat 命令列表**

                `v-help` 显示此帮助
                `v-status` 显示当前配置

                **Claude 配置**
                `v-config <key> [url] [model]` 一键配置
                `v-api <url>` API 地址
                `v-key <key>` API Key
                `v-model <name>` 模型
                `v-claude <path>` 安装路径
                `v-thinking <级别>` 推理模式
                `v-switch <name>` 切换配置
                `v-save <name>` 保存配置
                `v-profiles` 列出预设

                **工作目录**
                `v-cd <path>` 切换目录

                **通知配置**
                `v-tools` 工具调用通知
                `v-fileread` 文件读取通知
                `v-fileedit` 文件编辑通知
                `v-filter` 高级过滤
                `v-notify` 消息状态通知

                **关键词过滤**
                `v-block <词>` 添加过滤
                `v-unblock <词>` 移除过滤

                **会话管理**
                `v-new` 新建会话
                `v-clear` 清空会话
                `v-sessions` 列出会话
                `v-session <id>` 切换会话
                `v-delete <id>` 删除会话
                """;
        ilinkService.sendText(userId, help, contextToken);
    }

    private void showStatus(String userId, String contextToken) {
        String status = String.format("""
                **📋 当前配置状态**

                ━━━━━━━━━━━━━━━━━━━━━━
                **Claude**
                ━━━━━━━━━━━━━━━━━━━━━━
                API: `%s`
                模型: `%s`
                路径: `%s`

                ━━━━━━━━━━━━━━━━━━━━━━
                **工作目录**
                ━━━━━━━━━━━━━━━━━━━━━━
                `%s`

                ━━━━━━━━━━━━━━━━━━━━━━
                **通知**
                ━━━━━━━━━━━━━━━━━━━━━━
                工具调用: %s
                文件读取: %s
                文件编辑: %s
                """,
                claudeApiService.getApiUrl(),
                claudeApiService.getModel(),
                claudeApiService.getInstallPath() != null ? claudeApiService.getInstallPath() : "自动检测",
                System.getProperty("user.dir"),
                filterConfig.isShowToolCalls() ? "✅" : "❌",
                filterConfig.isShowFileRead() ? "✅" : "❌",
                filterConfig.isShowFileEdit() ? "✅" : "❌");
        ilinkService.sendText(userId, status, contextToken);
    }

    private void handleFilterCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 3) {
            ilinkService.sendText(userId, "用法: v-filter <key> <value>", contextToken);
            return;
        }
        String key = parts[1].toLowerCase();
        String value = parts[2].toLowerCase();
        switch (key) {
            case "tools" -> filterConfig.setShowToolCalls(Boolean.parseBoolean(value));
            case "fileread" -> filterConfig.setShowFileRead(Boolean.parseBoolean(value));
            case "fileedit" -> filterConfig.setShowFileEdit(Boolean.parseBoolean(value));
            case "notify" -> filterConfig.setShowMessageStatus(Boolean.parseBoolean(value));
        }
        configService.saveConfig();
        ilinkService.sendText(userId, "已更新: " + key + " = " + value, contextToken);
    }

    private void handleQuickToggle(String userId, String type, String[] parts, String contextToken) {
        boolean newValue = parts.length >= 2 ? Boolean.parseBoolean(parts[1].toLowerCase()) : true;
        switch (type) {
            case "tools" -> filterConfig.setShowToolCalls(newValue);
            case "fileread" -> filterConfig.setShowFileRead(newValue);
            case "fileedit" -> filterConfig.setShowFileEdit(newValue);
        }
        configService.saveConfig();
        ilinkService.sendText(userId, type + " = " + (newValue ? "on" : "off"), contextToken);
    }

    private void handleSessionCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2 && claudeApiService.switchSession(userId, parts[1])) {
            ilinkService.sendText(userId, "已切换: " + parts[1], contextToken);
        } else {
            ilinkService.sendText(userId, "用法: v-session <id>\n使用 v-sessions 查看", contextToken);
        }
    }

    private void handleListSessions(String userId, String contextToken) {
        java.util.List<String> history = claudeApiService.getSessionHistory(userId);
        String current = claudeApiService.getSessionId(userId);
        StringBuilder sb = new StringBuilder("**会话列表:**\n");
        if (history.isEmpty()) {
            sb.append("暂无历史会话");
        } else {
            for (int i = 0; i < history.size(); i++) {
                sb.append(i + 1).append(". `").append(history.get(i)).append("`");
                if (history.get(i).equals(current)) sb.append(" 当前");
                sb.append("\n");
            }
        }
        ilinkService.sendText(userId, sb.toString(), contextToken);
    }

    private void handleLimitCommand(String userId, String[] parts, String contextToken) {
        ilinkService.sendText(userId, "微信限制每条消息24h内最多回复10条", contextToken);
    }

    private void handleSwitchCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2 && configService.switchProfile(parts[1])) {
            ilinkService.sendText(userId, "已切换: " + parts[1], contextToken);
        } else {
            ilinkService.sendText(userId, "用法: v-switch <name>\n使用 v-profiles 查看", contextToken);
        }
    }

    private void handleSaveCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2) {
            configService.saveProfile(parts[1]);
            ilinkService.sendText(userId, "已保存: " + parts[1], contextToken);
        } else {
            ilinkService.sendText(userId, "用法: v-save <name>", contextToken);
        }
    }

    private void handleProfilesCommand(String userId, String[] parts, String contextToken) {
        ilinkService.sendText(userId, configService.getSwitchProfiles(), contextToken);
    }

    private void handleDeleteSessionCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2 && claudeApiService.deleteSession(userId, parts[1])) {
            ilinkService.sendText(userId, "已删除: " + parts[1], contextToken);
        } else {
            ilinkService.sendText(userId, "用法: v-delete <session_id>", contextToken);
        }
    }
}
