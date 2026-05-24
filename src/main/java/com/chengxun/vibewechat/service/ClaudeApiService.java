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
        public int inputTokens = 0;
        public int outputTokens = 0;
        public int totalTokens = 0;

        public void add(int input, int output) {
            this.inputTokens += input;
            this.outputTokens += output;
            this.totalTokens = inputTokens + outputTokens;
        }
    }

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

            // 添加模型配置（支持 [1m] 等配置）
            String model = claudeConfig.getModel();
            if (model != null && !model.isEmpty()) {
                command.add("--model");
                command.add(model);
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

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            long duration = System.currentTimeMillis() - startTime;

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

    public void clearHistory(String userId) {
        conversationHistory.remove(userId);
        tokenUsageMap.remove(userId);
    }

    public String getSessionId(String userId) {
        // 生成基于 userId 的会话 ID
        return "session_" + Integer.toHexString(userId.hashCode());
    }

    public List<Map<String, String>> getHistory(String userId) {
        return conversationHistory.getOrDefault(userId, new ArrayList<>());
    }

    public TokenUsage getTokenUsage(String userId) {
        return tokenUsageMap.getOrDefault(userId, new TokenUsage());
    }

    public String getTokenUsageSummary(String userId) {
        TokenUsage usage = getTokenUsage(userId);
        return String.format("输入: %d tokens, 输出: %d tokens, 总计: %d tokens",
                usage.inputTokens, usage.outputTokens, usage.totalTokens);
    }

    public String getTaskCompletionSummary(String userId, long durationMs) {
        TokenUsage usage = getTokenUsage(userId);
        String duration = formatDuration(durationMs);
        return String.format("---\n📊 Token: %d in / %d out | ⏱️ %s",
                usage.inputTokens, usage.outputTokens, duration);
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
}
