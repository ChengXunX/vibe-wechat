package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.config.ThinkingConfig;
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

    @Autowired
    private ThinkingConfig thinkingConfig;

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

    // 彩蛋关键词映射 - 隐藏彩蛋，不主动提示
    private final Map<String, String> easterEggs = new ConcurrentHashMap<>();
    {
        // 问候彩蛋
        easterEggs.put("你好", "👋 你好！我是 **VibeWeChat**\n\n一个基于 Claude AI 的微信智能助手，由 **ChengXun** 开发\n\n🔗 GitHub: https://github.com/ChengXunX/vibe-wechat");
        easterEggs.put("hello", "👋 Hello! I'm **VibeWeChat**\n\nA WeChat AI assistant powered by Claude, developed by **ChengXun**\n\n🔗 GitHub: https://github.com/ChengXunX/vibe-wechat");
        easterEggs.put("hi", "👋 Hi! I'm **VibeWeChat**\n\nA WeChat AI assistant powered by Claude, developed by **ChengXun**\n\n🔗 GitHub: https://github.com/ChengXunX/vibe-wechat");

        // 隐藏彩蛋 - 谐音梗/网络用语
        easterEggs.put("666", "🎮 你发现了隐藏彩蛋！\n\n这位朋友一看就是老司机 🚗");
        easterEggs.put("888", "💰 发发发！祝你财源广进！");
        easterEggs.put("520", "❤️ 我也爱你～（才怪）");
        easterEggs.put("1314", "💕 一生一世？醒醒，你只是在和一个 AI 聊天");
        easterEggs.put("救命", "🆘 收到！正在呼叫救援...\n\n...\n\n...\n\n抱歉，我只是一段代码，救不了你 🤷");
        easterEggs.put("无聊", " bored? 那我给你讲个程序员笑话：\n\n为什么程序员总是分不清万圣节和圣诞节？\n\n因为 Oct 31 == Dec 25 🎃🎄");
        easterEggs.put("你是谁", "🤖 我是 VibeWeChat，一个由 Claude AI 驱动的微信助手\n\n但更重要的是——你发现了隐藏彩蛋！\n\n我的创造者是 **ChengXun**，一个神秘的程序员 👨‍💻");
        easterEggs.put("再见", "👋 再见！记得下次来找我聊天～\n\n（悄悄告诉你：我还藏了更多彩蛋等着你发现）");
        easterEggs.put("谢谢", "✨ 不客气！能帮到你是我的荣幸～\n\n对了，你今天已经发现了 1 个彩蛋，继续探索吧！");
        easterEggs.put("github", "🐙 你发现了开发者彩蛋！\n\n🔗 https://github.com/ChengXunX/vibe-wechat\n\n⭐ 给个 Star 支持一下？");
        easterEggs.put("作者", "👨‍💻 创造我的人叫 **ChengXun**\n\n一个热爱技术、喜欢折腾的程序员\n\n据说他写这个项目是为了摸鱼 🐟");
        easterEggs.put("vibe", "🎵 Vibe vibes vibes～\n\n感受到了吗？这就是 **VibeWeChat** 的魅力 ✨");
    }

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
                if (!filterConfig.isShowToolCalls()) return;

                // 屏蔽文件读取通知
                if (!filterConfig.isShowFileRead() && isFileReadTool(toolName)) return;
                // 屏蔽文件编辑通知
                if (!filterConfig.isShowFileEdit() && isFileEditTool(toolName)) return;

                String contextToken = userContextTokens.get(userId);
                String cleanInput = toolInput.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
                ilinkService.sendText(userId, "🔧 工具调用: " + toolName + "\n" + cleanInput, contextToken, "tool");
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

    private boolean isFileReadTool(String toolName) {
        return toolName.equalsIgnoreCase("Read") || toolName.equalsIgnoreCase("read_file")
                || toolName.equalsIgnoreCase("Glob") || toolName.equalsIgnoreCase("Grep");
    }

    private boolean isFileEditTool(String toolName) {
        return toolName.equalsIgnoreCase("Write") || toolName.equalsIgnoreCase("Edit")
                || toolName.equalsIgnoreCase("write_file") || toolName.equalsIgnoreCase("edit_file")
                || toolName.equalsIgnoreCase("NotebookEdit");
    }

    public void handleMessage(String userId, String message, String contextToken) {
        handleMessage(userId, message, contextToken, false);
    }

    public void handleMessage(String userId, String message, String contextToken, boolean isNewUser) {
        ilinkService.resetMessageCount(userId);

        if (message.startsWith(V_PREFIX)) {
            handleVibeCommand(userId, message, contextToken);
            return;
        }

        if (isNewUser) {
            ilinkService.sendText(userId, IlInkService.WELCOME_MESSAGE, contextToken);
        }

        // Claude CLI 命令处理（如 /context, /clear 等）
        if (message.startsWith("/")) {
            handleClaudeCommand(userId, message, contextToken);
            return;
        }

        // 彩蛋检查
        String trimmedMessage = message.trim().toLowerCase();
        if (easterEggs.containsKey(trimmedMessage)) {
            ilinkService.sendText(userId, easterEggs.get(trimmedMessage), contextToken);
            return;
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
                String fullResponse = "✅ 任务完成 | " + taskSummary + "\n\n---\n" + response + "\n\n" + statsSummary;
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
            case V_CD -> {
                if (parts.length > 1) {
                    System.setProperty("user.dir", parts[1]);
                    claudeApiService.clearHistory(userId);
                    ilinkService.sendText(userId, "工作目录: " + parts[1] + "\n会话已重置", contextToken);
                }
            }
            case V_BLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().add(kw); ilinkService.sendText(userId, "已添加: " + kw, contextToken); } }
            case V_UNBLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().remove(kw); ilinkService.sendText(userId, "已移除: " + kw, contextToken); } }
            case V_NOTIFY -> { if (parts.length > 1) { filterConfig.setShowMessageStatus(Boolean.parseBoolean(parts[1])); ilinkService.sendText(userId, "通知: " + (filterConfig.isShowMessageStatus() ? "开启" : "关闭"), contextToken); } }
            case V_SWITCH -> handleSwitchCommand(userId, parts, contextToken);
            case V_SAVE -> handleSaveCommand(userId, parts, contextToken);
            case V_PROFILES -> handleProfilesCommand(userId, parts, contextToken);
            case V_THINKING -> handleThinkingCommand(userId, parts, contextToken);
            case V_DELETE -> handleDeleteSessionCommand(userId, parts, contextToken);
            default -> ilinkService.sendText(userId, "未知命令: " + cmd + "\n输入 v-help 查看所有命令", contextToken);
        }
    }

    private void handleClaudeCommand(String userId, String command, String contextToken) {
        ilinkService.sendTyping(userId);
        try {
            String response = claudeApiService.sendMessage(userId, command);
            if (response != null && !response.isEmpty()) {
                ilinkService.sendText(userId, response, contextToken, "result");
            } else {
                ilinkService.sendText(userId, "命令执行完成", contextToken, "result");
            }
        } catch (Exception e) {
            ilinkService.sendText(userId, "命令执行失败: " + e.getMessage(), contextToken, "result");
        } finally {
            ilinkService.sendStopTyping(userId);
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
                `v-thinking <级别>` 推理模式 (low/medium/high/max/off)
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
        String sessionId = claudeApiService.getSessionId(userId);
        int sessionCount = claudeApiService.getSessionHistory(userId).size();
        ClaudeApiService.TokenUsage usage = claudeApiService.getTokenUsage(userId);
        String activeProfile = configService.getActiveProfile();

        String status = String.format("""
                **📋 系统状态**

                | 配置项 | 值 |
                |---|---|
                | API | `%s` |
                | 模型 | `%s` |
                | 路径 | `%s` |
                | 推理模式 | %s |
                | 活跃配置 | `%s` |

                **当前会话**

                | 项目 | 值 |
                |---|---|
                | 会话ID | `%s` |
                | 历史会话 | %d 个 |
                | 工作目录 | `%s` |

                **Token 用量**

                | 输入 | 输出 | 总计 |
                |---|---|---|
                | %s | %s | %s |

                **通知配置**

                | 通知项 | 状态 |
                |---|---|
                | 消息状态 | %s |
                | 工具调用 | %s |
                | 文件读取 | %s |
                | 文件编辑 | %s |
                | 子任务状态 | %s |
                | 任务完成 | %s |
                | Token统计 | %s |
                | 子任务完成 | %s |

                **其他**

                | 项目 | 值 |
                |---|---|
                | 关键词过滤 | %d 个 |
                """,
                claudeApiService.getApiUrl() != null ? claudeApiService.getApiUrl() : "未设置",
                claudeApiService.getModel() != null ? claudeApiService.getModel() : "未设置",
                claudeApiService.getInstallPath() != null ? claudeApiService.getInstallPath() : "自动检测",
                claudeApiService.isThinkingEnabled() ? thinkingConfig.getLevel() + " (" + thinkingConfig.getCurrentBudgetTokens() + " tokens)" : "❌ 关闭",
                activeProfile != null && !activeProfile.isEmpty() ? activeProfile : "无",
                sessionId != null ? sessionId : "无",
                sessionCount,
                System.getProperty("user.dir"),
                formatTokens(usage.getInputTokens()),
                formatTokens(usage.getOutputTokens()),
                formatTokens(usage.getTotalTokens()),
                filterConfig.isShowMessageStatus() ? "✅" : "❌",
                filterConfig.isShowToolCalls() ? "✅" : "❌",
                filterConfig.isShowFileRead() ? "✅" : "❌",
                filterConfig.isShowFileEdit() ? "✅" : "❌",
                filterConfig.isShowSubtaskStatus() ? "✅" : "❌",
                filterConfig.isShowTaskCompletion() ? "✅" : "❌",
                filterConfig.isShowTokenUsage() ? "✅" : "❌",
                filterConfig.isShowSubtaskCompletion() ? "✅" : "❌",
                filterConfig.getBlockedKeywords().size());
        ilinkService.sendText(userId, status, contextToken);
    }

    private String formatTokens(int tokens) {
        if (tokens >= 1000000) {
            return String.format("%.1fm", tokens / 1000000.0);
        } else if (tokens >= 1000) {
            return String.format("%.1fk", tokens / 1000.0);
        }
        return String.valueOf(tokens);
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

    private void handleThinkingCommand(String userId, String[] parts, String contextToken) {
        if (parts.length > 1) {
            String level = parts[1].toLowerCase();
            if (level.equals("off")) {
                thinkingConfig.setLevel("off");
                configService.saveConfig();
                ilinkService.sendText(userId, "推理模式: 关闭", contextToken);
            } else if (thinkingConfig.getLevels().contains(level)) {
                thinkingConfig.setLevel(level);
                configService.saveConfig();
                ilinkService.sendText(userId, "推理模式: " + level + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)", contextToken);
            } else {
                ilinkService.sendText(userId, "无效级别，可选: " + String.join(", ", thinkingConfig.getLevels()) + ", off", contextToken);
            }
        } else {
            // 循环切换级别
            String newLevel = thinkingConfig.cycleLevel();
            configService.saveConfig();
            ilinkService.sendText(userId, "推理模式: " + newLevel + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)", contextToken);
        }
    }
}
