package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.FilterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    public void handleMessage(String userId, String message) {
        // 检查是否为 vibe-wechat 配置命令（v- 开头）
        if (message.startsWith(V_PREFIX)) {
            handleVibeCommand(userId, message);
            return;
        }

        // 检查消息限制
        if (!checkMessageLimit(userId)) {
            ilinkService.sendText(userId, "消息次数已达上限，请稍后再试");
            return;
        }

        // 发送输入状态
        ilinkService.sendTyping(userId);

        // 转发给 Claude
        String response = claudeApiService.sendMessage(userId, message);
        if (response != null && !response.isEmpty()) {
            ilinkService.sendText(userId, response);
        }
    }

    private void handleVibeCommand(String userId, String command) {
        String[] parts = command.split("\\s+");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case V_HELP -> showHelp(userId);
            case V_FILTER -> handleFilterCommand(userId, parts);
            case V_STATUS -> showStatus(userId);
            case V_SESSION -> handleSessionCommand(userId, parts);
            case V_NEW -> handleNewSession(userId);
            case V_CLEAR -> handleClearSession(userId);
            case V_SESSIONS -> handleListSessions(userId);
            case V_LIMIT -> handleLimitCommand(userId, parts);
            case V_API -> handleApiCommand(userId, parts);
            case V_KEY -> handleKeyCommand(userId, parts);
            case V_MODEL -> handleModelCommand(userId, parts);
            case V_TOOLS -> handleQuickToggle(userId, "tools", parts);
            case V_FILEREAD -> handleQuickToggle(userId, "fileread", parts);
            case V_FILEEDIT -> handleQuickToggle(userId, "fileedit", parts);
            case V_TOKEN -> handleTokenCommand(userId, parts);
            default -> ilinkService.sendText(userId, "未知命令: " + cmd + "\n输入 v-help 查看所有命令");
        }
    }

    private void showHelp(String userId) {
        String help = """
                vibe-wechat 命令列表:

                v-help          显示此帮助
                v-status        显示当前配置状态

                --- Claude 配置 ---
                v-api <url>     设置 Claude API 地址（默认: api.anthropic.com）
                v-key <key>     设置 Claude API Key
                v-model <name>  设置 Claude 模型（默认: claude-sonnet-4-20250514）

                --- 快捷过滤 ---
                v-tools         开关工具类消息（如grep、find等）
                v-fileread      开关读取文件类消息（如Read、cat等）
                v-fileedit      开关编辑文件类消息（如Edit、Write等）

                --- 高级过滤 ---
                v-filter <key> <value>  配置消息过滤

                --- 消息配置 ---
                v-limit <n>     设置每小时最大消息数（默认: 10）
                v-token         查看/开关 token 消耗统计

                --- 会话管理 ---
                v-new           新建 Claude 会话
                v-clear         清空当前会话
                v-sessions      列出会话
                v-session <id>  切换会话

                过滤配置项:
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
        ilinkService.sendText(userId, help);
    }

    private void showStatus(String userId) {
        String apiKey = claudeApiService.getApiKey();
        String maskedKey = (apiKey != null && apiKey.length() > 8) ?
                apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4) : "未配置";

        String status = String.format("""
                当前配置状态:

                --- Claude ---
                API地址: %s
                API Key: %s
                模型: %s

                --- 消息过滤 ---
                工具调用: %s
                读取文件: %s
                编辑文件: %s
                文件操作: %s
                决策消息: %s
                结果消息: %s
                子任务: %s
                任务完成: %s
                耗时: %s
                Token统计: %s

                --- 限制 ---
                每小时消息数: %d

                --- Token使用 ---
                %s
                """,
                claudeApiService.getApiUrl(),
                maskedKey,
                claudeApiService.getModel(),
                filterConfig.isShowToolCalls() ? "显示" : "隐藏",
                filterConfig.isShowFileRead() ? "显示" : "隐藏",
                filterConfig.isShowFileEdit() ? "显示" : "隐藏",
                filterConfig.isShowFileOperations() ? "显示" : "隐藏",
                filterConfig.isShowDecisionsOnly() ? "显示" : "隐藏",
                filterConfig.isShowResultsOnly() ? "显示" : "隐藏",
                filterConfig.isShowSubtaskCompletion() ? "显示" : "隐藏",
                filterConfig.isShowTaskCompletion() ? "显示" : "隐藏",
                filterConfig.isShowTaskDuration() ? "显示" : "隐藏",
                filterConfig.isShowTokenUsage() ? "显示" : "隐藏",
                filterConfig.getMaxMessagesPerUser(),
                claudeApiService.getTokenUsageSummary(userId));
        ilinkService.sendText(userId, status);
    }

    private void handleFilterCommand(String userId, String[] parts) {
        if (parts.length < 3) {
            ilinkService.sendText(userId, "用法: v-filter <key> <value>\n输入 v-help 查看可配置项");
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
                ilinkService.sendText(userId, "未知配置项: " + key);
                return;
            }
        }

        ilinkService.sendText(userId, "已更新: " + key + " = " + value);
    }

    private void handleSessionCommand(String userId, String[] parts) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-session <session_id>");
            return;
        }

        String sessionId = parts[1];
        ilinkService.sendText(userId, "已切换到会话: " + sessionId);
    }

    private void handleNewSession(String userId) {
        claudeApiService.clearHistory(userId);
        ilinkService.sendText(userId, "已创建新会话");
    }

    private void handleListSessions(String userId) {
        ilinkService.sendText(userId, "当前会话: " + userId);
    }

    private void handleClearSession(String userId) {
        claudeApiService.clearHistory(userId);
        ilinkService.sendText(userId, "会话已清空");
    }

    private void handleLimitCommand(String userId, String[] parts) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-limit <数量>\n当前限制: " + filterConfig.getMaxMessagesPerUser());
            return;
        }

        try {
            int limit = Integer.parseInt(parts[1]);
            filterConfig.setMaxMessagesPerUser(limit);
            ilinkService.sendText(userId, "已设置每小时消息限制: " + limit);
        } catch (NumberFormatException e) {
            ilinkService.sendText(userId, "请输入有效数字");
        }
    }

    private void handleApiCommand(String userId, String[] parts) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-api <url>\n当前API: " + claudeApiService.getApiUrl());
            return;
        }

        String url = parts[1];
        claudeApiService.setApiUrl(url);
        ilinkService.sendText(userId, "已设置 Claude API 地址: " + url);
    }

    private void handleKeyCommand(String userId, String[] parts) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-key <api-key>");
            return;
        }

        String key = parts[1];
        claudeApiService.setApiKey(key);
        ilinkService.sendText(userId, "已设置 Claude API Key");
    }

    private void handleModelCommand(String userId, String[] parts) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-model <model-name>\n当前模型: " + claudeApiService.getModel());
            return;
        }

        String model = parts[1];
        claudeApiService.setModel(model);
        ilinkService.sendText(userId, "已设置 Claude 模型: " + model);
    }

    private void handleQuickToggle(String userId, String type, String[] parts) {
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

        ilinkService.sendText(userId, type + " 已设置为: " + (newValue ? "显示" : "隐藏"));
    }

    private boolean getCurrentFilterState(String type) {
        return switch (type) {
            case "tools" -> filterConfig.isShowToolCalls();
            case "fileread" -> filterConfig.isShowFileRead();
            case "fileedit" -> filterConfig.isShowFileEdit();
            default -> false;
        };
    }

    private void handleTokenCommand(String userId, String[] parts) {
        if (parts.length >= 2) {
            // 设置 token 统计开关
            boolean value = Boolean.parseBoolean(parts[1].toLowerCase());
            filterConfig.setShowTokenUsage(value);
            ilinkService.sendText(userId, "Token 统计已设置为: " + (value ? "显示" : "隐藏"));
        } else {
            // 显示当前 token 使用情况
            String usage = claudeApiService.getTokenUsageSummary(userId);
            ilinkService.sendText(userId, "当前 Token 使用:\n" + usage);
        }
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
