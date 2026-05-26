package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.config.ThinkingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

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

    @Autowired
    private QuotaManager quotaManager;

    private final Map<String, String> userContextTokens = new ConcurrentHashMap<>();
    private final Map<String, Thread> activeTypingThreads = new ConcurrentHashMap<>();

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
    private static final String V_SUBTASK = "v-subtask";
    private static final String V_SUBTASK_DONE = "v-subtask-done";
    private static final String V_PROCESSES = "v-processes";
    private static final String V_MAXPROC = "v-maxproc";
    private static final String V_IDLE = "v-idle";
    private static final String V_PREFER = "v-prefer";
    private static final String V_STOP = "v-stop";

    // 彩蛋关键词映射
    private final Map<String, String> easterEggs = new ConcurrentHashMap<>();
    {
        easterEggs.put("你好", "👋 你好！我是 **VibeWeChat**\n\n一个基于 Claude AI 的微信智能助手，由 **ChengXun** 开发\n\n🔗 GitHub: https://github.com/ChengXunX/vibe-wechat");
        easterEggs.put("hello", "👋 Hello! I'm **VibeWeChat**\n\nA WeChat AI assistant powered by Claude, developed by **ChengXun**\n\n🔗 GitHub: https://github.com/ChengXunX/vibe-wechat");
        easterEggs.put("hi", "👋 Hi! I'm **VibeWeChat**\n\nA WeChat AI assistant powered by Claude, developed by **ChengXun**\n\n🔗 GitHub: https://github.com/ChengXunX/vibe-wechat");
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
        ilinkService.resetMessageCount(event.getUserId());
        handleMessage(event.getUserId(), event.getContent(), event.getContextToken(), event.isNewUser());
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        claudeApiService.setToolCallback(new ClaudeApiService.ToolCallback() {
            @Override
            public void onToolUse(String userId, String toolName, String toolInput) {
                if (!filterConfig.isShowToolCalls()) return;
                if (!filterConfig.isShowFileRead() && isFileReadTool(toolName)) return;
                if (!filterConfig.isShowFileEdit() && isFileEditTool(toolName)) return;

                // 检查配额
                if (!quotaManager.canSendToolMessage(userId)) {
                    log.info("Skipping tool notification due to quota limit for user: {}", userId);
                    return;
                }

                String contextToken = userContextTokens.get(userId);
                String cleanInput = toolInput.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
                ilinkService.sendText(userId, "🔧 工具调用: " + toolName + "\n" + cleanInput, contextToken != null ? contextToken : "", "tool");
                quotaManager.recordMessageSent(userId, "tool");
            }

            @Override
            public void onToolResult(String userId, String result) {
                if (filterConfig.isShowToolCalls()) {
                    if (!quotaManager.canSendToolMessage(userId)) return;
                    String contextToken = userContextTokens.get(userId);
                    ilinkService.sendText(userId, "📋 工具结果: " + result, contextToken != null ? contextToken : "", "sub_result");
                    quotaManager.recordMessageSent(userId, "sub_result");
                }
            }

            @Override
            public void onSubtaskStatus(String userId, String status, boolean isCompleted) {
                boolean shouldNotify = isCompleted ? filterConfig.isShowSubtaskCompletion() : filterConfig.isShowSubtaskStatus();
                if (shouldNotify) {
                    if (!quotaManager.canSendToolMessage(userId)) return;
                    String contextToken = userContextTokens.get(userId);
                    String messageType = isCompleted ? "subtask_completion" : "sub_result";
                    ilinkService.sendText(userId, status, contextToken != null ? contextToken : "", messageType);
                    quotaManager.recordMessageSent(userId, messageType);
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
        if (message.startsWith(V_PREFIX)) {
            handleVibeCommand(userId, message, contextToken);
            return;
        }

        if (isNewUser) {
            String welcome = IlInkService.WELCOME_MESSAGE;
            if (configService.detectConflict()) {
                welcome += "\n\n⚠️ 检测到 Claude settings 文件已被外部修改，已保存为「冲突配置」预设。\n如需恢复，请使用 `v-switch 冲突配置`";
            }
            ilinkService.sendText(userId, welcome, contextToken);
        }

        if (message.startsWith("/")) {
            handleClaudeCommand(userId, message, contextToken);
            return;
        }

        String trimmedMessage = message.trim().toLowerCase();
        if (easterEggs.containsKey(trimmedMessage)) {
            ilinkService.sendText(userId, easterEggs.get(trimmedMessage), contextToken);
            return;
        }

        ilinkService.sendTyping(userId);

        // 预留结果槽位
        quotaManager.reserveForResult(userId);

        if (filterConfig.isShowMessageStatus()) {
            String sessionId = claudeApiService.getSessionId(userId);
            String model = claudeApiService.getModel();
            String workDir = claudeConfig().getWorkDir();
            if (workDir == null || workDir.isEmpty()) workDir = System.getProperty("user.dir");
            String sessionDisplay = sessionId != null ? sessionId : "新会话";
            int processCount = claudeApiService.getProcessCount(userId);
            String statusMsg = String.format(
                "✅ 收到消息，开始处理...\n\n📋 会话ID: `%s`\n🤖 模型: `%s`\n📁 工作目录: `%s`\n⚙️ 进程数: %d",
                sessionDisplay, model, workDir, processCount
            );
            ilinkService.sendText(userId, statusMsg, contextToken, "result");
            quotaManager.recordMessageSent(userId, "result");
        }

        long startTime = System.currentTimeMillis();

        // 停止该用户之前的 typing 线程
        Thread oldThread = activeTypingThreads.get(userId);
        if (oldThread != null && oldThread.isAlive()) {
            oldThread.interrupt();
        }

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
        activeTypingThreads.put(userId, typingThread);
        typingThread.start();

        try {
            CompletableFuture<String> future = claudeApiService.sendMessageAsync(userId, message);
            String response = future.get(); // 阻塞等待结果
            long duration = System.currentTimeMillis() - startTime;

            boolean blocked = false;
            for (String keyword : filterConfig.getBlockedKeywords()) {
                if (response != null && response.contains(keyword)) {
                    blocked = true;
                    break;
                }
            }

            if (!blocked && response != null && !response.isEmpty()) {
                if (filterConfig.isShowTaskCompletion()) {
                    String taskSummary = claudeApiService.getTaskSummary(userId, message);
                    String statsSummary = claudeApiService.getTaskCompletionSummary(userId, duration);
                    String fullResponse = "✅ 任务完成 | " + taskSummary + "\n\n---\n" + response + "\n\n" + statsSummary;
                    ilinkService.sendText(userId, fullResponse, contextToken, "result");
                } else {
                    ilinkService.sendText(userId, response, contextToken, "result");
                }
            } else if (blocked) {
                if (filterConfig.isShowTaskCompletion()) {
                    ilinkService.sendText(userId, "✅ 任务完成（内容被关键词过滤）", contextToken, "result");
                }
            }
        } catch (Exception e) {
            log.error("Failed to get response for user: {}", userId, e);
            ilinkService.sendText(userId, "处理异常: " + e.getMessage(), contextToken, "result");
        } finally {
            quotaManager.releaseResultSlot(userId);
            quotaManager.recordMessageSent(userId, "result");
            typingThread.interrupt();
            activeTypingThreads.remove(userId, typingThread);
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
            case V_NEW -> {
                claudeApiService.clearHistory(userId);
                ilinkService.sendText(userId, "会话已清空，进程已销毁", contextToken);
            }
            case V_CLEAR -> {
                claudeApiService.clearHistory(userId);
                ilinkService.sendText(userId, "会话已清空，进程已销毁", contextToken);
            }
            case V_SESSIONS -> handleListSessions(userId, contextToken);
            case V_LIMIT -> handleLimitCommand(userId, parts, contextToken);
            case V_API -> { if (parts.length > 1) { claudeApiService.setApiUrl(parts[1]); configService.saveConfig(); if (claudeApiService.hasBusyProcesses(userId)) { ilinkService.sendText(userId, "已设置 API: " + parts[1] + "\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置", contextToken); } else { claudeApiService.destroyAllProcesses(userId); ilinkService.sendText(userId, "已设置 API: " + parts[1] + "\n进程已重启\n⚠️ 已覆盖本地 Claude settings 文件", contextToken); } } }
            case V_KEY -> { if (parts.length > 1) { claudeApiService.setApiKey(parts[1]); configService.saveConfig(); if (claudeApiService.hasBusyProcesses(userId)) { ilinkService.sendText(userId, "已设置 API Key\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置", contextToken); } else { claudeApiService.destroyAllProcesses(userId); ilinkService.sendText(userId, "已设置 API Key\n进程已重启\n⚠️ 已覆盖本地 Claude settings 文件", contextToken); } } }
            case V_MODEL -> {
                if (parts.length > 1) {
                    claudeApiService.setModel(parts[1]);
                    configService.saveConfig();
                    if (claudeApiService.hasBusyProcesses(userId)) {
                        ilinkService.sendText(userId, "已设置模型: " + parts[1] + "\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置", contextToken);
                    } else {
                        claudeApiService.destroyAllProcesses(userId);
                        ilinkService.sendText(userId, "已设置模型: " + parts[1] + "\n进程已重启\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
                    }
                }
            }
            case V_TOOLS -> handleQuickToggle(userId, "tools", parts, contextToken);
            case V_FILEREAD -> handleQuickToggle(userId, "fileread", parts, contextToken);
            case V_FILEEDIT -> handleQuickToggle(userId, "fileedit", parts, contextToken);
            case V_SUBTASK -> handleQuickToggle(userId, "subtask", parts, contextToken);
            case V_SUBTASK_DONE -> handleQuickToggle(userId, "subtask-done", parts, contextToken);
            case V_TOKEN -> {
                if (parts.length > 1) {
                    handleQuickToggle(userId, "token", parts, contextToken);
                } else {
                    String usage = claudeApiService.getTokenUsageSummary(userId);
                    String quotaStatus = quotaManager.getStatus(userId);
                    ilinkService.sendText(userId, "Token: " + usage + "\n配额: " + quotaStatus, contextToken);
                }
            }
            case V_CLAUDE -> {
                if (parts.length > 1) {
                    claudeApiService.setInstallPath(parts[1]);
                    configService.saveConfig();
                    if (claudeApiService.hasBusyProcesses(userId)) {
                        ilinkService.sendText(userId, "已设置路径: " + parts[1] + "\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置", contextToken);
                    } else {
                        claudeApiService.destroyAllProcesses(userId);
                        ilinkService.sendText(userId, "已设置路径: " + parts[1] + "\n进程已重启\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
                    }
                }
            }
            case V_CD -> {
                if (parts.length > 1) {
                    java.io.File dir = new java.io.File(parts[1]).getAbsoluteFile();
                    if (!dir.exists() || !dir.isDirectory()) {
                        ilinkService.sendText(userId, "目录不存在: " + parts[1] + "\n请先使用 AI 工具创建该目录", contextToken);
                    } else {
                        System.setProperty("user.dir", dir.getAbsolutePath());
                        claudeApiService.setWorkDir(dir.getAbsolutePath());
                        configService.saveConfig();
                        // 检查是否需要清理上下文
                        boolean clearContext = parts.length > 2 && "clear".equalsIgnoreCase(parts[2]);
                        if (clearContext) {
                            claudeApiService.clearHistory(userId);
                            ilinkService.sendText(userId, "工作目录: " + dir.getAbsolutePath() + "\n上下文已清空，新会话开始", contextToken);
                        } else {
                            ilinkService.sendText(userId, "工作目录: " + dir.getAbsolutePath() + "\n新请求将使用新目录（保留上下文）", contextToken);
                        }
                    }
                } else {
                    ilinkService.sendText(userId, "用法: v-cd <路径> [clear]\n添加 clear 清空上下文", contextToken);
                }
            }
            case V_BLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().add(kw); configService.saveConfig(); ilinkService.sendText(userId, "已添加: " + kw, contextToken); } }
            case V_UNBLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().remove(kw); configService.saveConfig(); ilinkService.sendText(userId, "已移除: " + kw, contextToken); } }
            case V_NOTIFY -> { if (parts.length > 1) { filterConfig.setShowMessageStatus(Boolean.parseBoolean(parts[1])); configService.saveConfig(); ilinkService.sendText(userId, "通知: " + (filterConfig.isShowMessageStatus() ? "开启" : "关闭"), contextToken); } }
            case V_SWITCH -> handleSwitchCommand(userId, parts, contextToken);
            case V_SAVE -> handleSaveCommand(userId, parts, contextToken);
            case V_PROFILES -> handleProfilesCommand(userId, contextToken);
            case V_THINKING -> handleThinkingCommand(userId, parts, contextToken);
            case V_DELETE -> handleDeleteSessionCommand(userId, parts, contextToken);
            case V_PROCESSES -> handleProcessStatus(userId, contextToken);
            case V_MAXPROC -> handleMaxProcCommand(userId, parts, contextToken);
            case V_IDLE -> handleIdleTimeoutCommand(userId, parts, contextToken);
            case V_PREFER -> handlePreferCommand(userId, parts, contextToken);
            case V_STOP -> handleStopCommand(userId, contextToken);
            default -> ilinkService.sendText(userId, "未知命令: " + cmd + "\n输入 v-help 查看所有命令", contextToken);
        }
    }

    private void handleClaudeCommand(String userId, String command, String contextToken) {
        ilinkService.sendTyping(userId);
        quotaManager.reserveForResult(userId);
        try {
            CompletableFuture<String> future = claudeApiService.sendMessageAsync(userId, command);
            String response = future.get();
            if (response != null && !response.isEmpty()) {
                ilinkService.sendText(userId, response, contextToken, "result");
            } else {
                ilinkService.sendText(userId, "命令执行完成", contextToken, "result");
            }
        } catch (Exception e) {
            ilinkService.sendText(userId, "命令执行失败: " + e.getMessage(), contextToken, "result");
        } finally {
            quotaManager.releaseResultSlot(userId);
            quotaManager.recordMessageSent(userId, "result");
            ilinkService.sendStopTyping(userId);
        }
    }

    private void handleProcessStatus(String userId, String contextToken) {
        String status = claudeApiService.getProcessStatus(userId);
        int count = claudeApiService.getProcessCount(userId);
        String quotaStatus = quotaManager.getStatus(userId);
        String preferMode = claudeApiService.isPreferNewProcess() ? "优先新进程" : "优先排队";
        // 计算忙碌进程数
        int busyCount = claudeApiService.getBusyProcessCount(userId);
        ilinkService.sendText(userId, String.format("**进程状态** (共%d个, 忙碌%d个)\n\n%s\n\n**排队策略**: %s\n\n**配额状态**\n%s", count, busyCount, status, preferMode, quotaStatus), contextToken);
    }

    private void showHelp(String userId, String contextToken) {
        String help = """
                **VibeWeChat 命令列表**

                **基础命令**
                | 命令 | 说明 | 命令 | 说明 |
                |------|------|------|------|
                | `v-help` | 显示此帮助 | `v-status` | 显示当前配置 |
                | `v-processes` | 查看进程状态 | | |

                **Claude 配置**
                | 命令 | 说明 | 命令 | 说明 |
                |------|------|------|------|
                | `v-model <name>` | 设置模型 | `v-thinking <级别>` | 推理模式 |
                | `v-claude <path>` | 安装路径 | `v-api <url>` | API 地址 |
                | `v-key <key>` | API Key | `v-cd <path> [clear]` | 切换目录 |
                | `v-config <key> [url] [model]` | 一键配置 | `v-switch <name>` | 切换配置 |
                | `v-maxproc <数量>` | 最大进程数 | `v-idle <秒>` | 空闲超时 |
                | `v-prefer <new|queue>` | 排队策略 | | |

                **推理模式** (v-thinking)
                | 级别 | 说明 | 级别 | 说明 |
                |------|------|------|------|
                | `low` | 1k tokens | `medium` | 5k tokens |
                | `high` | 10k tokens | `max` | 32k tokens |
                | `off` | 关闭推理 | `default` | 查看默认配置 |

                **通知配置** (true/false)
                | 命令 | 说明 | 命令 | 说明 |
                |------|------|------|------|
                | `v-notify` | 消息状态通知 | `v-tools` | 工具调用通知 |
                | `v-fileread` | 文件读取通知 | `v-fileedit` | 文件编辑通知 |
                | `v-subtask` | 子任务状态通知 | `v-subtask-done` | 子任务完成通知 |
                | `v-token` | 查看/开关Token统计 | | |

                **会话管理**
                | 命令 | 说明 | 命令 | 说明 |
                |------|------|------|------|
                | `v-new` / `v-clear` | 新建/清空会话 | `v-sessions` | 列出会话 |
                | `v-session <id>` | 切换会话 | `v-delete <id>` | 删除会话 |
                | `v-save <name>` | 保存配置 | `v-profiles` | 列出预设 |

                **关键词过滤**
                | 命令 | 说明 | 命令 | 说明 |
                |------|------|------|------|
                | `v-block <词>` | 添加过滤 | `v-unblock <词>` | 移除过滤 |

                **进程管理**
                | 命令 | 说明 | 命令 | 说明 |
                |------|------|------|------|
                | `v-processes` | 查看进程状态 | `v-maxproc <数量>` | 设置最大进程数(1-10) |
                | `v-idle <秒>` | 设置空闲超时 | `v-prefer new/queue` | 排队策略 |
                """;
        ilinkService.sendText(userId, help, contextToken);
    }

    private void showStatus(String userId, String contextToken) {
        String sessionId = claudeApiService.getSessionId(userId);
        int sessionCount = claudeApiService.getSessionHistory(userId).size();
        ClaudeApiService.TokenUsage usage = claudeApiService.getTokenUsage(userId);
        String activeProfile = configService.getActiveProfile();
        int processCount = claudeApiService.getProcessCount(userId);
        String quotaStatus = quotaManager.getStatus(userId);

        String status = String.format("""
                **VibeWeChat 系统状态**

                | 配置项 | 值 |
                |--------|-----|
                | API | `%s` |
                | 模型 | `%s` |
                | 路径 | `%s` |
                | 推理模式 | %s |
                | 活跃配置 | `%s` |

                **当前会话**

                | 项目 | 值 |
                |------|-----|
                | 会话ID | `%s` |
                | 历史会话 | %d 个 |
                | 工作目录 | `%s` |

                **进程配置**

                | 项目 | 值 |
                |------|-----|
                | 进程数 | %d / %d |
                | 空闲超时 | %s |
                | 排队策略 | %s |
                | 配额 | %s |

                **Token 用量**

                | 输入 | 输出 | 总计 |
                |------|------|------|
                | %s | %s | %s |

                **通知配置** (使用 `v-filter` 修改)

                | 通知项 | 状态 | 说明 |
                |--------|------|------|
                | 消息状态 | %s | 收到消息时提示 |
                | 工具调用 | %s | 显示工具调用详情 |
                | 文件读取 | %s | 显示文件读取操作 |
                | 文件编辑 | %s | 显示文件编辑操作 |
                | 子任务状态 | %s | 子任务创建/更新通知 |
                | 子任务完成 | %s | 子任务完成通知 |
                | 任务完成 | %s | 任务完成摘要 |
                | Token统计 | %s | 显示Token消耗 |

                **其他**

                | 项目 | 值 |
                |------|-----|
                | 关键词过滤 | %d 个 |
                """,
                claudeApiService.getApiUrl() != null ? claudeApiService.getApiUrl() : "未设置",
                claudeApiService.getModel() != null ? claudeApiService.getModel() : "未设置",
                claudeApiService.getInstallPath() != null ? claudeApiService.getInstallPath() : "自动检测",
                claudeApiService.isThinkingEnabled() ? thinkingConfig.getLevel() + " (" + thinkingConfig.getCurrentBudgetTokens() + " tokens)" : "❌ 关闭",
                activeProfile != null && !activeProfile.isEmpty() ? activeProfile : "无",
                sessionId != null ? sessionId : "无",
                sessionCount,
                claudeConfig().getWorkDir() != null && !claudeConfig().getWorkDir().isEmpty() ? claudeConfig().getWorkDir() : System.getProperty("user.dir"),
                processCount,
                claudeApiService.getMaxProcessesPerUser(),
                formatDuration(claudeApiService.getProcessIdleTimeoutMs()),
                claudeApiService.isPreferNewProcess() ? "优先新进程" : "优先排队",
                quotaStatus,
                formatTokens(usage.getInputTokens()),
                formatTokens(usage.getOutputTokens()),
                formatTokens(usage.getTotalTokens()),
                filterConfig.isShowMessageStatus() ? "✅" : "❌",
                filterConfig.isShowToolCalls() ? "✅" : "❌",
                filterConfig.isShowFileRead() ? "✅" : "❌",
                filterConfig.isShowFileEdit() ? "✅" : "❌",
                filterConfig.isShowSubtaskStatus() ? "✅" : "❌",
                filterConfig.isShowSubtaskCompletion() ? "✅" : "❌",
                filterConfig.isShowTaskCompletion() ? "✅" : "❌",
                filterConfig.isShowTokenUsage() ? "✅" : "❌",
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

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds >= 3600) {
            return (seconds / 3600) + "小时";
        } else if (seconds >= 60) {
            return (seconds / 60) + "分钟";
        }
        return seconds + "秒";
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
            case "subtask" -> filterConfig.setShowSubtaskStatus(Boolean.parseBoolean(value));
            case "subtask-done" -> filterConfig.setShowSubtaskCompletion(Boolean.parseBoolean(value));
            case "token" -> filterConfig.setShowTokenUsage(Boolean.parseBoolean(value));
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
            case "subtask" -> filterConfig.setShowSubtaskStatus(newValue);
            case "subtask-done" -> filterConfig.setShowSubtaskCompletion(newValue);
            case "token" -> filterConfig.setShowTokenUsage(newValue);
        }
        configService.saveConfig();
        ilinkService.sendText(userId, type + " = " + (newValue ? "on" : "off"), contextToken);
    }

    private void handleSessionCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2 && claudeApiService.switchSession(userId, parts[1])) {
            ilinkService.sendText(userId, "已切换: " + parts[1] + "\n新请求将恢复此会话", contextToken);
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
        ilinkService.sendText(userId, "微信限制每条消息24h内最多回复10条\n当前配额: " + quotaManager.getStatus(userId), contextToken);
    }

    private void handleSwitchCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2 && configService.switchProfile(parts[1])) {
            if (claudeApiService.hasBusyProcesses(userId)) {
                ilinkService.sendText(userId, "已切换: " + parts[1] + "\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置", contextToken);
            } else {
                claudeApiService.destroyAllProcesses(userId);
                ilinkService.sendText(userId, "已切换: " + parts[1] + "\n进程已重启\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
            }
        } else {
            ilinkService.sendText(userId, "用法: v-switch <name>\n使用 v-profiles 查看", contextToken);
        }
    }

    private void handleSaveCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2) {
            configService.saveProfile(parts[1]);
            ilinkService.sendText(userId, "已保存: " + parts[1] + "\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
        } else {
            ilinkService.sendText(userId, "用法: v-save <name>", contextToken);
        }
    }

    private void handleProfilesCommand(String userId, String contextToken) {
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
            if ("default".equals(level) || "reset".equals(level)) {
                // 显示当前默认配置
                String current = thinkingConfig.isEnabled() ? thinkingConfig.getLevel() + " (" + thinkingConfig.getCurrentBudgetTokens() + " tokens)" : "关闭";
                ilinkService.sendText(userId, "当前默认推理模式: " + current + "\n\n用法: v-thinking <级别> [default]\n级别: " + String.join(", ", thinkingConfig.getLevels()) + ", off\n添加 default 设置为新进程的默认配置", contextToken);
            } else if (level.equals("off")) {
                thinkingConfig.setLevel("off");
                configService.saveConfig();
                if (claudeApiService.hasBusyProcesses(userId)) {
                    ilinkService.sendText(userId, "推理模式: 关闭\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置\n当前进程将在完成后销毁", contextToken);
                } else {
                    claudeApiService.destroyAllProcesses(userId);
                    ilinkService.sendText(userId, "推理模式: 关闭\n进程已重启\n新进程将使用此配置\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
                }
            } else if (thinkingConfig.getLevels().contains(level)) {
                thinkingConfig.setLevel(level);
                configService.saveConfig();
                if (claudeApiService.hasBusyProcesses(userId)) {
                    ilinkService.sendText(userId, "推理模式: " + level + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置\n当前进程将在完成后销毁", contextToken);
                } else {
                    claudeApiService.destroyAllProcesses(userId);
                    ilinkService.sendText(userId, "推理模式: " + level + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n进程已重启\n新进程将使用此配置\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
                }
            } else {
                ilinkService.sendText(userId, "无效级别，可选: " + String.join(", ", thinkingConfig.getLevels()) + ", off", contextToken);
            }
        } else {
            String newLevel = thinkingConfig.cycleLevel();
            configService.saveConfig();
            if (claudeApiService.hasBusyProcesses(userId)) {
                ilinkService.sendText(userId, "推理模式: " + newLevel + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n⚠️ 有任务运行中，配置已保存，新进程将使用此配置", contextToken);
            } else {
                claudeApiService.destroyAllProcesses(userId);
                ilinkService.sendText(userId, "推理模式: " + newLevel + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n进程已重启\n新进程将使用此配置\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
            }
        }
    }

    private void handleMaxProcCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2) {
            try {
                int maxProc = Integer.parseInt(parts[1]);
                if (maxProc < 1 || maxProc > 10) {
                    ilinkService.sendText(userId, "无效值，范围: 1-10", contextToken);
                    return;
                }
                claudeApiService.setMaxProcessesPerUser(maxProc);
                configService.saveConfig();
                ilinkService.sendText(userId, "最大进程数: " + maxProc + "\n超出的空闲进程将在下次清理时销毁", contextToken);
            } catch (NumberFormatException e) {
                ilinkService.sendText(userId, "用法: v-maxproc <数量>\n当前: " + claudeApiService.getMaxProcessesPerUser(), contextToken);
            }
        } else {
            ilinkService.sendText(userId, "用法: v-maxproc <数量>\n当前: " + claudeApiService.getMaxProcessesPerUser(), contextToken);
        }
    }

    private void handleIdleTimeoutCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2) {
            try {
                long seconds = Long.parseLong(parts[1]);
                if (seconds < 60) {
                    ilinkService.sendText(userId, "最小值: 60秒", contextToken);
                    return;
                }
                long ms = seconds * 1000;
                claudeApiService.setProcessIdleTimeoutMs(ms);
                configService.saveConfig();
                String display = seconds >= 3600 ? (seconds / 3600) + "小时" : (seconds / 60) + "分钟";
                ilinkService.sendText(userId, "空闲超时: " + display + "\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
            } catch (NumberFormatException e) {
                long currentSec = claudeApiService.getProcessIdleTimeoutMs() / 1000;
                String current = currentSec >= 3600 ? (currentSec / 3600) + "小时" : (currentSec / 60) + "分钟";
                ilinkService.sendText(userId, "用法: v-idle <秒>\n当前: " + current, contextToken);
            }
        } else {
            long currentSec = claudeApiService.getProcessIdleTimeoutMs() / 1000;
            String current = currentSec >= 3600 ? (currentSec / 3600) + "小时" : (currentSec / 60) + "分钟";
            ilinkService.sendText(userId, "用法: v-idle <秒>\n当前: " + current, contextToken);
        }
    }

    private void handlePreferCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2) {
            String value = parts[1].toLowerCase();
            if ("new".equals(value) || "true".equals(value) || "1".equals(value)) {
                claudeApiService.setPreferNewProcess(true);
                configService.saveConfig();
                ilinkService.sendText(userId, "排队策略: 优先创建新进程\n进程未满时自动创建新进程（自动加载上下文）", contextToken);
            } else if ("queue".equals(value) || "false".equals(value) || "0".equals(value)) {
                claudeApiService.setPreferNewProcess(false);
                configService.saveConfig();
                ilinkService.sendText(userId, "排队策略: 优先排队等待\n进程未满时排队等待空闲进程", contextToken);
            } else {
                ilinkService.sendText(userId, "用法: v-prefer <new|queue>\n当前: " + (claudeApiService.isPreferNewProcess() ? "new (优先新进程)" : "queue (优先排队)"), contextToken);
            }
        } else {
            ilinkService.sendText(userId, "用法: v-prefer <new|queue>\n当前: " + (claudeApiService.isPreferNewProcess() ? "new (优先新进程)" : "queue (优先排队)"), contextToken);
        }
    }

    private void handleStopCommand(String userId, String contextToken) {
        int busyCount = claudeApiService.getBusyProcessCount(userId);
        if (busyCount == 0) {
            ilinkService.sendText(userId, "当前没有正在运行的任务", contextToken);
            return;
        }
        claudeApiService.forceStopAll(userId);
        ilinkService.sendText(userId, "已强制停止 " + busyCount + " 个忙碌进程", contextToken);
    }

    private com.chengxun.vibewechat.config.ClaudeConfig claudeConfig() {
        return claudeApiService.getClaudeConfig();
    }
}
