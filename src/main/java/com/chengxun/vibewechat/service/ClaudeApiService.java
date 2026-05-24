package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.model.ClaudeMessage;
import com.chengxun.vibewechat.model.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ClaudeApiService {

    @Value("${vibe-wechat.claude.api-key:}")
    private String apiKey;

    @Value("${vibe-wechat.claude.api-url:https://api.anthropic.com}")
    private String apiUrl;

    @Value("${vibe-wechat.claude.model:claude-sonnet-4-20250514}")
    private String model;

    @Value("${vibe-wechat.claude.max-tokens:4096}")
    private int maxTokens;

    @Autowired
    private FilterConfig filterConfig;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final Map<String, List<Map<String, String>>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<String, TokenUsage> tokenUsageMap = new ConcurrentHashMap<>();

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
                    .uri(URI.create(apiUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
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

    public String sendWithFilter(String userId, String message) {
        String response = sendMessage(userId, message);

        // 根据过滤配置决定是否显示
        ClaudeMessage claudeMsg = new ClaudeMessage(userId, response, MessageType.TEXT);
        if (!filterConfig.isShowResultsOnly()) {
            return response;
        }

        // 如果启用了结果过滤，只返回结果部分
        if (isResultMessage(response)) {
            return response;
        }
        return null;
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
            // 解析 usage 部分
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
        sb.append("\"model\":\"").append(model).append("\",");
        sb.append("\"max_tokens\":").append(maxTokens).append(",");
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
        // 简单解析 Claude API 响应
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

    private boolean isResultMessage(String message) {
        // 判断是否为结果消息
        return message.contains("已完成") || message.contains("结果") ||
               message.contains("成功") || message.contains("失败") ||
               message.length() < 200;
    }

    // Getters and Setters
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}
