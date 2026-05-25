package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.ClaudeConfig;
import com.chengxun.vibewechat.config.FilterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ClaudeApiService {

    @Autowired
    private ClaudeConfig claudeConfig;

    @Autowired
    private FilterConfig filterConfig;

    private final Map<String, List<Map<String, String>>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<String, TokenUsage> tokenUsageMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessionHistory = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @PostConstruct
    public void init() {
        if (claudeConfig.getInstallPath() == null || claudeConfig.getInstallPath().isEmpty()) {
            detectClaudePath();
        }
    }

    private void detectClaudePath() {
        String[] commonPaths = {
            "/usr/bin/claude",
            "/usr/local/bin/claude",
            "/opt/claude/bin/claude",
            System.getProperty("user.home") + "/claude/bin/claude",
            System.getProperty("user.home") + "/.local/bin/claude",
            System.getProperty("user.home") + "/.claude/bin/claude"
        };

        for (String path : commonPaths) {
            if (Files.exists(Path.of(path))) {
                claudeConfig.setInstallPath(path);
                log.info("Detected Claude installation at: {}", path);
                loadClaudeConfig();
                return;
            }
        }

        try {
            Process process = new ProcessBuilder("which", "claude").start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (!output.isEmpty() && Files.exists(Path.of(output))) {
                claudeConfig.setInstallPath(output);
                log.info("Detected Claude installation at: {}", output);
                loadClaudeConfig();
                return;
            }
        } catch (Exception e) {
            log.debug("Failed to find Claude via which command");
        }

        log.warn("Claude installation not found");
    }

    private void loadClaudeConfig() {
        try {
            String configPath = System.getProperty("user.home") + "/.claude/settings.json";
            Path configFilePath = Path.of(configPath);

            if (Files.exists(configFilePath)) {
                String content = new String(Files.readAllBytes(configFilePath));

                // 提取 ANTHROPIC_BASE_URL
                int baseUrlStart = content.indexOf("\"ANTHROPIC_BASE_URL\":\"") + 22;
                int baseUrlEnd = content.indexOf("\"", baseUrlStart);
                if (baseUrlStart > 21 && baseUrlEnd > baseUrlStart) {
                    String baseUrl = content.substring(baseUrlStart, baseUrlEnd);
                    if (claudeConfig.getApiUrl() == null || claudeConfig.getApiUrl().isEmpty()) {
                        claudeConfig.setApiUrl(baseUrl);
                        log.info("Loaded Claude API URL: {}", baseUrl);
                    }
                }

                // 提取 ANTHROPIC_AUTH_TOKEN
                int tokenStart = content.indexOf("\"ANTHROPIC_AUTH_TOKEN\":\"") + 23;
                int tokenEnd = content.indexOf("\"", tokenStart);
                if (tokenStart > 22 && tokenEnd > tokenStart) {
                    String token = content.substring(tokenStart, tokenEnd);
                    if (claudeConfig.getApiKey() == null || claudeConfig.getApiKey().isEmpty()) {
                        claudeConfig.setApiKey(token);
                        log.info("Loaded Claude API Key: {}...{}", token.substring(0, Math.min(8, token.length())), token.substring(Math.max(0, token.length() - 4)));
                    }
                }

                // 提取 ANTHROPIC_MODEL
                int modelStart = content.indexOf("\"ANTHROPIC_MODEL\":\"") + 20;
                int modelEnd = content.indexOf("\"", modelStart);
                if (modelStart > 19 && modelEnd > modelStart) {
                    String model = content.substring(modelStart, modelEnd);
                    if (claudeConfig.getModel() == null || claudeConfig.getModel().isEmpty()) {
                        claudeConfig.setModel(model);
                        log.info("Loaded Claude Model: {}", model);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load Claude config: {}", e.getMessage());
        }
    }

    public static class TokenUsage {
        private int inputTokens = 0;
        private int outputTokens = 0;
        private int totalTokens = 0;

        public synchronized void add(int input, int output) {
            this.inputTokens += input;
            this.outputTokens += output;
            this.totalTokens = inputTokens + outputTokens;
        }

        public synchronized int getInputTokens() { return inputTokens; }
        public synchronized int getOutputTokens() { return outputTokens; }
        public synchronized int getTotalTokens() { return totalTokens; }
    }

    private final java.util.Map<String, Long> lastDurationMsMap = new ConcurrentHashMap<>();

    public String sendMessage(String userId, String message) {
        String installPath = claudeConfig.getInstallPath();
        if (installPath == null || installPath.isEmpty()) {
            return "Claude 未安装或路径未配置";
        }

        long startTime = System.currentTimeMillis();

        try {
            // 构建 Claude CLI 命令
            List<String> command = new ArrayList<>();
            command.add(installPath);
            command.add("--print");
            command.add("--output-format");
            command.add("stream-json");
            command.add("--verbose");
            command.add("--dangerously-skip-permissions");

            // 添加模型配置（支持 [1m] 等配置）
            String model = claudeConfig.getModel();
            if (model != null && !model.isEmpty()) {
                command.add("--model");
                command.add(model);
            }

            // 添加会话恢复（保持上下文）
            String sessionId = sessionIds.get(userId);
            if (sessionId != null) {
                command.add("--resume");
                command.add(sessionId);
            }

            // 添加消息（作为最后一个参数）
            command.add(message);

            log.info("Executing Claude CLI with model: {}", model);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            // 设置工作目录
            String workDir = System.getProperty("user.dir");
            if (workDir != null) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            // 读取流式输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 跳过警告信息行
                    if (line.startsWith("Warning:") || line.startsWith("Error:")) {
                        continue;
                    }

                    // 尝试解析 stream-json 格式
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(line);

                        String type = node.get("type").asText();

                        if ("assistant".equals(type)) {
                            // 助手消息 - 提取文本和工具调用
                            com.fasterxml.jackson.databind.JsonNode messageNode = node.get("message");
                            if (messageNode != null) {
                                com.fasterxml.jackson.databind.JsonNode contentNode = messageNode.get("content");
                                if (contentNode != null && contentNode.isArray()) {
                                    for (com.fasterxml.jackson.databind.JsonNode item : contentNode) {
                                        String itemType = item.get("type").asText();
                                        if ("text".equals(itemType)) {
                                            output.append(item.get("text").asText());
                                        } else if ("tool_use".equals(itemType)) {
                                            // 工具调用
                                            String toolName = item.get("name").asText();
                                            String toolInput = item.get("input").toString();
                                            if (toolCallback != null) {
                                                toolCallback.onToolUse(userId, toolName, toolInput);
                                            }
                                        }
                                    }
                                }
                            }
                        } else if ("result".equals(type)) {
                            // 最终结果
                            String result = node.get("result").asText();
                            output.append(result);

                            // 保存 session_id
                            com.fasterxml.jackson.databind.JsonNode sessionNode = node.get("session_id");
                            if (sessionNode != null) {
                                sessionIds.put(userId, sessionNode.asText());
                                sessionHistory.computeIfAbsent(userId, k -> new java.util.ArrayList<>());
                                if (!sessionHistory.get(userId).contains(sessionNode.asText())) {
                                    sessionHistory.get(userId).add(sessionNode.asText());
                                }
                            }

                            // 解析 token 使用量
                            com.fasterxml.jackson.databind.JsonNode usage = node.get("usage");
                            if (usage != null) {
                                int inputTokens = usage.get("input_tokens").asInt();
                                int outputTokens = usage.get("output_tokens").asInt();
                                TokenUsage tokenUsage = tokenUsageMap.computeIfAbsent(userId, k -> new TokenUsage());
                                tokenUsage.add(inputTokens, outputTokens);
                            }
                        }
                    } catch (Exception e) {
                        // 非 JSON 行，可能是纯文本输出
                        output.append(line).append("\n");
                    }
                }
            }

            int exitCode = process.waitFor();
            long duration = System.currentTimeMillis() - startTime;
            lastDurationMsMap.put(userId, duration);

            String result = output.toString().trim();

            log.info("Claude CLI completed in {}ms, exit code: {}", duration, exitCode);

            if (exitCode == 0 && !result.isEmpty()) {
                return result;
            } else if (exitCode != 0) {
                log.error("Claude CLI exited with code: {}, output: {}", exitCode, result);
                return "Claude 执行失败 (exit code: " + exitCode + ")\n" + result;
            } else {
                return "Claude 未返回结果";
            }
        } catch (Exception e) {
            log.error("Failed to execute Claude CLI", e);
            return "Claude 执行异常: " + e.getMessage();
        }
    }

    // 工具调用回调接口
    public interface ToolCallback {
        void onToolUse(String userId, String toolName, String toolInput);
        void onToolResult(String userId, String result);
        void onSubtaskStatus(String userId, String status);
    }

    private ToolCallback toolCallback;

    public void setToolCallback(ToolCallback callback) {
        this.toolCallback = callback;
    }

    private String parseJsonResponse(String userId, String json) {
        try {
            // 使用 Jackson 解析 JSON
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);

            // 获取 result 字段
            String result = root.get("result").asText();

            // 保存 session_id 用于维持上下文
            com.fasterxml.jackson.databind.JsonNode sessionNode = root.get("session_id");
            if (sessionNode != null) {
                String sessionId = sessionNode.asText();
                sessionIds.put(userId, sessionId);
                // 添加到会话历史
                sessionHistory.computeIfAbsent(userId, k -> new java.util.ArrayList<>());
                if (!sessionHistory.get(userId).contains(sessionId)) {
                    sessionHistory.get(userId).add(sessionId);
                }
            }

            // 解析 token 使用量
            com.fasterxml.jackson.databind.JsonNode usage = root.get("usage");
            if (usage != null) {
                int inputTokens = usage.get("input_tokens").asInt();
                int outputTokens = usage.get("output_tokens").asInt();
                TokenUsage tokenUsage = tokenUsageMap.computeIfAbsent(userId, k -> new TokenUsage());
                tokenUsage.add(inputTokens, outputTokens);
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to parse JSON response", e);
            return "解析响应失败";
        }
    }

    private void parseJsonTokenUsage(String userId, String json) {
        try {
            int inputStart = json.indexOf("\"input_tokens\":") + 15;
            int inputEnd = json.indexOf(",", inputStart);
            if (inputStart > 14 && inputEnd > inputStart) {
                int inputTokens = Integer.parseInt(json.substring(inputStart, inputEnd).trim());
                int outputStart = json.indexOf("\"output_tokens\":", inputEnd) + 16;
                int outputEnd = json.indexOf(",", outputStart);
                if (outputStart > 15 && outputEnd > outputStart) {
                    int outputTokens = Integer.parseInt(json.substring(outputStart, outputEnd).trim());
                    TokenUsage usage = tokenUsageMap.computeIfAbsent(userId, k -> new TokenUsage());
                    usage.add(inputTokens, outputTokens);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse token usage", e);
        }
    }

    public void clearHistory(String userId) {
        conversationHistory.remove(userId);
        tokenUsageMap.remove(userId);
        sessionIds.remove(userId);
        sessionHistory.remove(userId);
    }

    public String getSessionId(String userId) {
        return sessionIds.get(userId);
    }

    public List<String> getSessionHistory(String userId) {
        return sessionHistory.getOrDefault(userId, new ArrayList<>());
    }

    public boolean switchSession(String userId, String sessionId) {
        List<String> history = sessionHistory.get(userId);
        if (history != null && history.contains(sessionId)) {
            sessionIds.put(userId, sessionId);
            return true;
        }
        return false;
    }

    public boolean deleteSession(String userId, String sessionId) {
        List<String> history = sessionHistory.get(userId);
        if (history != null && history.remove(sessionId)) {
            // 如果删除的是当前会话，清除当前会话
            if (sessionId.equals(sessionIds.get(userId))) {
                sessionIds.remove(userId);
            }
            return true;
        }
        return false;
    }

    public List<Map<String, String>> getHistory(String userId) {
        return conversationHistory.getOrDefault(userId, new ArrayList<>());
    }

    public TokenUsage getTokenUsage(String userId) {
        return tokenUsageMap.getOrDefault(userId, new TokenUsage());
    }

    public String getTokenUsageSummary(String userId) {
        TokenUsage usage = getTokenUsage(userId);
        return String.format("输入: %s, 输出: %s, 总计: %s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), formatTokens(usage.totalTokens));
    }

    public long getLastDurationMs(String userId) {
        return lastDurationMsMap.getOrDefault(userId, 0L);
    }

    public String getTaskCompletionSummary(String userId, long durationMs) {
        TokenUsage usage = getTokenUsage(userId);
        String duration = formatDuration(durationMs);
        String sessionId = sessionIds.get(userId);
        String sessionInfo = sessionId != null ? "\n📋 Session: `" + sessionId + "`" : "";
        int contextWindow = parseContextWindowSize(claudeConfig.getModel());
        int contextPercent = contextWindow > 0 ? (int) (usage.inputTokens * 100.0 / contextWindow) : 0;
        String contextInfo = "\n🧠 上下文: " + contextPercent + "% (" + formatTokens(usage.inputTokens) + "/" + formatTokens(contextWindow) + ")";
        return String.format("---\n📊 Token: %s in / %s out | ⏱️ %s%s%s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), duration, sessionInfo, contextInfo);
    }

    private int parseContextWindowSize(String model) {
        if (model == null) return claudeConfig.getContextWindowSize();
        // 从模型名称解析上下文窗口大小，如 "claude-sonnet-4-20250514[1m]"
        if (model.contains("[1m]") || model.contains("[1M]")) {
            return 1000000;
        } else if (model.contains("[200k]") || model.contains("[200K]")) {
            return 200000;
        }
        return claudeConfig.getContextWindowSize();
    }

    private String formatTokens(int tokens) {
        if (tokens >= 1000000) {
            return String.format("%.1fm", tokens / 1000000.0);
        } else if (tokens >= 1000) {
            return String.format("%.1fk", tokens / 1000.0);
        }
        return String.valueOf(tokens);
    }

    public String getTaskSummary(String userId, String originalMessage) {
        // 使用 Claude 对用户消息进行缩句，只输出摘要，不回答问题
        String installPath = claudeConfig.getInstallPath();
        if (installPath == null || installPath.isEmpty()) {
            return "任务完成";
        }

        try {
            String prompt = "你是一个摘要助手。请对以下用户指令进行缩句，提取核心任务意图，不超过10个字。只输出缩句结果，不要回答问题，不要解释，不要添加任何其他内容。用户指令：" + originalMessage;
            List<String> command = new ArrayList<>();
            command.add(installPath);
            command.add("--print");
            command.add("--model");
            command.add(claudeConfig.getModel());
            command.add(prompt);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("Warning:") && !line.startsWith("Error:")) {
                        output.append(line);
                    }
                }
            }
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

            String summary = output.toString().trim();
            // 去除可能的引号
            summary = summary.replaceAll("^['\"]|['\"]$", "");
            return summary.isEmpty() ? "任务完成" : summary;
        } catch (Exception e) {
            return "任务完成";
        }
    }

    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        long seconds = ms / 1000;
        long remainingMs = ms % 1000;
        if (seconds < 60) {
            return seconds + "s" + remainingMs + "ms";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + "m" + seconds + "s" + remainingMs + "ms";
    }

    private void parseTokenUsage(String userId, String responseBody) {
        try {
            int usageStart = responseBody.indexOf("\"usage\":{");
            if (usageStart == -1) return;

            int inputStart = responseBody.indexOf("\"input_tokens\":", usageStart) + 15;
            int inputEnd = responseBody.indexOf(",", inputStart);
            int inputTokens = Integer.parseInt(responseBody.substring(inputStart, inputEnd).trim());

            int outputStart = responseBody.indexOf("\"output_tokens\":", inputEnd) + 16;
            int outputEnd = responseBody.indexOf("}", outputStart);
            int outputTokens = Integer.parseInt(responseBody.substring(outputStart, outputEnd).trim());

            TokenUsage usage = tokenUsageMap.computeIfAbsent(userId, k -> new TokenUsage());
            usage.add(inputTokens, outputTokens);
        } catch (Exception e) {
            log.debug("Failed to parse token usage", e);
        }
    }

    private String buildRequestBody(List<Map<String, String>> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // 处理模型名：去除 [1m] 等后缀，这些是 CLI 设置不是 API 参数
        String modelName = claudeConfig.getModel();
        if (modelName != null && modelName.contains("[")) {
            modelName = modelName.substring(0, modelName.indexOf("["));
        }
        sb.append("\"model\":\"").append(escapeJson(modelName)).append("\",");
        sb.append("\"max_tokens\":").append(claudeConfig.getMaxTokens()).append(",");

        // 添加 thinking 模式支持
        if (claudeConfig.isThinkingEnabled()) {
            sb.append("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":")
              .append(claudeConfig.getThinkingBudgetTokens()).append("},");
        }

        sb.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Map<String, String> msg = messages.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(msg.get("role")).append("\",\"content\":\"")
              .append(escapeJson(msg.get("content"))).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String parseResponse(String responseBody) {
        try {
            int contentStart = responseBody.indexOf("\"text\":\"") + 8;
            if (contentStart <= 7) return "解析响应失败";

            // 找到文本内容的结束位置（处理转义的引号）
            int contentEnd = contentStart;
            boolean escaped = false;
            while (contentEnd < responseBody.length()) {
                char c = responseBody.charAt(contentEnd);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                }
                contentEnd++;
            }

            if (contentEnd > contentStart) {
                String text = responseBody.substring(contentStart, contentEnd);
                // 处理 Unicode 转义
                text = text.replace("\\n", "\n")
                          .replace("\\\"", "\"")
                          .replace("\\\\", "\\");
                // 处理 Unicode surrogate pairs (如 😀)
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})");
                java.util.regex.Matcher matcher = pattern.matcher(text);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    int codePoint = Integer.parseInt(matcher.group(1), 16);
                    matcher.appendReplacement(sb, new String(Character.toChars(codePoint)));
                }
                matcher.appendTail(sb);
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("Failed to parse Claude response", e);
        }
        return "解析响应失败";
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    // Getters and Setters
    public String getApiKey() { return claudeConfig.getApiKey(); }
    public void setApiKey(String key) { claudeConfig.setApiKey(key); }
    public String getApiUrl() { return claudeConfig.getApiUrl(); }
    public void setApiUrl(String url) { claudeConfig.setApiUrl(url); }
    public String getModel() { return claudeConfig.getModel(); }
    public void setModel(String model) { claudeConfig.setModel(model); }
    public String getInstallPath() { return claudeConfig.getInstallPath(); }
    public void setInstallPath(String path) { claudeConfig.setInstallPath(path); }
    public boolean isThinkingEnabled() { return claudeConfig.isThinkingEnabled(); }
    public void setThinkingEnabled(boolean enabled) { claudeConfig.setThinkingEnabled(enabled); }
    public int getThinkingBudgetTokens() { return claudeConfig.getThinkingBudgetTokens(); }
    public void setThinkingBudgetTokens(int budget) { claudeConfig.setThinkingBudgetTokens(budget); }
    public int getContextWindowSize() { return claudeConfig.getContextWindowSize(); }
    public void setContextWindowSize(int size) { claudeConfig.setContextWindowSize(size); }
}
