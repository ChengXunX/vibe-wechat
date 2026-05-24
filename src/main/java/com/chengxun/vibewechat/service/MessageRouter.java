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

    @Autowired
    private ConfigService configService;

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
    private static final String V_CD = "v-cd";
    private static final String V_BLOCK = "v-block";
    private static final String V_UNBLOCK = "v-unblock";
    private static final String V_NOTIFY = "v-notify";
    private static final String V_SWITCH = "v-switch";
    private static final String V_SAVE = "v-save";
    private static final String V_PROFILES = "v-profiles";
    private static final String V_THINKING = "v-thinking";

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

        // 检查是否为彩蛋关键词
        String easterEgg = checkEasterEgg(message);
        if (easterEgg != null) {
            ilinkService.sendText(userId, easterEgg, contextToken);
            return;
        }

        // 检查消息限制
        if (!checkMessageLimit(userId)) {
            ilinkService.sendText(userId, "消息次数已达上限，请稍后再试", contextToken);
            return;
        }

        // 发送输入状态
        ilinkService.sendTyping(userId);

        // 如果开启了消息状态通知，发送确认消息
        if (filterConfig.isShowMessageStatus()) {
            String sessionId = claudeApiService.getSessionId(userId);
            String model = claudeApiService.getModel();
            String workDir = System.getProperty("user.dir");
            String statusMsg = String.format(
                "✅ 收到消息，开始处理...\n\n" +
                "📋 会话ID: `%s`\n" +
                "🤖 模型: `%s`\n" +
                "📁 工作目录: `%s`",
                sessionId != null ? sessionId.substring(0, Math.min(8, sessionId.length())) + "..." : "新会话",
                model,
                workDir
            );
            ilinkService.sendText(userId, statusMsg, contextToken);
        }

        long startTime = System.currentTimeMillis();

        try {
            // 转发给 Claude
            String response = claudeApiService.sendMessage(userId, message);

            // 检查是否包含屏蔽关键词
            boolean blocked = false;
            for (String keyword : filterConfig.getBlockedKeywords()) {
                if (response != null && response.contains(keyword)) {
                    blocked = true;
                    break;
                }
            }

            if (!blocked) {
                // 检查是否接近消息限制，添加提示
                if (isNearLimit(userId) && filterConfig.isShowMessageStatus()) {
                    long duration = System.currentTimeMillis() - startTime;
                    String summary = claudeApiService.getTaskCompletionSummary(userId, duration);
                    String warning = "> ⚠️ 消息次数即将达到上限，Claude 任务完成后将发送最后一条消息\n\n" + response + "\n\n" + summary;
                    ilinkService.sendText(userId, warning, contextToken);
                } else if (response != null && !response.isEmpty()) {
                    ilinkService.sendText(userId, response, contextToken);
                }
            }
        } finally {
            // 无论如何都停止输入状态
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
            case V_NEW -> handleNewSession(userId, contextToken);
            case V_CLEAR -> handleClearSession(userId, contextToken);
            case V_SESSIONS -> handleListSessions(userId, contextToken);
            case V_LIMIT -> handleLimitCommand(userId, parts, contextToken);
            case V_API -> handleApiCommand(userId, parts, contextToken);
            case V_KEY -> handleKeyCommand(userId, parts, contextToken);
            case V_MODEL -> handleModelCommand(userId, parts, contextToken);
            case V_CONFIG -> handleConfigCommand(userId, parts, contextToken);
            case V_TOOLS -> handleQuickToggle(userId, "tools", parts, contextToken);
            case V_FILEREAD -> handleQuickToggle(userId, "fileread", parts, contextToken);
            case V_FILEEDIT -> handleQuickToggle(userId, "fileedit", parts, contextToken);
            case V_TOKEN -> handleTokenCommand(userId, parts, contextToken);
            case V_CLAUDE -> handleClaudePathCommand(userId, parts, contextToken);
            case V_CD -> handleCdCommand(userId, parts, contextToken);
            case V_BLOCK -> handleBlockCommand(userId, parts, contextToken);
            case V_UNBLOCK -> handleUnblockCommand(userId, parts, contextToken);
            case V_NOTIFY -> handleNotifyCommand(userId, parts, contextToken);
            case V_SWITCH -> handleSwitchCommand(userId, parts, contextToken);
            case V_SAVE -> handleSaveCommand(userId, parts, contextToken);
            case V_PROFILES -> handleProfilesCommand(userId, parts, contextToken);
            case V_THINKING -> handleThinkingCommand(userId, parts, contextToken);
            default -> ilinkService.sendText(userId, "未知命令: " + cmd + "\n输入 v-help 查看所有命令", contextToken);
        }
    }

    private void showHelp(String userId, String contextToken) {
        String help = """
                **📋 vibe-wechat 命令列表**

                *微信限制每条用户消息24h内最多回复10条，v-limit 设置内部限制*

                ━━━━━━━━━━━━━━━━━━━━━━
                `v-help`          显示此帮助
                `v-status`        显示当前配置状态

                ━━━━━━━━━━━━━━━━━━━━━━
                **🔧 Claude 配置**
                ━━━━━━━━━━━━━━━━━━━━━━
                `v-config <key> [url] [model]` 一键配置
                `v-api <url>`      设置 API 地址
                `v-key <key>`      设置 API Key
                `v-model <name>`   设置模型（支持 [1m] 配置）
                `v-claude <path>`  设置安装路径
                `v-thinking <级别>` 推理模式 (off/low/medium/high)
                `v-switch <name>`  切换预设配置
                `v-save <name>`    保存当前配置
                `v-profiles`       列出所有预设

                ━━━━━━━━━━━━━━━━━━━━━━
                **📁 工作目录**
                ━━━━━━━━━━━━━━━━━━━━━━
                `v-cd <path>`     切换工作目录

                ━━━━━━━━━━━━━━━━━━━━━━
                **⚙️ 消息过滤**
                ━━━━━━━━━━━━━━━━━━━━━━
                `v-tools`         开关工具类消息
                `v-fileread`      开关读取文件消息
                `v-fileedit`      开关编辑文件消息
                `v-filter <key> <value>`  高级过滤

                ━━━━━━━━━━━━━━━━━━━━━━
                **🚫 关键词过滤**
                ━━━━━━━━━━━━━━━━━━━━━━
                `v-block <词>`    添加过滤关键词
                `v-unblock <词>`  移除过滤关键词
                *包含关键词的回复不发送，节省微信通知次数*

                ━━━━━━━━━━━━━━━━━━━━━━
                **📊 消息配置**
                ━━━━━━━━━━━━━━━━━━━━━━
                `v-limit <n>`     本账号内部消息数限制
                `v-token`         Token 使用统计
                `v-notify`        收到消息时发送处理确认
                `v-notify true`   开启状态通知（含会话ID、模型等）
                `v-notify false`  关闭状态通知（仅显示输入中）

                ━━━━━━━━━━━━━━━━━━━━━━
                **💬 会话管理**
                ━━━━━━━━━━━━━━━━━━━━━━
                `v-new`           新建会话
                `v-clear`         清空会话
                `v-sessions`      列出会话
                `v-session <id>`  切换会话
                """;
        ilinkService.sendText(userId, help, contextToken);
    }

    private void showStatus(String userId, String contextToken) {
        String apiKey = claudeApiService.getApiKey();
        String maskedKey = (apiKey != null && apiKey.length() > 8) ?
                apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4) : "未配置";

        String on = "✅";
        String off = "❌";

        String status = String.format("""
                **📋 当前配置状态**

                ━━━━━━━━━━━━━━━━━━━━━━
                **🔧 Claude 配置**
                ━━━━━━━━━━━━━━━━━━━━━━
                API地址 %s
                API Key %s
                模型 %s
                安装路径 %s

                ━━━━━━━━━━━━━━━━━━━━━━
                **📁 工作目录**
                ━━━━━━━━━━━━━━━━━━━━━━
                %s

                ━━━━━━━━━━━━━━━━━━━━━━
                **⚙️ 消息过滤**
                ━━━━━━━━━━━━━━━━━━━━━━
                工具调用 %s
                读取文件 %s
                编辑文件 %s
                文件操作 %s
                决策消息 %s
                结果消息 %s
                子任务 %s
                任务完成 %s
                耗时 %s
                Token统计 %s

                ━━━━━━━━━━━━━━━━━━━━━━
                **📊 限制**
                ━━━━━━━━━━━━━━━━━━━━━━
                每小时消息数 %d

                ━━━━━━━━━━━━━━━━━━━━━━
                **📈 Token使用**
                ━━━━━━━━━━━━━━━━━━━━━━
                %s
                """,
                claudeApiService.getApiUrl(),
                maskedKey,
                claudeApiService.getModel(),
                claudeApiService.getInstallPath() != null ? claudeApiService.getInstallPath() : "自动检测",
                System.getProperty("user.dir"),
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

    private void handleBlockCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            java.util.List<String> keywords = filterConfig.getBlockedKeywords();
            String list = keywords.isEmpty() ? "暂无屏蔽关键词" :
                    "当前屏蔽关键词:\n" + String.join("\n", keywords.stream().map(k -> "- " + k).toList());
            ilinkService.sendText(userId, "用法: v-block <关键词>\n\n" + list, contextToken);
            return;
        }

        String keyword = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        if (!filterConfig.getBlockedKeywords().contains(keyword)) {
            filterConfig.getBlockedKeywords().add(keyword);
            configService.saveConfig();
            ilinkService.sendText(userId, "已添加屏蔽关键词: " + keyword, contextToken);
        } else {
            ilinkService.sendText(userId, "该关键词已存在: " + keyword, contextToken);
        }
    }

    private void handleUnblockCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-unblock <关键词>", contextToken);
            return;
        }

        String keyword = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        if (filterConfig.getBlockedKeywords().remove(keyword)) {
            configService.saveConfig();
            ilinkService.sendText(userId, "已移除屏蔽关键词: " + keyword, contextToken);
        } else {
            ilinkService.sendText(userId, "未找到该关键词: " + keyword, contextToken);
        }
    }

    private void handleSwitchCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            String profiles = configService.getSwitchProfiles();
            String active = configService.getActiveProfile();
            String help = """
                    **🔄 配置切换**

                    当前激活: `%s`

                    可用配置:
                    %s

                    **命令:**
                    `v-switch <name>`  切换到指定配置
                    `v-switch`        显示当前配置

                    **管理:**
                    `v-save <name>`   保存当前配置
                    `v-profiles`      列出所有配置
                    """.formatted(active.isEmpty() ? "无" : active, profiles);
            ilinkService.sendText(userId, help, contextToken);
            return;
        }

        String profileName = parts[1];
        if (configService.switchProfile(profileName)) {
            ilinkService.sendText(userId, "已切换到配置: " + profileName, contextToken);
        } else {
            ilinkService.sendText(userId, "未找到配置: " + profileName, contextToken);
        }
    }

    private void handleSaveCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-save <配置名>\n保存当前配置为指定名称", contextToken);
            return;
        }

        String profileName = parts[1];
        configService.saveProfile(profileName);
        ilinkService.sendText(userId, "已保存配置: " + profileName, contextToken);
    }

    private void handleProfilesCommand(String userId, String[] parts, String contextToken) {
        String profiles = configService.getSwitchProfiles();
        ilinkService.sendText(userId, profiles, contextToken);
    }

    private void handleNotifyCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            String status = filterConfig.isShowMessageStatus() ? "开启" : "关闭";
            ilinkService.sendText(userId, "用法: v-notify true/false\n当前状态: " + status, contextToken);
            return;
        }

        boolean value = Boolean.parseBoolean(parts[1].toLowerCase());
        filterConfig.setShowMessageStatus(value);
        configService.saveConfig();
        ilinkService.sendText(userId, "消息状态通知已设置为: " + (value ? "开启" : "关闭"), contextToken);
    }

    private void handleThinkingCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            String status = claudeApiService.isThinkingEnabled() ? "开启" : "关闭";
            int budget = claudeApiService.getThinkingBudgetTokens();
            ilinkService.sendText(userId, "用法: v-thinking <级别>\n当前状态: " + status + "\n推理预算: " + budget + " tokens\n\n级别:\n- off: 关闭\n- low: 低强度 (5000 tokens)\n- medium: 中强度 (10000 tokens)\n- high: 高强度 (20000 tokens)", contextToken);
            return;
        }

        String level = parts[1].toLowerCase();
        switch (level) {
            case "off", "false", "0" -> {
                claudeApiService.setThinkingEnabled(false);
                configService.saveConfig();
                ilinkService.sendText(userId, "推理模式已关闭", contextToken);
            }
            case "low", "1" -> {
                claudeApiService.setThinkingEnabled(true);
                claudeApiService.setThinkingBudgetTokens(5000);
                configService.saveConfig();
                ilinkService.sendText(userId, "推理模式: 低强度 (5000 tokens)", contextToken);
            }
            case "medium", "2" -> {
                claudeApiService.setThinkingEnabled(true);
                claudeApiService.setThinkingBudgetTokens(10000);
                configService.saveConfig();
                ilinkService.sendText(userId, "推理模式: 中强度 (10000 tokens)", contextToken);
            }
            case "high", "3" -> {
                claudeApiService.setThinkingEnabled(true);
                claudeApiService.setThinkingBudgetTokens(20000);
                configService.saveConfig();
                ilinkService.sendText(userId, "推理模式: 高强度 (20000 tokens)", contextToken);
            }
            default -> ilinkService.sendText(userId, "无效的级别: " + level + "\n可用级别: off, low, medium, high", contextToken);
        }
    }

    private String checkEasterEgg(String message) {
        String lower = message.toLowerCase();
        String[] whoKeywords = {"你是谁", "你叫什么", "what are you", "who are you", "what's your name"};
        String[] helloKeywords = {"你好", "hello", "hi", "hey", "嗨", "在吗", "在不在"};

        for (String keyword : whoKeywords) {
            if (lower.contains(keyword)) {
                return "我是 **Vibe We Chat**，一个微信 ilink 机器人中间件，可以连接 Claude 进行对话。\n\n" +
                       "*由 ChengXun 开发*\n" +
                       "*GitHub: https://github.com/ChengXunX/vibe-wechat*";
            }
        }

        for (String keyword : helloKeywords) {
            if (lower.contains(keyword)) {
                return "你好！我是 **Vibe We Chat** 🤖\n\n" +
                       "我可以帮你连接 Claude 进行对话，发送 `v-help` 查看所有命令。\n\n" +
                       "*GitHub: https://github.com/ChengXunX/vibe-wechat*";
            }
        }

        return null;
    }

    private void handleCdCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            String currentDir = System.getProperty("user.dir");
            ilinkService.sendText(userId, "用法: v-cd <path>\n当前工作目录: " + currentDir, contextToken);
            return;
        }

        String path = parts[1];
        java.io.File dir = new java.io.File(path);

        if (dir.exists() && dir.isDirectory()) {
            System.setProperty("user.dir", dir.getAbsolutePath());
            ilinkService.sendText(userId, "工作目录已切换到: " + dir.getAbsolutePath(), contextToken);
        } else {
            ilinkService.sendText(userId, "目录不存在: " + path, contextToken);
        }
    }

    private void handleConfigCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            String help = """
                    **一键配置 Claude**

                    用法: `v-config <api_key> [api_url] [model]`

                    示例:
                    `v-config sk-xxx1234567890`
                    `v-config sk-xxx1234567890 https://api.anthropic.com`
                    `v-config sk-xxx1234567890 https://api.anthropic.com claude-sonnet-4-20250514`

                    说明:
                    - 第一个参数为 API Key（必填）
                    - 第二个参数为 API 地址（可选，默认: https://api.anthropic.com）
                    - 第三个参数为模型名称（可选）
                    """;
            ilinkService.sendText(userId, help, contextToken);
            return;
        }

        String apiKey = parts[1];
        String apiUrl = parts.length > 2 ? parts[2] : "https://api.anthropic.com";
        String model = parts.length > 3 ? parts[3] : "claude-sonnet-4-20250514";

        // 设置配置
        claudeApiService.setApiKey(apiKey);
        claudeApiService.setApiUrl(apiUrl);
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

        // 保存配置到本地文件
        configService.saveConfig();

        ilinkService.sendText(userId, "Claude 配置已更新:\n- API Key: " + maskedKey + "\n- 模型: " + model, contextToken);
    }

    private final Map<String, Long> messageExpiry = new ConcurrentHashMap<>();

    private boolean checkMessageLimit(String userId) {
        AtomicInteger count = messageCounts.computeIfAbsent(userId, k -> new AtomicInteger(0));

        // 检查是否已过期
        Long expiry = messageExpiry.get(userId);
        if (expiry != null && System.currentTimeMillis() > expiry) {
            count.set(0);
            messageExpiry.remove(userId);
        }

        if (count.get() >= filterConfig.getMaxMessagesPerUser()) {
            return false;
        }
        count.incrementAndGet();

        // 设置24小时过期时间（只在第一次时设置）
        messageExpiry.putIfAbsent(userId, System.currentTimeMillis() + 24 * 60 * 60 * 1000);
        return true;
    }

    private int getMessageCount(String userId) {
        AtomicInteger count = messageCounts.get(userId);
        return count != null ? count.get() : 0;
    }

    private boolean isNearLimit(String userId) {
        return getMessageCount(userId) >= 9;
    }
}
