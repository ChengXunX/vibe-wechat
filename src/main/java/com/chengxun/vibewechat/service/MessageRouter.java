package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.FilterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private StringRedisTemplate redisTemplate;

    private final Map<String, AtomicInteger> messageCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // vibe-wechat 配置命令前缀
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

    @EventListener
    public void handleIlInkMessage(IlInkService.IlInkMessageEvent event) {
        handleMessage(event.getUserId(), event.getContent(), event.getContextToken());
    }

    public void handleMessage(String userId, String message, String contextToken) {
        // 检查是否为 vibe-wechat 配置命令（v- 开头）
        if (message.startsWith(V_PREFIX)) {
            handleVibeCommand(userId, message, contextToken);
            return;
        }

        // 检查消息限制
        if (!checkMessageLimit(userId)) {
            ilinkService.sendText(userId, "消息次数已达上限，请稍后再试", contextToken);
            return;
        }

        // 发送输入状态
        ilinkService.sendTyping(userId);

        // 转发给 Claude
        String response = claudeApiService.sendMessage(userId, message);

        // 停止输入状态
        ilinkService.sendStopTyping(userId);

        if (response != null && !response.isEmpty()) {
            ilinkService.sendText(userId, response, contextToken);
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
            case V_NEW -> handleNewSession(userId, contextToken);
            case V_CLEAR -> handleClearSession(userId, contextToken);
            case V_SESSIONS -> handleListSessions(userId, contextToken);
            case V_LIMIT -> handleLimitCommand(userId, parts, contextToken);
            case V_API -> handleApiCommand(userId, parts, contextToken);
            case V_KEY -> handleKeyCommand(userId, parts, contextToken);
            case V_MODEL -> handleModelCommand(userId, parts, contextToken);
            case V_TOOLS -> handleQuickToggle(userId, "tools", parts, contextToken);
            case V_FILEREAD -> handleQuickToggle(userId, "fileread", parts, contextToken);
            case V_FILEEDIT -> handleQuickToggle(userId, "fileedit", parts, contextToken);
            case V_TOKEN -> handleTokenCommand(userId, parts, contextToken);
            case V_CLAUDE -> handleClaudePathCommand(userId, parts, contextToken);
            case V_CONFIG -> handleConfigCommand(userId, parts, contextToken);
            default -> ilinkService.sendText(userId, "未知命令: " + cmd + "\n输入 v-help 查看所有命令", contextToken);
        }
    }

    private void showHelp(String userId, String contextToken) {
        String help = """
                **vibe-wechat 命令列表**

                *说明: 微信限制每条用户消息最多回复10次，可通过 v-limit 设置内部限制（默认不限制）*

                `v-help`          显示此帮助
                `v-status`        显示当前配置状态

                **Claude 配置**
                `v-config <key> [model]` 一键配置 API Key 和模型
                `v-api <url>`     设置 Claude API 地址（默认: api.anthropic.com）
                `v-key <key>`     设置 Claude API Key
                `v-model <name>`  设置 Claude 模型（默认: claude-sonnet-4-20250514）
                `v-claude <path>` 设置 Claude 安装路径

                **快捷过滤**
                `v-tools`         开关工具类消息（如grep、find等）
                `v-fileread`      开关读取文件类消息（如Read、cat等）
                `v-fileedit`      开关编辑文件类消息（如Edit、Write等）

                **高级过滤**
                `v-filter <key> <value>`  配置消息过滤

                **消息配置**
                `v-limit <n>`     设置每小时最大消息数（默认: 不限制）
                `v-token`         查看/开关 token 消耗统计

                **会话管理**
                `v-new`           新建 Claude 会话
                `v-clear`         清空当前会话
                `v-sessions`      列出会话
                `v-session <id>`  切换会话

                **过滤配置项**
                - tools       工具调用 (true/false)
                - fileread    读取文件 (true/false)
                - fileedit    编辑文件 (true/false)
                - files       所有文件操作 (true/false)
                - decisions   决策消息 (true/false)
                - results     结果消息 (true/false)
                - subtasks    子任务完成 (true/false)
                - tasks       任务完成 (true/false)
                - duration    任务耗时 (true/false)
                - token       Token消耗 (true/false)
                """;
        ilinkService.sendText(userId, help, contextToken);
    }

    private void showStatus(String userId, String contextToken) {
        String apiKey = claudeApiService.getApiKey();
        String maskedKey = (apiKey != null && apiKey.length() > 8) ?
                apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4) : "未配置";

        String on = "✅ 显示";
        String off = "❌ 隐藏";

        String status = String.format("""
                **当前配置状态**

                ════════════════════════
                **Claude 配置**
                ════════════════════════
                API地址: `%s`
                API Key: `%s`
                模型: `%s`
                安装路径: `%s`

                ════════════════════════
                **消息过滤**
                ════════════════════════
                | 配置项 | 状态 |
                |--------|------|
                | 工具调用 | %s |
                | 读取文件 | %s |
                | 编辑文件 | %s |
                | 文件操作 | %s |
                | 决策消息 | %s |
                | 结果消息 | %s |
                | 子任务 | %s |
                | 任务完成 | %s |
                | 耗时 | %s |
                | Token统计 | %s |

                ════════════════════════
                **限制**
                ════════════════════════
                每小时消息数: %d

                ════════════════════════
                **Token使用**
                ════════════════════════
                %s
                """,
                claudeApiService.getApiUrl(),
                maskedKey,
                claudeApiService.getModel(),
                claudeApiService.getInstallPath() != null ? claudeApiService.getInstallPath() : "自动检测",
                filterConfig.isShowToolCalls() ? on : off,
                filterConfig.isShowFileRead() ? on : off,
                filterConfig.isShowFileEdit() ? on : off,
                filterConfig.isShowFileOperations() ? on : off,
                filterConfig.isShowDecisionsOnly() ? on : off,
                filterConfig.isShowResultsOnly() ? on : off,
                filterConfig.isShowSubtaskCompletion() ? on : off,
                filterConfig.isShowTaskCompletion() ? on : off,
                filterConfig.isShowTaskDuration() ? on : off,
                filterConfig.isShowTokenUsage() ? on : off,
                filterConfig.getMaxMessagesPerUser(),
                claudeApiService.getTokenUsageSummary(userId));
        ilinkService.sendText(userId, status, contextToken);
    }

    private void handleFilterCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 3) {
            ilinkService.sendText(userId, "用法: v-filter <key> <value>\n输入 v-help 查看可配置项", contextToken);
            return;
        }

        String key = parts[1].toLowerCase();
        String value = parts[2].toLowerCase();

        switch (key) {
            case "tools" -> filterConfig.setShowToolCalls(Boolean.parseBoolean(value));
            case "fileread" -> filterConfig.setShowFileRead(Boolean.parseBoolean(value));
            case "fileedit" -> filterConfig.setShowFileEdit(Boolean.parseBoolean(value));
            case "files" -> filterConfig.setShowFileOperations(Boolean.parseBoolean(value));
            case "decisions" -> filterConfig.setShowDecisionsOnly(Boolean.parseBoolean(value));
            case "results" -> filterConfig.setShowResultsOnly(Boolean.parseBoolean(value));
            case "subtasks" -> filterConfig.setShowSubtaskCompletion(Boolean.parseBoolean(value));
            case "tasks" -> filterConfig.setShowTaskCompletion(Boolean.parseBoolean(value));
            case "duration" -> filterConfig.setShowTaskDuration(Boolean.parseBoolean(value));
            default -> {
                ilinkService.sendText(userId, "未知配置项: " + key, contextToken);
                return;
            }
        }

        ilinkService.sendText(userId, "已更新: " + key + " = " + value, contextToken);
    }

    private void handleSessionCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-session <session_id>", contextToken);
            return;
        }

        String sessionId = parts[1];
        ilinkService.sendText(userId, "已切换到会话: " + sessionId, contextToken);
    }

    private void handleNewSession(String userId, String contextToken) {
        claudeApiService.clearHistory(userId);
        ilinkService.sendText(userId, "已创建新会话", contextToken);
    }

    private void handleListSessions(String userId, String contextToken) {
        ilinkService.sendText(userId, "当前会话: " + userId, contextToken);
    }

    private void handleClearSession(String userId, String contextToken) {
        claudeApiService.clearHistory(userId);
        ilinkService.sendText(userId, "会话已清空", contextToken);
    }

    private void handleLimitCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-limit <数量>\n当前限制: " + filterConfig.getMaxMessagesPerUser(), contextToken);
            return;
        }

        try {
            int limit = Integer.parseInt(parts[1]);
            filterConfig.setMaxMessagesPerUser(limit);
            ilinkService.sendText(userId, "已设置每小时消息限制: " + limit, contextToken);
        } catch (NumberFormatException e) {
            ilinkService.sendText(userId, "请输入有效数字", contextToken);
        }
    }

    private void handleApiCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-api <url>\n当前API: " + claudeApiService.getApiUrl(), contextToken);
            return;
        }

        String url = parts[1];
        claudeApiService.setApiUrl(url);
        ilinkService.sendText(userId, "已设置 Claude API 地址: " + url, contextToken);
    }

    private void handleKeyCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-key <api-key>", contextToken);
            return;
        }

        String key = parts[1];
        claudeApiService.setApiKey(key);
        ilinkService.sendText(userId, "已设置 Claude API Key", contextToken);
    }

    private void handleModelCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-model <model-name>\n当前模型: " + claudeApiService.getModel(), contextToken);
            return;
        }

        String model = parts[1];
        claudeApiService.setModel(model);
        ilinkService.sendText(userId, "已设置 Claude 模型: " + model, contextToken);
    }

    private void handleQuickToggle(String userId, String type, String[] parts, String contextToken) {
        boolean newValue = true;
        if (parts.length >= 2) {
            newValue = Boolean.parseBoolean(parts[1].toLowerCase());
        } else {
            // 切换当前状态
            newValue = !getCurrentFilterState(type);
        }

        switch (type) {
            case "tools" -> filterConfig.setShowToolCalls(newValue);
            case "fileread" -> filterConfig.setShowFileRead(newValue);
            case "fileedit" -> filterConfig.setShowFileEdit(newValue);
        }

        ilinkService.sendText(userId, type + " 已设置为: " + (newValue ? "显示" : "隐藏"), contextToken);
    }

    private boolean getCurrentFilterState(String type) {
        return switch (type) {
            case "tools" -> filterConfig.isShowToolCalls();
            case "fileread" -> filterConfig.isShowFileRead();
            case "fileedit" -> filterConfig.isShowFileEdit();
            default -> false;
        };
    }

    private void handleTokenCommand(String userId, String[] parts, String contextToken) {
        if (parts.length >= 2) {
            // 设置 token 统计开关
            boolean value = Boolean.parseBoolean(parts[1].toLowerCase());
            filterConfig.setShowTokenUsage(value);
            ilinkService.sendText(userId, "Token 统计已设置为: " + (value ? "显示" : "隐藏"), contextToken);
        } else {
            // 显示当前 token 使用情况
            String usage = claudeApiService.getTokenUsageSummary(userId);
            ilinkService.sendText(userId, "当前 Token 使用:\n" + usage, contextToken);
        }
    }

    private void handleClaudePathCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            String currentPath = claudeApiService.getInstallPath();
            ilinkService.sendText(userId, "用法: v-claude <path>\n当前路径: " + (currentPath != null ? currentPath : "自动检测"), contextToken);
            return;
        }

        String path = parts[1];

        // 检查是否是 install 命令
        if (path.equals("install")) {
            installClaude(userId, contextToken);
            return;
        }

        // 检查路径是否存在
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            claudeApiService.setInstallPath(path);
            // 更新 Claude 配置文件
            updateClaudeConfig(userId, path, contextToken);
            ilinkService.sendText(userId, "已设置 Claude 安装路径: " + path + "\n配置已更新到 ~/.claude/settings.json", contextToken);
        } else {
            ilinkService.sendText(userId, "路径不存在: " + path + "\n\n是否安装 Claude？\n回复 v-claude install 进行安装", contextToken);
        }
    }

    private void updateClaudeConfig(String userId, String installPath, String contextToken) {
        try {
            String configPath = System.getProperty("user.home") + "/.claude/settings.json";
            java.io.File configFile = new java.io.File(configPath);

            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));

                // 更新 ANTHROPIC_BASE_URL
                String apiUrl = claudeApiService.getApiUrl();
                if (apiUrl != null && !apiUrl.isEmpty()) {
                    if (content.contains("\"ANTHROPIC_BASE_URL\"")) {
                        content = content.replaceAll("\"ANTHROPIC_BASE_URL\":\"[^\"]*\"", "\"ANTHROPIC_BASE_URL\":\"" + apiUrl + "\"");
                    } else {
                        content = content.replaceFirst("\\{", "{\"env\":{\"ANTHROPIC_BASE_URL\":\"" + apiUrl + "\",");
                    }
                }

                // 更新 ANTHROPIC_AUTH_TOKEN
                String apiKey = claudeApiService.getApiKey();
                if (apiKey != null && !apiKey.isEmpty()) {
                    if (content.contains("\"ANTHROPIC_AUTH_TOKEN\"")) {
                        content = content.replaceAll("\"ANTHROPIC_AUTH_TOKEN\":\"[^\"]*\"", "\"ANTHROPIC_AUTH_TOKEN\":\"" + apiKey + "\"");
                    } else if (content.contains("\"env\"")) {
                        content = content.replaceFirst("\\{\"env\":\\{", "{\"env\":{\"ANTHROPIC_AUTH_TOKEN\":\"" + apiKey + "\",");
                    }
                }

                java.nio.file.Files.write(configFile.toPath(), content.getBytes());
                ilinkService.sendText(userId, "Claude 配置已更新", contextToken);
            } else {
                ilinkService.sendText(userId, "Claude 配置文件不存在，已跳过", contextToken);
            }
        } catch (Exception e) {
            ilinkService.sendText(userId, "更新配置失败: " + e.getMessage(), contextToken);
        }
    }

    private void installClaude(String userId, String contextToken) {
        ilinkService.sendText(userId, "正在安装 Claude，请稍候...", contextToken);

        try {
            // 使用 npm 安装 Claude
            ProcessBuilder pb = new ProcessBuilder("npm", "install", "-g", "@anthropic-ai/claude-code");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                claudeApiService.setInstallPath("/usr/bin/claude");
                ilinkService.sendText(userId, "Claude 安装成功!\n路径: /usr/bin/claude", contextToken);
            } else {
                ilinkService.sendText(userId, "Claude 安装失败:\n" + output, contextToken);
            }
        } catch (Exception e) {
            ilinkService.sendText(userId, "安装失败: " + e.getMessage(), contextToken);
        }
    }

    private void handleConfigCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            String help = """
                    **一键配置 Claude**

                    用法: `v-config <api_key> [model]`

                    示例:
                    `v-config sk-xxx1234567890`
                    `v-config sk-xxx1234567890 claude-sonnet-4-20250514`

                    说明:
                    - 第一个参数为 API Key（必填）
                    - 第二个参数为模型名称（可选，默认: claude-sonnet-4-20250514）
                    """;
            ilinkService.sendText(userId, help, contextToken);
            return;
        }

        String apiKey = parts[1];
        String model = parts.length > 2 ? parts[2] : "claude-sonnet-4-20250514";

        // 设置 API Key
        claudeApiService.setApiKey(apiKey);

        // 设置模型
        claudeApiService.setModel(model);

        // 更新 Claude 配置文件
        try {
            String configPath = System.getProperty("user.home") + "/.claude/settings.json";
            java.io.File configFile = new java.io.File(configPath);

            if (configFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configFile.toPath()));

                // 更新 ANTHROPIC_AUTH_TOKEN
                if (content.contains("\"ANTHROPIC_AUTH_TOKEN\"")) {
                    content = content.replaceAll("\"ANTHROPIC_AUTH_TOKEN\":\"[^\"]*\"", "\"ANTHROPIC_AUTH_TOKEN\":\"" + apiKey + "\"");
                } else if (content.contains("\"env\"")) {
                    content = content.replaceFirst("\\{\"env\":\\{", "{\"env\":{\"ANTHROPIC_AUTH_TOKEN\":\"" + apiKey + "\",");
                }

                // 更新 ANTHROPIC_MODEL
                if (content.contains("\"ANTHROPIC_MODEL\"")) {
                    content = content.replaceAll("\"ANTHROPIC_MODEL\":\"[^\"]*\"", "\"ANTHROPIC_MODEL\":\"" + model + "\"");
                } else if (content.contains("\"env\"")) {
                    content = content.replaceFirst("\\{\"env\":\\{", "{\"env\":{\"ANTHROPIC_MODEL\":\"" + model + "\",");
                }

                java.nio.file.Files.write(configFile.toPath(), content.getBytes());
            }
        } catch (Exception e) {
            log.error("Failed to update Claude config", e);
        }

        String maskedKey = apiKey.length() > 8 ?
                apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4) : apiKey;

        ilinkService.sendText(userId, "Claude 配置已更新:\n- API Key: " + maskedKey + "\n- 模型: " + model, contextToken);
    }

    private boolean checkMessageLimit(String userId) {
        AtomicInteger count = messageCounts.computeIfAbsent(userId, k -> new AtomicInteger(0));
        if (count.get() >= filterConfig.getMaxMessagesPerUser()) {
            return false;
        }
        count.incrementAndGet();

        // 每小时重置计数
        scheduler.schedule(() -> messageCounts.remove(userId), 1, TimeUnit.HOURS);
        return true;
    }
}
