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
    // 记录已发送完成通知的 tool_use_id（task_notification 和 tool_result 去重）
    private final java.util.Set<String> completedToolUseIds = ConcurrentHashMap.newKeySet();
    // 记录失败的 Agent tool_use_id -> 错误信息（等待 thinking 降级通知）
    private final Map<String, String> failedAgentErrors = new ConcurrentHashMap<>();

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
    private static final String V_AGENT = "v-agent";
    private static final String V_AGENT_DONE = "v-agent-done";
    private static final String V_PLAN = "v-plan";
    private static final String V_SUBSTEP = "v-substep";
    private static final String V_PROCESSES = "v-processes";
    private static final String V_MAXPROC = "v-maxproc";
    private static final String V_IDLE = "v-idle";
    private static final String V_STOP = "v-stop";
    private static final String V_PROC = "v-proc";
    private static final String V_FORK = "v-fork";
    private static final String V_NEWPROC = "v-newproc";
    private static final String V_DELPROC = "v-delproc";
    private static final String V_REFRESH = "v-refresh";
    private static final String V_DISK_SESSIONS = "v-disk-sessions";
    private static final String V_RESUME = "v-resume";

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
        quotaManager.reset(event.getUserId());

        String userId = event.getUserId();
        String content = event.getContent();
        String contextToken = event.getContextToken();
        boolean isNewUser = event.isNewUser();

        // 欢迎语（新用户立即发送，不阻塞）
        if (isNewUser) {
            String welcome = IlInkService.WELCOME_MESSAGE;
            if (configService.detectConflict()) {
                welcome += "\n\n⚠️ 检测到 Claude settings 文件已被外部修改，已保存为「冲突配置」预设。\n如需恢复，请使用 `v-switch 冲突配置`";
            }
            ilinkService.sendText(userId, welcome, contextToken);
        }

        // v- 命令立即处理（不阻塞）
        if (content.startsWith(V_PREFIX)) {
            handleVibeCommand(userId, content, contextToken);
            return;
        }

        // 彩蛋词立即返回（不阻塞）
        String trimmedMessage = content.trim().toLowerCase();
        if (easterEggs.containsKey(trimmedMessage)) {
            ilinkService.sendText(userId, easterEggs.get(trimmedMessage), contextToken);
            return;
        }

        // Claude 消息处理：异步执行，不阻塞轮询线程
        // 进程组内排队策略：组内有空闲进程则并行执行，全部忙碌则排队等待
        CompletableFuture.runAsync(() -> {
            handleMessage(userId, content, contextToken, isNewUser);
        });
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        claudeApiService.setToolCallback(new ClaudeApiService.ToolCallback() {
            @Override
            public void onToolUse(String userId, String toolName, String toolInput, int processIndex) {
                // 子任务工具有独立通知，跳过工具调用通知避免重复
                if ("TaskCreate".equals(toolName) || "TaskUpdate".equals(toolName)
                        || "TaskGet".equals(toolName) || "TaskList".equals(toolName)) {
                    return;
                }

                String procTag = processIndex > 0 ? " `[进程" + processIndex + "]`" : "";

                // Agent 工具
                if ("Agent".equals(toolName)) {
                    if (!filterConfig.isShowAgentCalls()) return;
                    if (!quotaManager.canSendToolMessage(userId)) return;
                    String contextToken = userContextTokens.get(userId);
                    String agentInfo = formatAgentInput(toolInput);
                    ilinkService.sendText(userId, "🤖 Agent" + procTag + "\n" + agentInfo, contextToken != null ? contextToken : "", "tool");
                    quotaManager.recordMessageSent(userId, "tool");
                    return;
                }

                // PlanMode 工具
                if ("EnterPlanMode".equals(toolName) || "ExitPlanMode".equals(toolName)) {
                    if (!filterConfig.isShowPlanMode()) return;
                    if (!quotaManager.canSendToolMessage(userId)) return;
                    String contextToken = userContextTokens.get(userId);
                    String planInfo = formatPlanModeInput(toolName, toolInput);
                    if ("EnterPlanMode".equals(toolName)) {
                        ilinkService.sendText(userId, "📋 进入规划模式" + procTag + "\n" + planInfo, contextToken != null ? contextToken : "", "tool");
                    } else {
                        ilinkService.sendText(userId, "✅ 退出规划模式" + procTag + "\n" + planInfo, contextToken != null ? contextToken : "", "tool");
                    }
                    quotaManager.recordMessageSent(userId, "tool");
                    return;
                }

                // AskUserQuestion 工具（决策点）
                if ("AskUserQuestion".equals(toolName)) {
                    if (!filterConfig.isShowAgentCalls()) return;
                    if (!quotaManager.canSendToolMessage(userId)) return;
                    String contextToken = userContextTokens.get(userId);
                    String decisionInfo = formatAskUserQuestion(toolInput);
                    ilinkService.sendText(userId, decisionInfo + procTag, contextToken != null ? contextToken : "", "tool");
                    quotaManager.recordMessageSent(userId, "tool");
                    return;
                }

                // 根据工具类型检查对应的过滤开关
                boolean allowed = false;
                if (isFileReadTool(toolName)) {
                    allowed = filterConfig.isShowFileRead();
                } else if (isFileEditTool(toolName)) {
                    allowed = filterConfig.isShowFileEdit();
                } else {
                    allowed = filterConfig.isShowToolCalls();
                }

                if (!allowed) return;

                // 检查配额
                if (!quotaManager.canSendToolMessage(userId)) {
                    log.info("Skipping tool notification due to quota limit for user: {}", userId);
                    return;
                }

                String contextToken = userContextTokens.get(userId);
                String cleanInput = toolInput.replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
                String formattedInput = formatToolInput(toolName, cleanInput);
                String msg;
                if (isFileReadTool(toolName)) {
                    msg = "📖 " + toolName + procTag + "\n" + formattedInput;
                } else if (isFileEditTool(toolName)) {
                    msg = "✏️ " + toolName + procTag + "\n" + formattedInput;
                } else {
                    msg = "🔧 " + toolName + procTag + "\n" + formattedInput;
                }
                ilinkService.sendText(userId, msg, contextToken != null ? contextToken : "", "tool");
                quotaManager.recordMessageSent(userId, "tool");
            }

            @Override
            public void onToolResult(String userId, String toolName, String toolUseId, String result, int processIndex) {
                // Agent 完成通知（去重：task_notification 和 tool_result 只发一次）
                if ("Agent".equals(toolName)) {
                    if (!filterConfig.isShowAgentCompletion()) return;
                    if (completedToolUseIds.contains(toolUseId)) return;
                    completedToolUseIds.add(toolUseId);

                    // 检测 Agent 错误，标记为 pending 等待 thinking 降级通知
                    boolean isError = result.contains("API Error") || result.contains("400")
                            || result.contains("error") || result.contains("Error");
                    if (isError && toolUseId != null) {
                        failedAgentErrors.put(toolUseId, result.trim());
                        log.info("Agent error detected for tool_use_id: {}, deferring notification", toolUseId);
                        return;
                    }

                    if (!quotaManager.canSendToolMessage(userId)) return;
                    String contextToken = userContextTokens.get(userId);
                    String procTag = processIndex > 0 ? " `[进程" + processIndex + "]`" : "";
                    StringBuilder sb = new StringBuilder();
                    sb.append("🤖 Agent 完成").append(procTag);
                    if (!result.isEmpty()) {
                        // 格式化结果内容
                        String formattedResult = formatAgentResult(result);
                        sb.append("\n").append(formattedResult);
                    }
                    ilinkService.sendText(userId, sb.toString(), contextToken != null ? contextToken : "", "sub_result");
                    quotaManager.recordMessageSent(userId, "sub_result");
                }
            }

            @Override
            public void onSubtaskStatus(String userId, String status, boolean isCompleted, int processIndex) {
                boolean shouldNotify = isCompleted ? filterConfig.isShowSubtaskCompletion() : filterConfig.isShowSubtaskStatus();
                if (shouldNotify) {
                    String contextToken = userContextTokens.get(userId);
                    String messageType = isCompleted ? "subtask_completion" : "sub_result";
                    // 子任务完成消息不消耗配额，其他子任务消息消耗配额
                    if (!isCompleted && !quotaManager.canSendToolMessage(userId)) return;
                    String procTag = processIndex > 0 ? " `[进程" + processIndex + "]`" : "";
                    ilinkService.sendText(userId, status + procTag, contextToken != null ? contextToken : "", messageType);
                    if (!isCompleted) {
                        quotaManager.recordMessageSent(userId, messageType);
                    }
                }
            }

            @Override
            public void onDecisionMessage(String userId, String message) {
                // 检测决策类内容，美化后通知用户
                if (!isDecisionMessage(message)) return;
                if (!filterConfig.isShowAgentCalls()) return;
                if (!quotaManager.canSendToolMessage(userId)) return;
                String contextToken = userContextTokens.get(userId);
                String formatted = formatDecisionMessage(message);
                ilinkService.sendText(userId, formatted, contextToken != null ? contextToken : "", "tool");
                quotaManager.recordMessageSent(userId, "tool");
            }

            @Override
            public void onThinking(String userId, String thinking, int processIndex) {
                // 如果有失败的 Agent，发送降级重试通知（含失败原因）
                if (!failedAgentErrors.isEmpty()) {
                    String errorDetail = String.join("\n", failedAgentErrors.values());
                    failedAgentErrors.clear();
                    if (!filterConfig.isShowAgentCalls()) return;
                    if (!quotaManager.canSendToolMessage(userId)) return;
                    String contextToken = userContextTokens.get(userId);
                    String procTag = processIndex > 0 ? " `[进程" + processIndex + "]`" : "";
                    String msg = "⚠️ Agent 调用失败，正在降级重试..." + procTag
                            + "\n原因: " + errorDetail;
                    ilinkService.sendText(userId, msg, contextToken != null ? contextToken : "", "tool");
                    quotaManager.recordMessageSent(userId, "tool");
                }
            }
        });
    }

    /**
     * 检测是否为决策类消息（AI 在展示选项、方案让用户选择）
     */
    private boolean isDecisionMessage(String message) {
        if (message == null || message.length() < 20) return false;
        // 包含明确的选择/方案关键词
        return message.contains("请选择") || message.contains("选哪个")
                || message.contains("方案1") || message.contains("方案一")
                || message.contains("选项") || message.contains("你想要")
                || message.contains("你想") && message.contains("还是")
                || message.contains("Which") || message.contains("choose")
                || (message.contains("？") && (message.contains("方案") || message.contains("方式") || message.contains("Approach")));
    }

    /**
     * 美化决策消息，提取选项并添加自动决策提示
     */
    private String formatDecisionMessage(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤔 AI 正在决策：\n");

        // 提取编号选项（1. xxx 2. xxx 或 - xxx）
        String[] lines = message.split("\n");
        boolean hasOptions = false;
        for (String line : lines) {
            String trimmed = line.trim();
            // 匹配 "1. " "1、" "- " "* " 开头的选项行
            if (trimmed.matches("^\\d+[.、)：:].+")
                    || trimmed.matches("^[-*•]\\s.+")
                    || trimmed.matches("^\\*\\*.+\\*\\*$")) {
                // 去掉 markdown 加粗符号，简化显示
                String clean = trimmed.replaceAll("\\*\\*", "").replaceAll("`", "");
                sb.append("  ").append(clean).append("\n");
                hasOptions = true;
            }
        }

        // 如果没提取到选项，截取前200字符作为摘要
        if (!hasOptions) {
            String summary = message.length() > 200 ? message.substring(0, 200) + "..." : message;
            summary = summary.replaceAll("\\*\\*", "").replaceAll("`", "").replaceAll("\\n+", " ");
            sb.append("  ").append(summary).append("\n");
        }

        sb.append("\n⚡ AI 将自动决策，如需干预请切换到该进程发送 v-stop");
        return sb.toString();
    }

    /**
     * 格式化 AskUserQuestion 工具输入，展示问题和选项
     */
    private String formatAskUserQuestion(String toolInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤔 AI 需要您决策：\n");
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode input = mapper.readTree(toolInput);
            if (input.has("questions") && input.get("questions").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode q : input.get("questions")) {
                    String question = q.has("question") ? q.get("question").asText() : "";
                    sb.append("\n❓ ").append(question).append("\n");
                    if (q.has("options") && q.get("options").isArray()) {
                        int idx = 1;
                        for (com.fasterxml.jackson.databind.JsonNode opt : q.get("options")) {
                            String label = opt.has("label") ? opt.get("label").asText() : "";
                            String desc = opt.has("description") ? opt.get("description").asText() : "";
                            sb.append("  ").append(idx++).append(". ").append(label);
                            if (!desc.isEmpty()) {
                                sb.append(" - ").append(desc);
                            }
                            sb.append("\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            // JSON 解析失败，截取原文
            String summary = toolInput.length() > 200 ? toolInput.substring(0, 200) + "..." : toolInput;
            sb.append("  ").append(summary).append("\n");
        }
        sb.append("\n⚡ AI 将自动决策，如需干预请切换到该进程发送 v-stop");
        return sb.toString();
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

    /**
     * 格式化工具输入内容，添加 markdown 美化
     */
    private String formatToolInput(String toolName, String input) {
        if (input == null || input.isEmpty()) return "";

        // 尝试解析为 JSON 并格式化
        if (input.trim().startsWith("{") || input.trim().startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(input);
                // 提取关键字段进行美化显示
                StringBuilder sb = new StringBuilder();
                if (node.isObject()) {
                    node.fields().forEachRemaining(entry -> {
                        String key = entry.getKey();
                        com.fasterxml.jackson.databind.JsonNode value = entry.getValue();
                        if ("file_path".equals(key) || "path".equals(key) || "command".equals(key)) {
                            sb.append(key).append(": `").append(value.asText()).append("`\n");
                        } else if ("content".equals(key) && value.asText().length() > 100) {
                            // 长内容用代码块包裹
                            sb.append(key).append(":\n```\n").append(value.asText()).append("\n```\n");
                        } else if (value.isTextual()) {
                            sb.append(key).append(": ").append(value.asText()).append("\n");
                        } else {
                            sb.append(key).append(": ").append(value).append("\n");
                        }
                    });
                    return sb.toString().strip();
                }
            } catch (Exception e) {
                // JSON 解析失败，使用原始输入
            }
        }

        // 非 JSON 或解析失败，直接返回（可能是文件路径、命令等）
        return input;
    }

    /**
     * 格式化规划模式输入，提取关键信息
     */
    private String formatPlanModeInput(String toolName, String toolInput) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(toolInput);
            StringBuilder sb = new StringBuilder();

            if ("EnterPlanMode".equals(toolName)) {
                // 进入规划模式的参数
                if (node.has("reason")) {
                    sb.append("**原因**: ").append(node.get("reason").asText()).append("\n");
                }
                if (node.has("description")) {
                    sb.append("**描述**: ").append(node.get("description").asText()).append("\n");
                }
                if (node.has("allowedPrompts") && node.get("allowedPrompts").isArray()) {
                    sb.append("**允许的操作**:\n");
                    for (com.fasterxml.jackson.databind.JsonNode prompt : node.get("allowedPrompts")) {
                        if (prompt.has("prompt")) {
                            sb.append("- ").append(prompt.get("prompt").asText()).append("\n");
                        }
                    }
                }
            } else {
                // ExitPlanMode - 显示规划结果
                if (node.has("plan")) {
                    sb.append("**规划方案**:\n\n");
                    sb.append(node.get("plan").asText()).append("\n");
                }
                if (node.has("allowedPrompts") && node.get("allowedPrompts").isArray()) {
                    sb.append("\n**执行权限**:\n");
                    for (com.fasterxml.jackson.databind.JsonNode prompt : node.get("allowedPrompts")) {
                        if (prompt.has("tool") && prompt.has("prompt")) {
                            sb.append("- `").append(prompt.get("tool").asText()).append("`: ").append(prompt.get("prompt").asText()).append("\n");
                        }
                    }
                }
            }

            return sb.toString().strip();
        } catch (Exception e) {
            // JSON 解析失败，返回空
            return "";
        }
    }

    /**
     * 格式化 Agent 输入，提取关键信息
     */
    private String formatAgentInput(String toolInput) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(toolInput);
            StringBuilder sb = new StringBuilder();

            // 任务描述（标题）
            if (node.has("description")) {
                sb.append("**任务**: ").append(node.get("description").asText()).append("\n");
            }

            // 子代理类型
            if (node.has("subagent_type")) {
                sb.append("**类型**: `").append(node.get("subagent_type").asText()).append("`\n");
            }

            // 模型
            if (node.has("model")) {
                sb.append("**模型**: `").append(node.get("model").asText()).append("`\n");
            }

            // 后台运行
            if (node.has("run_in_background") && node.get("run_in_background").asBoolean()) {
                sb.append("**模式**: 后台运行\n");
            }

            // 隔离模式
            if (node.has("isolation")) {
                sb.append("**隔离**: `").append(node.get("isolation").asText()).append("`\n");
            }

            // 任务详情（prompt）
            if (node.has("prompt")) {
                String prompt = node.get("prompt").asText();
                // 截断过长的prompt，保留关键信息
                if (prompt.length() > 500) {
                    prompt = prompt.substring(0, 500) + "...";
                }
                sb.append("\n**任务详情**:\n```\n").append(prompt).append("\n```");
            }

            return sb.toString().strip();
        } catch (Exception e) {
            // JSON 解析失败，返回原始输入
            return toolInput;
        }
    }

    /**
     * 格式化 Agent 结果，美化显示
     */
    private String formatAgentResult(String result) {
        if (result == null || result.isEmpty()) return "";

        // 检查是否是 API Error
        if (result.contains("API Error")) {
            return formatApiError(result);
        }

        // 尝试解析为 JSON
        if (result.trim().startsWith("{") || result.trim().startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(result);

                // 如果是对象，提取关键字段
                if (node.isObject()) {
                    StringBuilder sb = new StringBuilder();

                    // 完成状态
                    if (node.has("status")) {
                        String status = node.get("status").asText();
                        sb.append("**状态**: ").append(status.equals("success") ? "✅ 成功" : "❌ " + status).append("\n");
                    }

                    // 结果摘要
                    if (node.has("summary")) {
                        sb.append("**摘要**: ").append(node.get("summary").asText()).append("\n");
                    }

                    // 耗时
                    if (node.has("duration_ms")) {
                        long ms = node.get("duration_ms").asLong();
                        sb.append("**耗时**: ").append(String.format("%.1f秒", ms / 1000.0)).append("\n");
                    }

                    // Token用量
                    if (node.has("usage")) {
                        com.fasterxml.jackson.databind.JsonNode usage = node.get("usage");
                        int input = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                        int output = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                        sb.append("**Token**: 输入").append(input).append(" + 输出").append(output).append(" = ").append(input + output).append("\n");
                    }

                    // 结果内容
                    if (node.has("result")) {
                        String content = node.get("result").asText();
                        // 截断过长内容
                        if (content.length() > 1000) {
                            content = content.substring(0, 1000) + "\n...[内容已截断]";
                        }
                        sb.append("\n**结果**:\n```\n").append(content).append("\n```");
                    }

                    if (sb.length() > 0) {
                        return sb.toString().strip();
                    }
                }
            } catch (Exception e) {
                // JSON 解析失败，使用原始结果
            }
        }

        // 非 JSON 或解析失败，直接返回（截断过长内容）
        if (result.length() > 1000) {
            return result.substring(0, 1000) + "\n...[内容已截断]";
        }
        return result;
    }

    /**
     * 格式化 API 错误信息
     */
    private String formatApiError(String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ **Agent 执行遇到 API 错误**\n\n");

        // 解析错误类型和消息
        // 格式: "API Error: 400 Param Incorrect"
        if (error.startsWith("API Error:")) {
            String errorContent = error.substring("API Error:".length()).trim();
            String[] parts = errorContent.split(" ", 2);

            if (parts.length >= 1) {
                String errorCode = parts[0];
                String errorMsg = parts.length > 1 ? parts[1] : "";

                sb.append("```json\n");
                sb.append("{\n");
                sb.append("  \"error_code\": \"").append(errorCode).append("\",\n");
                sb.append("  \"error_type\": \"").append(getErrorType(errorCode)).append("\",\n");
                sb.append("  \"message\": \"").append(errorMsg.isEmpty() ? "参数错误" : errorMsg).append("\"\n");
                sb.append("}\n");
                sb.append("```\n\n");

                sb.append("💡 **可能原因**: 子 Agent 调用参数不正确");
            }
        } else {
            // 其他格式的 API 错误
            sb.append("```\n").append(error).append("\n```\n\n");
            sb.append("💡 **建议**: 检查 API 配置或稍后重试");
        }

        return sb.toString();
    }

    /**
     * 根据错误码获取错误类型描述
     */
    private String getErrorType(String errorCode) {
        return switch (errorCode) {
            case "400" -> "请求参数错误";
            case "401" -> "认证失败";
            case "403" -> "权限不足";
            case "404" -> "资源不存在";
            case "429" -> "请求过于频繁";
            case "500" -> "服务器内部错误";
            case "502" -> "网关错误";
            case "503" -> "服务不可用";
            default -> "未知错误";
        };
    }

    public void handleMessage(String userId, String message, String contextToken) {
        handleMessage(userId, message, contextToken, false);
    }

    public void handleMessage(String userId, String message, String contextToken, boolean isNewUser) {
        // v- 命令、欢迎语、彩蛋已在 handleIlInkMessage 中处理

        if (message.startsWith("/")) {
            handleClaudeCommand(userId, message, contextToken);
            return;
        }

        ilinkService.sendTyping(userId);

        // 预留结果槽位
        quotaManager.reserveForResult(userId);

        // 先发送消息获取进程索引（通知需要）
        ClaudeApiService.SendResult sendResult = claudeApiService.sendMessageWithIndex(userId, message);
        int assignedProcessIndex = sendResult.processIndex();

        if (filterConfig.isShowMessageStatus()) {
            String sessionId = claudeApiService.getSessionId(userId);
            String model = claudeApiService.getModel();
            String workDir = claudeConfig().getWorkDir();
            if (workDir == null || workDir.isEmpty()) workDir = System.getProperty("user.dir");
            String sessionDisplay = sessionId != null ? sessionId : "新会话";
            int processCount = claudeApiService.getProcessCount(userId);
            int busyCount = claudeApiService.getBusyProcessCount(userId);
            int groupProcessCount = claudeApiService.getGroupProcessCount(userId);

            // 根据是否已分配进程显示不同的处理提示
            // 注意：sendMessageWithIndex 已将分配的进程标记为 busy，显示时需加回来
            String statusHint;
            if (assignedProcessIndex > 0) {
                int groupIdleCount = claudeApiService.getGroupIdleProcessCount(userId) + 1;
                statusHint = "\n🚀 开始处理任务（组内空闲进程: " + groupIdleCount + "）";
            } else {
                statusHint = "\n⏳ 进入等待队列（忙碌进程: " + busyCount + "/" + processCount + "）";
            }

            String processIndexDisplay = assignedProcessIndex > 0 ? "#" + assignedProcessIndex : "排队中";
            String groupInfo = groupProcessCount > 1 ? "\n👥 当前进程组: " + groupProcessCount + " 个" : "";

            String statusMsg = String.format(
                "✅ 收到消息\n\n🔢 进程: %s\n📋 会话ID: `%s`\n🤖 模型: `%s`\n📁 工作目录: `%s`\n⚙️ 总进程: %d 个%s%s",
                processIndexDisplay, sessionDisplay, model, workDir, processCount, groupInfo, statusHint
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

        // 使用 volatile 标志控制 typing 线程，避免 interrupt 后仍发送 sendTyping
        java.util.concurrent.atomic.AtomicBoolean typingActive = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread typingThread = new Thread(() -> {
            while (typingActive.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                    if (typingActive.get()) {
                        ilinkService.sendTyping(userId);
                    }
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
            String response = sendResult.future().get(); // 阻塞等待结果
            long duration = System.currentTimeMillis() - startTime;

            boolean blocked = false;
            for (String keyword : filterConfig.getBlockedKeywords()) {
                if (response != null && response.contains(keyword)) {
                    blocked = true;
                    break;
                }
            }

            if (response == null || response.isEmpty()) {
                response = "Claude 未返回结果，请检查日志或稍后重试";
            }

            String processTag = assignedProcessIndex > 0 ? "\n\n> 📍 由进程 #" + assignedProcessIndex + " 执行" : "";

            if (!blocked) {
                if (filterConfig.isShowTaskCompletion()) {
                    String taskSummary = claudeApiService.getTaskSummary(userId, message);
                    String statsSummary = claudeApiService.getTaskCompletionSummary(userId, duration, response);
                    String fullResponse = "✅ 任务完成 | " + taskSummary + "\n\n---\n" + response + "\n\n" + statsSummary + processTag;
                    ilinkService.sendText(userId, fullResponse, contextToken, "result");
                } else {
                    ilinkService.sendText(userId, response, contextToken, "result");
                }
            } else {
                if (filterConfig.isShowTaskCompletion()) {
                    ilinkService.sendText(userId, "✅ 任务完成（内容被关键词过滤）" + processTag, contextToken, "result");
                }
            }
        } catch (Exception e) {
            log.error("Failed to get response for user: {}", userId, e);
            ilinkService.sendText(userId, "处理异常: " + e.getMessage(), contextToken, "result");
        } finally {
            quotaManager.releaseResultSlot(userId);
            quotaManager.recordMessageSent(userId, "result");
            typingActive.set(false);
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
            case V_CLEAR -> handleClearCommand(userId, parts, contextToken);
            case V_SESSIONS -> handleListSessions(userId, contextToken);
            case V_LIMIT -> handleLimitCommand(userId, parts, contextToken);
            case V_API -> { if (parts.length > 1) { claudeApiService.setApiUrl(parts[1]); configService.saveConfig(); if (claudeApiService.getGroupBusyProcessCount(userId) > 0) { claudeApiService.markGroupForRestart(userId); ilinkService.sendText(userId, "已设置 API: " + parts[1] + "\n✅ 配置已保存，当前任务完成后自动生效", contextToken); } else { claudeApiService.destroyCurrentGroupProcesses(userId); ilinkService.sendText(userId, "已设置 API: " + parts[1] + "\n✅ 配置已生效", contextToken); } } }
            case V_KEY -> { if (parts.length > 1) { claudeApiService.setApiKey(parts[1]); configService.saveConfig(); if (claudeApiService.getGroupBusyProcessCount(userId) > 0) { claudeApiService.markGroupForRestart(userId); ilinkService.sendText(userId, "已设置 API Key\n✅ 配置已保存，当前任务完成后自动生效", contextToken); } else { claudeApiService.destroyCurrentGroupProcesses(userId); ilinkService.sendText(userId, "已设置 API Key\n✅ 配置已生效", contextToken); } } }
            case V_MODEL -> {
                if (parts.length > 1) {
                    claudeApiService.setModel(parts[1]);
                    configService.saveConfig();
                    if (claudeApiService.getGroupBusyProcessCount(userId) > 0) {
                        claudeApiService.markGroupForRestart(userId);
                        ilinkService.sendText(userId, "已设置模型: " + parts[1] + "\n✅ 配置已保存，当前任务完成后自动生效", contextToken);
                    } else {
                        claudeApiService.destroyCurrentGroupProcesses(userId);
                        ilinkService.sendText(userId, "已设置模型: " + parts[1] + "\n✅ 配置已生效", contextToken);
                    }
                }
            }
            case V_TOOLS -> handleQuickToggle(userId, "tools", parts, contextToken);
            case V_FILEREAD -> handleQuickToggle(userId, "fileread", parts, contextToken);
            case V_FILEEDIT -> handleQuickToggle(userId, "fileedit", parts, contextToken);
            case V_SUBTASK -> handleQuickToggle(userId, "subtask", parts, contextToken);
            case V_SUBTASK_DONE -> handleQuickToggle(userId, "subtask-done", parts, contextToken);
            case V_AGENT -> handleQuickToggle(userId, "agent", parts, contextToken);
            case V_AGENT_DONE -> handleQuickToggle(userId, "agent-done", parts, contextToken);
            case V_PLAN -> handleQuickToggle(userId, "plan", parts, contextToken);
            case V_SUBSTEP -> handleQuickToggle(userId, "substep", parts, contextToken);
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
                    if (claudeApiService.getGroupBusyProcessCount(userId) > 0) {
                        claudeApiService.markGroupForRestart(userId);
                        ilinkService.sendText(userId, "已设置路径: " + parts[1] + "\n✅ 配置已保存，当前任务完成后自动生效", contextToken);
                    } else {
                        claudeApiService.destroyCurrentGroupProcesses(userId);
                        ilinkService.sendText(userId, "已设置路径: " + parts[1] + "\n✅ 配置已生效", contextToken);
                    }
                }
            }
            case V_CD -> {
                if (parts.length > 1) {
                    java.io.File dir = new java.io.File(parts[1]).getAbsoluteFile();
                    boolean mkdir = java.util.Arrays.asList(parts).contains("mkdir");
                    boolean clearContext = java.util.Arrays.asList(parts).contains("clear");
                    boolean force = java.util.Arrays.asList(parts).contains("force");

                    // 目录不存在时尝试创建
                    if (!dir.exists() || !dir.isDirectory()) {
                        if (mkdir) {
                            if (!dir.mkdirs()) {
                                ilinkService.sendText(userId, "目录创建失败: " + parts[1], contextToken);
                                return;
                            }
                        } else {
                            ilinkService.sendText(userId, "目录不存在: " + parts[1] + "\n使用 `v-cd " + parts[1] + " mkdir` 自动创建", contextToken);
                            return;
                        }
                    }

                    // 检查进程组是否有忙碌任务
                    int groupBusyCount = claudeApiService.getGroupBusyProcessCount(userId);

                    if (groupBusyCount > 0 && force) {
                        claudeApiService.forceStopAll(userId);
                        ilinkService.sendText(userId, "⚠️ 已强制停止 " + groupBusyCount + " 个忙碌进程", contextToken);
                        groupBusyCount = 0;
                    }

                    // 保存目录配置
                    claudeConfig().setWorkDir(dir.getAbsolutePath());
                    System.setProperty("user.dir", dir.getAbsolutePath());
                    configService.saveConfig();

                    if (groupBusyCount > 0) {
                        // 有任务运行中，标记延迟重启
                        claudeApiService.markGroupForRestart(userId);
                        ilinkService.sendText(userId, "工作目录: " + dir.getAbsolutePath() + "\n✅ 配置已保存，当前任务完成后自动生效", contextToken);
                    } else {
                        // 无任务，立即生效
                        claudeApiService.destroyCurrentGroupProcesses(userId);
                        String msg = "工作目录: " + dir.getAbsolutePath() + "\n✅ 配置已生效";
                        if (clearContext) {
                            claudeApiService.clearCurrentSession(userId);
                            msg += "\n当前会话已清空";
                        }
                        ilinkService.sendText(userId, msg, contextToken);
                    }
                } else {
                    ilinkService.sendText(userId, "用法: v-cd <路径> [mkdir] [clear] [force]\n- mkdir: 目录不存在时自动创建\n- clear: 清空当前会话\n- force: 强制停止繁忙任务后切换", contextToken);
                }
            }
            case V_BLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().add(kw); configService.saveConfig(); ilinkService.sendText(userId, "已添加: " + kw, contextToken); } }
            case V_UNBLOCK -> { if (parts.length > 1) { String kw = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)); filterConfig.getBlockedKeywords().remove(kw); configService.saveConfig(); ilinkService.sendText(userId, "已移除: " + kw, contextToken); } }
            case V_NOTIFY -> { if (parts.length > 1) { filterConfig.setShowMessageStatus(Boolean.parseBoolean(parts[1])); configService.saveConfig(); ilinkService.sendText(userId, "通知: " + (filterConfig.isShowMessageStatus() ? "开启" : "关闭"), contextToken); } }
            case V_CONFIG -> handleConfigCommand(userId, parts, contextToken);
            case V_SWITCH -> handleSwitchCommand(userId, parts, contextToken);
            case V_SAVE -> handleSaveCommand(userId, parts, contextToken);
            case V_PROFILES -> handleProfilesCommand(userId, contextToken);
            case V_THINKING -> handleThinkingCommand(userId, parts, contextToken);
            case V_DELETE -> handleDeleteSessionCommand(userId, parts, contextToken);
            case V_PROCESSES -> handleProcessStatus(userId, contextToken);
            case V_MAXPROC -> handleMaxProcCommand(userId, parts, contextToken);
            case V_IDLE -> handleIdleTimeoutCommand(userId, parts, contextToken);
            case V_STOP -> handleStopCommand(userId, parts, contextToken);
            case V_PROC -> handleProcCommand(userId, parts, contextToken);
            case V_FORK -> handleForkCommand(userId, parts, contextToken);
            case V_NEWPROC -> handleNewProcCommand(userId, parts, contextToken);
            case V_DELPROC -> handleDelProcCommand(userId, parts, contextToken);
            case V_REFRESH -> {
                int busyCount = claudeApiService.getBusyProcessCount(userId);
                String refreshMsg = "✅ 已刷新配额\n\n本条消息也占一条配额";
                if (busyCount >= 9) {
                    refreshMsg += "\n\n⚠️ 当前有 " + busyCount + " 个忙碌进程，每个进程仍需占用一条配额用于返回结果，实际可用配额不足。建议等待部分任务完成后再发送新消息。";
                }
                ilinkService.sendText(userId, refreshMsg, contextToken);
            }
            default -> ilinkService.sendText(userId, "未知命令: " + cmd + "\n输入 v-help 查看所有命令", contextToken);
        }
    }

    private void handleClaudeCommand(String userId, String command, String contextToken) {
        // /btw 命令：临时进程处理，不影响当前任务
        if (command.startsWith("/btw ")) {
            handleBtwCommand(userId, command, contextToken);
            return;
        }

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

    /**
     * /btw 命令：临时进程处理，不影响当前任务
     * 用法: /btw <消息内容>
     */
    private void handleBtwCommand(String userId, String command, String contextToken) {
        // 提取消息内容（去掉 "/btw " 前缀）
        String message = command.substring(5).trim();
        if (message.isEmpty()) {
            ilinkService.sendText(userId, "用法: `/btw <消息内容>`\n\n临时发送消息给 Claude，不影响当前正在执行的任务", contextToken);
            return;
        }

        // 异步处理，不阻塞
        CompletableFuture.runAsync(() -> {
            ilinkService.sendTyping(userId);
            try {
                String response = claudeApiService.sendBtwMessage(userId, message);
                if (response != null && !response.isEmpty()) {
                    // 格式化结果，走单独的 markdown 模板
                    String formatted = formatBtwResponse(message, response);
                    ilinkService.sendText(userId, formatted, contextToken);
                } else {
                    ilinkService.sendText(userId, "💬 /btw 命令执行完成（无输出）", contextToken);
                }
            } catch (Exception e) {
                ilinkService.sendText(userId, "❌ /btw 执行失败: " + e.getMessage(), contextToken);
            } finally {
                ilinkService.sendStopTyping(userId);
            }
        });
    }

    /**
     * 格式化 /btw 响应，markdown 模板
     */
    private String formatBtwResponse(String originalMessage, String response) {
        StringBuilder sb = new StringBuilder();
        sb.append("💬 **BTW 回复**\n\n");
        // 截断过长的原始消息
        String shortMessage = originalMessage.length() > 50
                ? originalMessage.substring(0, 50) + "..."
                : originalMessage;
        sb.append("> ").append(shortMessage).append("\n\n");
        sb.append("---\n\n");
        sb.append(response);
        return sb.toString();
    }

    private void handleProcessStatus(String userId, String contextToken) {
        String status = claudeApiService.getProcessStatus(userId);
        int count = claudeApiService.getProcessCount(userId);
        int parentCount = claudeApiService.getParentProcessCount(userId);
        int forkChildCount = claudeApiService.getForkChildCount(userId);
        int independentCount = count - parentCount - forkChildCount;
        String quotaStatus = quotaManager.getStatus(userId);
        int busyCount = claudeApiService.getBusyProcessCount(userId);
        ilinkService.sendText(userId, String.format("**进程状态** (共%d个, 父进程%d个, fork子进程%d个, 独立进程%d个, 忙碌%d个)\n\n%s\n\n💡 使用 `v-proc <序号>` 切换进程\n\n**配额状态**\n%s", count, parentCount, forkChildCount, independentCount, busyCount, status, quotaStatus), contextToken);
    }

    private void showHelp(String userId, String contextToken) {
        String help = """
                **🤖 VibeWeChat 命令帮助**

                ---

                **📋 基础命令**

                | 命令 | 说明 |
                |------|------|
                | `v-help` | 显示此帮助信息 |
                | `v-status` | 查看系统完整状态 |
                | `v-processes` | 查看进程列表和状态 |
                | `v-refresh` | 刷新消息配额（10条/24h） |

                ---

                **💬 特殊命令**

                | 命令 | 说明 |
                |------|------|
                | `/btw <消息>` | 临时发送消息给 Claude，不影响当前正在执行的任务 |

                ---

                **⚡ 进程管理**

                | 命令 | 说明 |
                |------|------|
                | `v-proc <序号>` | 切换到指定进程（同时切换会话和工作目录） |
                | `v-fork` | 克隆当前进程，共享 session 和工作目录 |
                | `v-newproc <session/路径>` | 创建新独立进程，绑定 session 或目录 |
                | `v-delproc <序号>` | 删除指定进程（需先删除子进程） |
                | `v-stop` | 停止当前进程的任务 |
                | `v-stop <序号>` | 停止指定进程的任务 |
                | `v-stop all` | 停止全部进程的任务 |
                | `v-stop keep` | 停止当前任务，保留 Worker 并立即处理排队消息 |
                | `v-maxproc <数量>` | 设置最大进程数 (1-CPU×2) |
                | `v-idle <秒>` | 设置空闲超时（默认24小时） |

                ---

                **🔗 进程克隆说明**

                • `v-fork` 克隆当前进程作为子进程，共享 session 和工作目录
                • 如果当前是子进程，克隆的进程会放在父进程下（不传递子进程）
                • `v-newproc` 创建的是独立进程，不在任何进程组，不共享任务
                • `v-newproc` 必须指定 session 序号/ID 或目录路径
                • 进程组内可并行处理消息，全部忙碌时排队等待
                • 父进程的 session 和工作目录变更会同步到子进程
                • 进程空闲超时后自动销毁（默认24小时）
                • 每个忙碌进程占用一条消息配额

                ---

                **⚙️ Claude 配置**

                | 命令 | 说明 |
                |------|------|
                | `v-config <key> <url> <model>` | 一键配置 API Key、地址、模型 |
                | `v-switch <name>` | 切换预设配置 |
                | `v-model <name>` | 设置模型名称 |
                | `v-api <url>` | 设置 API 地址 |
                | `v-key <key>` | 设置 API Key |
                | `v-claude <path>` | 设置 Claude 安装路径 |
                | `v-cd <path> [mkdir] [clear] [force]` | 切换工作目录（mkdir: 自动创建） |
                | `v-thinking <级别>` | 设置推理模式 |

                ---

                **🧠 推理模式** (`v-thinking`)

                | 级别 | 说明 |
                |------|------|
                | `off` | 关闭推理 |
                | `low` | 1k tokens |
                | `medium` | 5k tokens |
                | `high` | 10k tokens |
                | `max` | 32k tokens |
                | `default` | 查看当前配置 |

                ---

                **💬 会话管理**

                | 命令 | 说明 |
                |------|------|
                | `v-sessions` | 列出所有会话 |
                | `v-session <序号或ID>` | 切换到指定会话 |
                | `v-new` | 新建会话（销毁进程） |
                | `v-clear [current/all] [destroy]` | 清空会话（默认仅当前，destroy 同时销毁进程） |
                | `v-delete <id>` | 删除指定会话 |
                | `v-disk-sessions` | 查看磁盘保存的会话 |
                | `v-save <name>` | 保存当前配置为预设 |
                | `v-profiles` | 列出所有预设 |

                ---

                **🔔 通知配置**

                | 命令 | 说明 |
                |------|------|
                | `v-notify <true/false>` | 消息状态通知 |
                | `v-tools <true/false>` | 工具调用通知 |
                | `v-fileread <true/false>` | 文件读取通知 |
                | `v-fileedit <true/false>` | 文件编辑通知 |
                | `v-subtask <true/false>` | 子任务状态通知 |
                | `v-subtask-done <true/false>` | 子任务完成通知 |
                | `v-substep <true/false>` | 子环节通知 |
                | `v-agent <true/false>` | Agent 开始通知 |
                | `v-agent-done <true/false>` | Agent 完成通知 |
                | `v-plan <true/false>` | 规划模式通知 |
                | `v-token` | 查看/开关 Token 统计 |

                ---

                **🔍 关键词过滤**

                | 命令 | 说明 |
                |------|------|
                | `v-block <词>` | 添加关键词过滤 |
                | `v-unblock <词>` | 移除关键词过滤 |

                ---

                **📌 注意事项**

                • 服务重启后，内存中的进程池和会话记录会清空
                • 异常关闭导致的孤儿进程会在服务启动时自动清理
                • 消息配额为 10 条/24h，每个忙碌进程占用一条配额
                """;
        ilinkService.sendText(userId, help, contextToken);
    }

    private void showStatus(String userId, String contextToken) {
        String sessionId = claudeApiService.getSessionId(userId);
        int sessionCount = claudeApiService.getSessionHistory(userId).size();
        ClaudeApiService.TokenUsage usage = claudeApiService.getTokenUsage(userId);
        ClaudeApiService.TokenUsage cumulative = claudeApiService.getCumulativeTokenUsage(userId);
        String activeProfile = configService.getActiveProfile();
        int processCount = claudeApiService.getProcessCount(userId);
        int parentCount = claudeApiService.getParentProcessCount(userId);
        int forkCount = claudeApiService.getForkChildCount(userId);
        int independentCount = processCount - parentCount - forkCount;
        int busyCount = claudeApiService.getBusyProcessCount(userId);
        String quotaStatus = quotaManager.getStatus(userId);

        String status = String.format("""
                **📊 VibeWeChat 系统状态**

                ---

                **⚙️ Claude 配置**

                | API 地址 | 模型 | 安装路径 | 推理模式 | 活跃配置 | 工作目录 |
                |----------|------|----------|----------|----------|----------|
                | `%s` | `%s` | `%s` | %s | `%s` | `%s` |

                ---

                **⚡ 进程状态**

                | 进程总数 | 父进程 | fork 子进程 | 独立进程 | 忙碌进程 | 空闲超时 | 配额 |
                |----------|----------|-------------|----------|----------|----------|------|
                | %d/%d | %d | %d | %d | %d | %s | %s |

                ---

                **💬 会话信息**

                | 当前会话 | 历史会话 | 关键词过滤 |
                |----------|----------|------------|
                | `%s` | %d 个 | %d 个 |

                ---

                **📈 Token 统计**

                | 类型 | Token 输入 | Token 输出 | Token 总计 |
                |------|------------|------------|------------|
                | 本次会话 | %s | %s | %s |
                | 累积总计 | %s | %s | %s |

                ---

                **🔔 通知配置**

                | 通知类型 | 状态 | 命令 |
                |----------|------|------|
                | 消息状态 | %s | `v-notify` |
                | 工具调用 | %s | `v-tools` |
                | 文件读取 | %s | `v-fileread` |
                | 文件编辑 | %s | `v-fileedit` |
                | 子任务状态 | %s | `v-subtask` |
                | 子任务完成 | %s | `v-subtask-done` |
                | 子环节 | %s | `v-substep` |
                | Agent 开始 | %s | `v-agent` |
                | Agent 完成 | %s | `v-agent-done` |
                | 规划模式 | %s | `v-plan` |
                | Token 统计 | %s | `v-token` |
                """,
                claudeApiService.getApiUrl() != null ? claudeApiService.getApiUrl() : "未设置",
                claudeApiService.getModel() != null ? claudeApiService.getModel() : "未设置",
                claudeApiService.getInstallPath() != null ? claudeApiService.getInstallPath() : "自动检测",
                claudeApiService.isThinkingEnabled() ? thinkingConfig.getLevel() + " (" + thinkingConfig.getCurrentBudgetTokens() + " tok)" : "关闭",
                activeProfile != null && !activeProfile.isEmpty() ? activeProfile : "无",
                claudeConfig().getWorkDir() != null && !claudeConfig().getWorkDir().isEmpty() ? claudeConfig().getWorkDir() : System.getProperty("user.dir"),
                processCount,
                claudeApiService.getMaxProcessesPerUser(),
                parentCount,
                forkCount,
                independentCount,
                busyCount,
                formatDuration(claudeApiService.getProcessIdleTimeoutMs()),
                quotaStatus,
                sessionId != null ? sessionId.substring(0, Math.min(12, sessionId.length())) + "..." : "无",
                sessionCount,
                filterConfig.getBlockedKeywords().size(),
                formatTokens(usage.getInputTokens()),
                formatTokens(usage.getOutputTokens()),
                formatTokens(usage.getTotalTokens()),
                formatTokens(cumulative.getInputTokens()),
                formatTokens(cumulative.getOutputTokens()),
                formatTokens(cumulative.getTotalTokens()),
                filterConfig.isShowMessageStatus() ? "✅ 开" : "❌ 关",
                filterConfig.isShowToolCalls() ? "✅ 开" : "❌ 关",
                filterConfig.isShowFileRead() ? "✅ 开" : "❌ 关",
                filterConfig.isShowFileEdit() ? "✅ 开" : "❌ 关",
                filterConfig.isShowSubtaskStatus() ? "✅ 开" : "❌ 关",
                filterConfig.isShowSubtaskCompletion() ? "✅ 开" : "❌ 关",
                filterConfig.isShowSubStepStatus() ? "✅ 开" : "❌ 关",
                filterConfig.isShowAgentCalls() ? "✅ 开" : "❌ 关",
                filterConfig.isShowAgentCompletion() ? "✅ 开" : "❌ 关",
                filterConfig.isShowPlanMode() ? "✅ 开" : "❌ 关",
                filterConfig.isShowTokenUsage() ? "✅ 开" : "❌ 关");
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
            case "agent" -> filterConfig.setShowAgentCalls(newValue);
            case "agent-done" -> filterConfig.setShowAgentCompletion(newValue);
            case "plan" -> filterConfig.setShowPlanMode(newValue);
            case "substep" -> filterConfig.setShowSubStepStatus(newValue);
        }
        configService.saveConfig();
        ilinkService.sendText(userId, type + " = " + (newValue ? "on" : "off"), contextToken);
    }

    private void handleSessionCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-session <序号或ID>\n使用 v-sessions 查看", contextToken);
            return;
        }

        String target = parts[1];
        // 尝试按序号匹配
        try {
            int index = Integer.parseInt(target) - 1;
            java.util.List<String> history = claudeApiService.getSessionHistory(userId);
            if (index >= 0 && index < history.size()) {
                String sessionId = history.get(index);
                if (claudeApiService.switchSession(userId, sessionId)) {
                    ilinkService.sendText(userId, "已切换到会话#" + target + ": `" + sessionId.substring(0, Math.min(12, sessionId.length())) + "...`\n新请求将恢复此会话", contextToken);
                    return;
                }
            }
            ilinkService.sendText(userId, "无效序号，范围: 1-" + history.size() + "\n使用 v-sessions 查看", contextToken);
        } catch (NumberFormatException e) {
            // 不是数字，作为 sessionId 使用
            if (claudeApiService.switchSession(userId, target)) {
                ilinkService.sendText(userId, "已切换: " + target + "\n新请求将恢复此会话", contextToken);
            } else {
                ilinkService.sendText(userId, "未找到会话: " + target + "\n使用 v-sessions 查看", contextToken);
            }
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
            if (claudeApiService.getGroupBusyProcessCount(userId) > 0) {
                claudeApiService.markGroupForRestart(userId);
                ilinkService.sendText(userId, "已切换: " + parts[1] + "\n✅ 配置已保存，当前任务完成后自动生效", contextToken);
            } else {
                claudeApiService.destroyCurrentGroupProcesses(userId);
                ilinkService.sendText(userId, "已切换: " + parts[1] + "\n✅ 配置已生效", contextToken);
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
                claudeApiService.clearSessionId(userId);
                if (claudeApiService.hasBusyProcesses(userId)) {
                    ilinkService.sendText(userId, "推理模式: 关闭\n⚠️ 有任务运行中，配置已保存，下次请求将使用新会话\n当前进程将在完成后销毁", contextToken);
                } else {
                    claudeApiService.destroyAllProcesses(userId);
                    ilinkService.sendText(userId, "推理模式: 关闭\n进程已重启，下次请求将使用新会话\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
                }
            } else if (thinkingConfig.getLevels().contains(level)) {
                thinkingConfig.setLevel(level);
                configService.saveConfig();
                claudeApiService.clearSessionId(userId);
                if (claudeApiService.hasBusyProcesses(userId)) {
                    ilinkService.sendText(userId, "推理模式: " + level + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n⚠️ 有任务运行中，配置已保存，下次请求将使用新会话\n当前进程将在完成后销毁", contextToken);
                } else {
                    claudeApiService.destroyAllProcesses(userId);
                    ilinkService.sendText(userId, "推理模式: " + level + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n进程已重启，下次请求将使用新会话\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
                }
            } else {
                ilinkService.sendText(userId, "无效级别，可选: " + String.join(", ", thinkingConfig.getLevels()) + ", off", contextToken);
            }
        } else {
            String newLevel = thinkingConfig.cycleLevel();
            configService.saveConfig();
            claudeApiService.clearSessionId(userId);
            if (claudeApiService.hasBusyProcesses(userId)) {
                ilinkService.sendText(userId, "推理模式: " + newLevel + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n⚠️ 有任务运行中，配置已保存，下次请求将使用新会话", contextToken);
            } else {
                claudeApiService.destroyAllProcesses(userId);
                ilinkService.sendText(userId, "推理模式: " + newLevel + " (budget: " + thinkingConfig.getCurrentBudgetTokens() + " tokens)\n进程已重启，下次请求将使用新会话\n⚠️ 已覆盖本地 Claude settings 文件", contextToken);
            }
        }
    }

    private void handleMaxProcCommand(String userId, String[] parts, String contextToken) {
        int maxAllowed = Runtime.getRuntime().availableProcessors() * 2;
        if (parts.length >= 2) {
            try {
                int maxProc = Integer.parseInt(parts[1]);
                if (maxProc < 1 || maxProc > maxAllowed) {
                    ilinkService.sendText(userId, "无效值，范围: 1-" + maxAllowed + "（CPU核心数×2）", contextToken);
                    return;
                }
                claudeApiService.setMaxProcessesPerUser(maxProc);
                configService.saveConfig();
                ilinkService.sendText(userId, "最大进程数: " + maxProc + "\n超出的空闲进程将在下次清理时销毁", contextToken);
            } catch (NumberFormatException e) {
                ilinkService.sendText(userId, "用法: v-maxproc <数量>\n当前: " + claudeApiService.getMaxProcessesPerUser() + "\n范围: 1-" + maxAllowed, contextToken);
            }
        } else {
            ilinkService.sendText(userId, "用法: v-maxproc <数量>\n当前: " + claudeApiService.getMaxProcessesPerUser() + "\n范围: 1-" + maxAllowed, contextToken);
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

    private void handleStopCommand(String userId, String[] parts, String contextToken) {
        // v-stop [all|keep|序号]
        // 默认停止当前进程的任务（销毁 Worker）
        // all: 停止全部任务
        // keep: 停止当前任务但保留 Worker，排队的消息会立即被处理
        // 序号: 停止指定进程的任务

        if (parts.length >= 2) {
            String arg = parts[1].toLowerCase();
            if ("all".equals(arg)) {
                // 停止全部任务
                int busyCount = claudeApiService.getBusyProcessCount(userId);
                if (busyCount == 0) {
                    ilinkService.sendText(userId, "当前没有正在运行的任务", contextToken);
                    return;
                }
                claudeApiService.forceStopAll(userId);
                ilinkService.sendText(userId, "已强制停止全部 " + busyCount + " 个忙碌进程", contextToken);
            } else if ("keep".equals(arg)) {
                // 停止当前任务但保留 Worker
                String result = claudeApiService.stopCurrentProcessKeepWorker(userId);
                ilinkService.sendText(userId, result, contextToken);
            } else {
                // 停止指定进程的任务
                try {
                    int index = Integer.parseInt(parts[1]) - 1;
                    String result = claudeApiService.forceStopProcess(userId, index);
                    ilinkService.sendText(userId, result, contextToken);
                } catch (NumberFormatException e) {
                    ilinkService.sendText(userId, "用法: v-stop [all|keep|序号]\n- 无参数: 停止当前进程\n- all: 停止全部\n- keep: 停止当前任务，保留 Worker 并处理排队消息\n- 序号: 停止指定进程", contextToken);
                }
            }
        } else {
            // 默认停止当前进程的任务
            int busyCount = claudeApiService.getBusyProcessCount(userId);
            if (busyCount == 0) {
                ilinkService.sendText(userId, "当前没有正在运行的任务", contextToken);
                return;
            }
            // 停止当前进程（第一个父进程）
            String result = claudeApiService.forceStopCurrentProcess(userId);
            ilinkService.sendText(userId, result, contextToken);
        }
    }

    private void handleProcCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-proc <序号>\n使用 v-processes 查看进程列表", contextToken);
            return;
        }

        try {
            int index = Integer.parseInt(parts[1]) - 1;
            String result = claudeApiService.switchProcess(userId, index);
            ilinkService.sendText(userId, result, contextToken);
        } catch (NumberFormatException e) {
            ilinkService.sendText(userId, "用法: v-proc <序号>\n使用 v-processes 查看进程列表", contextToken);
        }
    }

    private void handleClearCommand(String userId, String[] parts, String contextToken) {
        // v-clear [current|all] [destroy]
        boolean all = false;
        boolean destroy = false;
        for (int i = 1; i < parts.length; i++) {
            if ("all".equalsIgnoreCase(parts[i])) all = true;
            if ("destroy".equalsIgnoreCase(parts[i])) destroy = true;
        }

        if (all) {
            if (destroy) {
                claudeApiService.clearHistory(userId);
                ilinkService.sendText(userId, "所有会话已清空，进程已销毁", contextToken);
            } else {
                // 仅清空所有 session，保留进程
                claudeApiService.clearAllSessions(userId);
                ilinkService.sendText(userId, "所有会话已清空（进程已保留）\n\n如需同时销毁进程，请使用 `v-clear all destroy`", contextToken);
            }
        } else {
            // 默认 current：仅清除当前 session，保留所有进程
            claudeApiService.clearCurrentSession(userId);
            ilinkService.sendText(userId, "当前会话已清空（进程已保留）", contextToken);
        }
    }

    private void handleForkCommand(String userId, String[] parts, String contextToken) {
        // v-fork: 克隆当前进程作为子进程
        // 如果当前是子进程，则克隆到父进程下
        String result = claudeApiService.cloneCurrentProcess(userId);
        ilinkService.sendText(userId, result, contextToken);
    }

    private void handleNewProcCommand(String userId, String[] parts, String contextToken) {
        // v-newproc <session序号/ID | 目录路径>: 创建新独立进程
        // 如果参数是 session 序号或 ID，绑定该 session 及其工作目录
        // 如果参数是目录路径，绑定该目录，首次使用时自动创建 session
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-newproc <session序号/ID | 目录路径>\n\n" +
                    "• `v-newproc 2` — 绑定会话列表第2个会话\n" +
                    "• `v-newproc <session-id>` — 绑定指定会话\n" +
                    "• `v-newproc /path/to/dir` — 绑定目录，首次使用时自动创建会话", contextToken);
            return;
        }

        String target = parts[1];

        // 判断参数是 session 还是目录路径
        // 如果以 / 开头或以 ~ 开头，视为目录路径
        if (target.startsWith("/") || target.startsWith("~") || target.startsWith(".")) {
            // 目录路径
            java.io.File dir = new java.io.File(target).getAbsoluteFile();
            if (!dir.exists() || !dir.isDirectory()) {
                ilinkService.sendText(userId, "目录不存在: " + target, contextToken);
                return;
            }
            String result = claudeApiService.createNewProcess(userId, null, dir.getAbsolutePath());
            ilinkService.sendText(userId, result, contextToken);
        } else {
            // 尝试按 session 序号匹配
            try {
                int index = Integer.parseInt(target) - 1;
                java.util.List<String> history = claudeApiService.getSessionHistory(userId);
                if (index >= 0 && index < history.size()) {
                    String sessionId = history.get(index);
                    String result = claudeApiService.createNewProcess(userId, sessionId);
                    ilinkService.sendText(userId, result, contextToken);
                } else {
                    ilinkService.sendText(userId, "无效序号，范围: 1-" + history.size() + "\n使用 v-sessions 查看", contextToken);
                }
            } catch (NumberFormatException e) {
                // 不是数字，作为 sessionId 使用
                String result = claudeApiService.createNewProcess(userId, target);
                ilinkService.sendText(userId, result, contextToken);
            }
        }
    }

    private void handleDelProcCommand(String userId, String[] parts, String contextToken) {
        // v-delproc <序号>: 删除指定进程（有子进程时不可删除）
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-delproc <序号>\n使用 v-processes 查看进程列表", contextToken);
            return;
        }

        try {
            int index = Integer.parseInt(parts[1]) - 1;
            String result = claudeApiService.deleteProcess(userId, index);
            ilinkService.sendText(userId, result, contextToken);
        } catch (NumberFormatException e) {
            ilinkService.sendText(userId, "用法: v-delproc <序号>\n使用 v-processes 查看进程列表", contextToken);
        }
    }

    private void handleConfigCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 4) {
            ilinkService.sendText(userId, "用法: v-config <key> <url> <model>\n\n示例:\nv-config sk-xxx https://api.anthropic.com claude-sonnet-4-20250514", contextToken);
            return;
        }
        String apiKey = parts[1];
        String apiUrl = parts[2];
        String model = parts[3];

        claudeApiService.setApiKey(apiKey);
        claudeApiService.setApiUrl(apiUrl);
        claudeApiService.setModel(model);
        configService.saveConfig();

        if (claudeApiService.getGroupBusyProcessCount(userId) > 0) {
            claudeApiService.markGroupForRestart(userId);
            ilinkService.sendText(userId, "✅ 一键配置完成\n\n🔑 API Key: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...\n🌐 API: " + apiUrl + "\n🤖 模型: " + model + "\n\n✅ 配置已保存，当前任务完成后自动生效", contextToken);
        } else {
            claudeApiService.destroyCurrentGroupProcesses(userId);
            ilinkService.sendText(userId, "✅ 一键配置完成\n\n🔑 API Key: " + apiKey.substring(0, Math.min(8, apiKey.length())) + "...\n🌐 API: " + apiUrl + "\n🤖 模型: " + model + "\n\n✅ 配置已生效", contextToken);
        }
    }

    private com.chengxun.vibewechat.config.ClaudeConfig claudeConfig() {
        return claudeApiService.getClaudeConfig();
    }

    private void handleDiskSessionsCommand(String userId, String contextToken) {
        java.util.List<ClaudeApiService.DiskSession> sessions = claudeApiService.getDiskSessions();
        StringBuilder sb = new StringBuilder("**磁盘会话列表:**\n\n");

        if (sessions.isEmpty()) {
            sb.append("暂无磁盘会话");
        } else {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm");
            java.text.SimpleDateFormat fullSdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            for (int i = 0; i < sessions.size(); i++) {
                ClaudeApiService.DiskSession s = sessions.get(i);
                String time = s.startedAt > 0 ? sdf.format(new java.util.Date(s.startedAt)) : "未知";
                String fullTime = s.startedAt > 0 ? fullSdf.format(new java.util.Date(s.startedAt)) : "未知";
                String shortId = s.sessionId.length() > 12 ? s.sessionId.substring(0, 12) + "..." : s.sessionId;
                String cwd = s.cwd != null ? s.cwd : "未知";
                String dirName = s.cwd != null ? java.nio.file.Path.of(s.cwd).getFileName().toString() : "未知";

                sb.append(String.format("**%d.** `%s`\n", i + 1, shortId));
                sb.append(String.format("   📁 `%s`\n", cwd));
                sb.append(String.format("   🕐 %s (%s)\n", time, s.status));
                if (i < sessions.size() - 1) sb.append("\n");
            }
            sb.append("\n💡 使用 `v-resume <序号>` 恢复会话");
        }
        ilinkService.sendText(userId, sb.toString(), contextToken);
    }

    private void handleResumeCommand(String userId, String[] parts, String contextToken) {
        if (parts.length < 2) {
            ilinkService.sendText(userId, "用法: v-resume <序号或Session ID>\n使用 v-disk-sessions 查看可用会话", contextToken);
            return;
        }

        String target = parts[1];
        java.util.List<ClaudeApiService.DiskSession> sessions = claudeApiService.getDiskSessions();

        // 尝试按序号匹配
        try {
            int index = Integer.parseInt(target) - 1;
            if (index >= 0 && index < sessions.size()) {
                String sessionId = sessions.get(index).sessionId;
                if (claudeApiService.resumeDiskSession(userId, sessionId)) {
                    ilinkService.sendText(userId, "已恢复会话: " + sessionId + "\n新请求将使用此会话", contextToken);
                    return;
                }
            }
        } catch (NumberFormatException e) {
            // 不是数字，尝试作为 sessionId 匹配
        }

        // 尝试按 sessionId 匹配
        if (claudeApiService.resumeDiskSession(userId, target)) {
            ilinkService.sendText(userId, "已恢复会话: " + target + "\n新请求将使用此会话", contextToken);
        } else {
            ilinkService.sendText(userId, "未找到会话: " + target + "\n使用 v-disk-sessions 查看可用会话", contextToken);
        }
    }
}
