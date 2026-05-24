package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.ClaudeConfig;
import com.chengxun.vibewechat.config.FilterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final Map<String, List<Map<String, String>>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<String, TokenUsage> tokenUsageMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 自动检测 Claude 安装路径
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

        // 尝试通过 which 命令查找
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
        List<Map<String, String>> history = conversationHistory.computeIfAbsent(userId, k -> new ArrayList<>());

        // 添加用户消息
        history.add(Map.of("role", "user", "content", message));

        try {
            String requestBody = buildRequestBody(history);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(claudeConfig.getApiUrl() + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", claudeConfig.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String assistantMessage = parseResponse(response.body());
                history.add(Map.of("role", "assistant", "content", assistantMessage));

                // 解析 token 使用量
                parseTokenUsage(userId, response.body());

                return assistantMessage;
            } else {
                log.error("Claude API error: {} - {}", response.statusCode(), response.body());
                return "Claude API 调用失败: " + response.statusCode();
            }
        } catch (Exception e) {
            log.error("Failed to call Claude API", e);
            return "Claude API 调用异常: " + e.getMessage();
        }
    }

    public void clearHistory(String userId) {
        conversationHistory.remove(userId);
        tokenUsageMap.remove(userId);
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
        sb.append("\"model\":\"").append(escapeJson(claudeConfig.getModel())).append("\",");
        sb.append("\"max_tokens\":").append(claudeConfig.getMaxTokens()).append(",");
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
            int contentEnd = responseBody.indexOf("\"", contentStart);
            if (contentStart > 7 && contentEnd > contentStart) {
                return responseBody.substring(contentStart, contentEnd)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"");
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
}
