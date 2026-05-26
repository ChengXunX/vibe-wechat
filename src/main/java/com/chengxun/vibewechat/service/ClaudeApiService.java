package com.chengxun.vibewechat.service;

import com.chengxun.vibewechat.config.ClaudeConfig;
import com.chengxun.vibewechat.config.FilterConfig;
import com.chengxun.vibewechat.config.ThinkingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class ClaudeApiService {

    @Autowired
    private ClaudeConfig claudeConfig;

    @Autowired
    private FilterConfig filterConfig;

    @Autowired
    private ThinkingConfig thinkingConfig;

    @Autowired
    private QuotaManager quotaManager;

    // 会话管理
    private final Map<String, TokenUsage> tokenUsageMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessionHistory = new ConcurrentHashMap<>();
    private final Map<String, String> subtaskNames = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // 磁盘会话缓存
    private List<DiskSession> diskSessions = new ArrayList<>();

    // 常驻进程池
    private final Map<String, List<ClaudeProcess>> processPools = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<PendingRequest>> requestQueues = new ConcurrentHashMap<>();

    // 空闲进程清理调度器
    private ScheduledExecutorService cleanupScheduler;

    @PostConstruct
    public void init() {
        if (claudeConfig.getInstallPath() == null || claudeConfig.getInstallPath().isEmpty()) {
            detectClaudePath();
        }

        // 清理孤儿进程
        cleanupOrphanProcesses();

        // 扫描磁盘会话
        scanDiskSessions();

        // 启动空闲进程清理任务
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "claude-process-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::cleanupIdleProcesses, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 服务关闭时销毁所有 Claude 子进程。
     * 正常关闭：进程池清空，所有子进程被 destroyForcibly 终止。
     * 异常关闭（kill -9）：Java 进程被杀，子进程可能成为孤儿进程。
     * 重启后：进程池为空，会话历史丢失，但 ~/.claude/sessions 仍保留磁盘会话。
     */
    @PreDestroy
    public void destroy() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
        }
        // 销毁所有进程
        processPools.values().forEach(pool ->
            pool.forEach(ClaudeProcess::destroy)
        );
        processPools.clear();
    }

    // ==================== 常驻进程内部类 ====================

    static class ClaudeProcess {
        Process process;
        OutputStream stdin;
        BufferedReader stdout;
        BufferedReader stderr;
        boolean busy = false;
        String sessionId;
        String thinkingLevel;
        String workDir;
        long lastActiveTime = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();

        synchronized boolean isAlive() {
            return process != null && process.isAlive();
        }

        synchronized void destroy() {
            busy = false;
            if (process != null) {
                process.destroyForcibly();
                process = null;
            }
            stdin = null;
            stdout = null;
            stderr = null;
        }
    }

    static class PendingRequest {
        String message;
        CompletableFuture<String> future;

        PendingRequest(String message, CompletableFuture<String> future) {
            this.message = message;
            this.future = future;
        }
    }

    // 磁盘会话信息
    static class DiskSession {
        String sessionId;
        String pid;
        String cwd;
        long startedAt;
        String status;

        DiskSession(String sessionId, String pid, String cwd, long startedAt, String status) {
            this.sessionId = sessionId;
            this.pid = pid;
            this.cwd = cwd;
            this.startedAt = startedAt;
            this.status = status;
        }
    }

    // ==================== 进程管理 ====================

    private ClaudeProcess getOrCreateProcess(String userId) {
        List<ClaudeProcess> pool = processPools.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

        // 查找空闲进程
        for (ClaudeProcess p : pool) {
            if (p.isAlive() && !p.busy) {
                return p;
            }
        }

        // 检查进程数上限
        if (pool.size() >= claudeConfig.getMaxProcessesPerUser()) {
            return null; // 需要排队
        }

        // 进程池为空时必须创建进程，否则队列中的消息将永远无法被处理
        if (pool.isEmpty()) {
            return startNewProcess(userId, pool);
        }

        // 根据配置决定：优先创建新进程还是排队
        if (claudeConfig.isPreferNewProcess()) {
            return startNewProcess(userId, pool);
        } else {
            // 优先排队等待空闲进程
            return null;
        }
    }

    private ClaudeProcess startNewProcess(String userId, List<ClaudeProcess> pool) {
        String installPath = claudeConfig.getInstallPath();
        if (installPath == null || installPath.isEmpty()) {
            return null;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(installPath);
            command.add("--print");
            command.add("--input-format");
            command.add("stream-json");
            command.add("--output-format");
            command.add("stream-json");
            command.add("--verbose");
            command.add("--dangerously-skip-permissions");

            // 模型配置
            String model = claudeConfig.getModel();
            if (model != null && !model.isEmpty()) {
                command.add("--model");
                command.add(model);
            }

            // thinking 配置
            if (thinkingConfig.isEnabled()) {
                String settingsJson = "{\"thinking\":{\"budget_tokens\":" + thinkingConfig.getCurrentBudgetTokens() + "}}";
                command.add("--settings");
                command.add(settingsJson);
            }

            // 检查是否有待恢复的 session
            String sessionId = sessionIds.get(userId);
            if (sessionId != null) {
                command.add("--resume");
                command.add(sessionId);
                log.info("Resuming session {} for user: {}", sessionId, userId);
            }

            // 工作目录
            String workDir = claudeConfig.getWorkDir();
            if (workDir == null || workDir.isEmpty()) {
                workDir = System.getProperty("user.dir");
            }

            log.info("Starting persistent Claude CLI process for user: {}, model: {}", userId, model);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            if (workDir != null && !workDir.isEmpty()) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            ClaudeProcess cp = new ClaudeProcess();
            cp.process = process;
            cp.stdin = process.getOutputStream();
            cp.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            cp.stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            cp.workDir = workDir;
            cp.thinkingLevel = thinkingConfig.getLevel();

            pool.add(cp);
            quotaManager.processStarted(userId);

            // 启动 stderr 读取线程（用于日志）
            Thread stderrThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = cp.stderr.readLine()) != null) {
                        log.info("Claude stderr for user {}: {}", userId, line);
                    }
                    log.info("Claude stderr stream closed for user: {}", userId);
                } catch (Exception e) {
                    log.debug("Claude stderr reader error for user {}: {}", userId, e.getMessage());
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            log.info("Claude CLI process started for user: {}", userId);
            return cp;

        } catch (Exception e) {
            log.error("Failed to start Claude CLI process for user: {}", userId, e);
            return null;
        }
    }

    // ==================== 消息发送 ====================

    public CompletableFuture<String> sendMessageAsync(String userId, String message) {
        CompletableFuture<String> future = new CompletableFuture<>();

        // 检查配额
        if (!quotaManager.canSendToolMessage(userId)) {
            future.complete("消息次数已达上限（10条），请等待当前任务完成");
            return future;
        }

        ClaudeProcess cp = getOrCreateProcess(userId);
        if (cp == null) {
            // 进程数达上限，加入队列
            BlockingQueue<PendingRequest> queue = requestQueues.computeIfAbsent(userId,
                    k -> new LinkedBlockingQueue<>());
            queue.offer(new PendingRequest(message, future));
            log.info("Request queued for user: {}, queue size: {}", userId, queue.size());
            return future;
        }

        // 异步处理请求
        cp.busy = true;
        cp.lastActiveTime = System.currentTimeMillis();

        CompletableFuture.runAsync(() -> {
            try {
                String response = processMessage(cp, userId, message);
                future.complete(response);
            } catch (Exception e) {
                future.completeExceptionally(e);
            } finally {
                cp.busy = false;
                quotaManager.processEnded(userId);
                processNextInQueue(userId);
            }
        });

        return future;
    }

    private String processMessage(ClaudeProcess cp, String userId, String message) {
        long startTime = System.currentTimeMillis();

        try {
            // 通过 stdin 发送消息
            String jsonMessage = objectMapper.writeValueAsString(
                    Map.of("type", "user_message", "content", message));
            log.info("Sending message to Claude stdin for user {}: {}", userId, jsonMessage.length() > 200 ? jsonMessage.substring(0, 200) + "..." : jsonMessage);
            cp.stdin.write((jsonMessage + "\n").getBytes());
            cp.stdin.flush();
            log.info("Message flushed to Claude stdin for user: {}", userId);

            // 读取响应
            StringBuilder output = new StringBuilder();
            String line;
            log.info("Waiting for Claude response on stdout for user: {}", userId);
            while ((line = cp.stdout.readLine()) != null) {
                if (line.isEmpty()) continue;
                log.debug("Claude stdout line for user {}: {}", userId, line.length() > 200 ? line.substring(0, 200) + "..." : line);

                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(line);
                    String type = node.get("type").asText();

                    if ("assistant".equals(type)) {
                        com.fasterxml.jackson.databind.JsonNode messageNode = node.get("message");
                        if (messageNode != null) {
                            com.fasterxml.jackson.databind.JsonNode contentNode = messageNode.get("content");
                            if (contentNode != null && contentNode.isArray()) {
                                for (com.fasterxml.jackson.databind.JsonNode item : contentNode) {
                                    String itemType = item.get("type").asText();
                                    if ("text".equals(itemType)) {
                                        output.append(item.get("text").asText());
                                    } else if ("tool_use".equals(itemType)) {
                                        String toolName = item.get("name").asText();
                                        String toolInput = item.get("input").toString();
                                        if (toolCallback != null) {
                                            toolCallback.onToolUse(userId, toolName, toolInput);
                                            if (isSubtaskTool(toolName)) {
                                                String subtaskStatus = extractSubtaskStatus(toolName, toolInput);
                                                if (subtaskStatus != null) {
                                                    boolean isCompleted = isSubtaskCompleted(toolName, toolInput);
                                                    toolCallback.onSubtaskStatus(userId, subtaskStatus, isCompleted);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if ("result".equals(type)) {
                        // 保存 session_id
                        com.fasterxml.jackson.databind.JsonNode sessionNode = node.get("session_id");
                        if (sessionNode != null) {
                            cp.sessionId = sessionNode.asText();
                            sessionIds.put(userId, sessionNode.asText());
                            sessionHistory.computeIfAbsent(userId, k -> new ArrayList<>());
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

                        // 收到 result，退出循环
                        break;
                    }
                } catch (Exception e) {
                    // 非 JSON 行，可能是纯文本输出
                    output.append(line).append("\n");
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            String result = output.toString().trim();
            log.info("Claude process responded in {}ms for user: {}", duration, userId);

            return result.isEmpty() ? "Claude 未返回结果" : result;

        } catch (Exception e) {
            log.error("Failed to process message for user: {}", userId, e);
            // 进程可能已死亡，销毁并标记
            cp.destroy();
            return "Claude 执行异常: " + e.getMessage();
        }
    }

    private void processNextInQueue(String userId) {
        BlockingQueue<PendingRequest> queue = requestQueues.get(userId);
        if (queue == null || queue.isEmpty()) return;

        PendingRequest next = queue.poll();
        if (next != null) {
            sendMessageAsync(userId, next.message).whenComplete((result, ex) -> {
                if (ex != null) {
                    next.future.completeExceptionally(ex);
                } else {
                    next.future.complete(result);
                }
            });
        }
    }

    // ==================== 进程管理命令 ====================

    public void destroyProcess(String userId) {
        List<ClaudeProcess> pool = processPools.remove(userId);
        if (pool != null) {
            pool.forEach(ClaudeProcess::destroy);
        }
        requestQueues.remove(userId);
    }

    public void destroyAllProcesses(String userId) {
        destroyProcess(userId);
    }

    public int getProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        return pool != null ? pool.size() : 0;
    }

    public boolean hasBusyProcesses(String userId) {
        return getBusyProcessCount(userId) > 0;
    }

    public void forceStopAll(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool != null) {
            for (ClaudeProcess p : pool) {
                p.destroy();
            }
            pool.clear();
        }
        processPools.remove(userId);
        requestQueues.remove(userId);
    }

    public int getBusyProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (p.busy) count++;
        }
        return count;
    }

    public String getProcessStatus(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) return "无活跃进程";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pool.size(); i++) {
            ClaudeProcess p = pool.get(i);
            sb.append(String.format("进程%d: %s | thinking=%s | session=%s | 状态=%s | 工作目录=%s\n",
                    i + 1,
                    p.isAlive() ? "运行中" : "已停止",
                    p.thinkingLevel != null ? p.thinkingLevel : "默认",
                    p.sessionId != null ? p.sessionId.substring(0, Math.min(8, p.sessionId.length())) + "..." : "无",
                    p.busy ? "忙碌" : "空闲",
                    p.workDir != null ? p.workDir : "默认"
            ));
        }
        return sb.toString();
    }

    // ==================== 空闲进程清理 ====================

    private void cleanupIdleProcesses() {
        long now = System.currentTimeMillis();
        long timeout = claudeConfig.getProcessIdleTimeoutMs();

        // 收集需要移除的空 userId
        java.util.List<String> emptyPools = new ArrayList<>();

        processPools.forEach((userId, pool) -> {
            // 先清理空闲超时的进程
            pool.removeIf(p -> {
                if (!p.busy && (now - p.lastActiveTime) > timeout) {
                    log.info("Cleaning up idle Claude process for user: {}", userId);
                    p.destroy();
                    quotaManager.processEnded(userId);
                    return true;
                }
                return false;
            });

            // 数量超限时，清理最早的空闲进程
            int maxProcesses = claudeConfig.getMaxProcessesPerUser();
            while (pool.size() > maxProcesses) {
                // 找到最早的空闲进程
                ClaudeProcess oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (ClaudeProcess p : pool) {
                    if (!p.busy && p.lastActiveTime < oldestTime) {
                        oldest = p;
                        oldestTime = p.lastActiveTime;
                    }
                }
                if (oldest != null) {
                    log.info("Cleaning up excess Claude process for user: {}, pool size: {}", userId, pool.size());
                    oldest.destroy();
                    quotaManager.processEnded(userId);
                    pool.remove(oldest);
                } else {
                    break; // 没有空闲进程可清理
                }
            }

            // 如果池空了，标记移除
            if (pool.isEmpty()) {
                emptyPools.add(userId);
            }
        });

        // 统一移除空池
        for (String userId : emptyPools) {
            processPools.remove(userId);
        }
    }

    // ==================== 工具调用回调 ====================

    public interface ToolCallback {
        void onToolUse(String userId, String toolName, String toolInput);
        void onToolResult(String userId, String result);
        void onSubtaskStatus(String userId, String status, boolean isCompleted);
    }

    private ToolCallback toolCallback;

    public void setToolCallback(ToolCallback callback) {
        this.toolCallback = callback;
    }

    private boolean isSubtaskTool(String toolName) {
        return "TaskCreate".equals(toolName) || "TaskUpdate".equals(toolName)
                || "TaskGet".equals(toolName) || "TaskList".equals(toolName);
    }

    private String extractSubtaskStatus(String toolName, String toolInput) {
        try {
            com.fasterxml.jackson.databind.JsonNode inputNode = objectMapper.readTree(toolInput);
            return switch (toolName) {
                case "TaskCreate" -> {
                    String subject = inputNode.has("subject") ? inputNode.get("subject").asText() : "";
                    String taskId = inputNode.has("taskId") ? inputNode.get("taskId").asText() : null;
                    if (taskId != null && !subject.isEmpty()) {
                        subtaskNames.put(taskId, subject);
                    }
                    yield "📋 创建子任务: " + subject;
                }
                case "TaskUpdate" -> {
                    String status = inputNode.has("status") ? inputNode.get("status").asText() : "更新";
                    String taskId = inputNode.has("taskId") ? inputNode.get("taskId").asText() : "";
                    String subject = inputNode.has("subject") ? inputNode.get("subject").asText() : "";
                    if (!subject.isEmpty() && taskId != null) {
                        subtaskNames.put(taskId, subject);
                    } else if (taskId != null) {
                        subject = subtaskNames.getOrDefault(taskId, "");
                    }
                    if ("completed".equals(status)) {
                        yield "✅ 子任务完成" + (subject.isEmpty() ? "" : ": " + subject);
                    }
                    yield "🔄 子任务 " + status + (subject.isEmpty() ? "" : ": " + subject);
                }
                case "TaskGet" -> "📖 查看子任务: " + (inputNode.has("taskId") ? inputNode.get("taskId").asText() : "");
                case "TaskList" -> "📋 列出所有子任务";
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSubtaskCompleted(String toolName, String toolInput) {
        try {
            if ("TaskUpdate".equals(toolName)) {
                com.fasterxml.jackson.databind.JsonNode inputNode = objectMapper.readTree(toolInput);
                return inputNode.has("status") && "completed".equals(inputNode.get("status").asText());
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    // ==================== 路径检测和配置加载 ====================

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

                int baseUrlStart = content.indexOf("\"ANTHROPIC_BASE_URL\":\"") + 22;
                int baseUrlEnd = content.indexOf("\"", baseUrlStart);
                if (baseUrlStart > 21 && baseUrlEnd > baseUrlStart) {
                    String baseUrl = content.substring(baseUrlStart, baseUrlEnd);
                    if (claudeConfig.getApiUrl() == null || claudeConfig.getApiUrl().isEmpty()) {
                        claudeConfig.setApiUrl(baseUrl);
                        log.info("Loaded Claude API URL: {}", baseUrl);
                    }
                }

                int tokenStart = content.indexOf("\"ANTHROPIC_AUTH_TOKEN\":\"") + 23;
                int tokenEnd = content.indexOf("\"", tokenStart);
                if (tokenStart > 22 && tokenEnd > tokenStart) {
                    String token = content.substring(tokenStart, tokenEnd);
                    if (claudeConfig.getApiKey() == null || claudeConfig.getApiKey().isEmpty()) {
                        claudeConfig.setApiKey(token);
                        log.info("Loaded Claude API Key: {}...{}", token.substring(0, Math.min(8, token.length())), token.substring(Math.max(0, token.length() - 4)));
                    }
                }

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

    // ==================== Token 用量 ====================

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

    public void clearHistory(String userId) {
        tokenUsageMap.remove(userId);
        sessionIds.remove(userId);
        sessionHistory.remove(userId);
        quotaManager.reset(userId);
        destroyProcess(userId);
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
            // 不销毁进程，让新请求自动创建新进程时使用 --resume
            return true;
        }
        return false;
    }

    public boolean deleteSession(String userId, String sessionId) {
        List<String> history = sessionHistory.get(userId);
        if (history != null && history.remove(sessionId)) {
            if (sessionId.equals(sessionIds.get(userId))) {
                sessionIds.remove(userId);
                destroyProcess(userId);
            }
            return true;
        }
        return false;
    }

    public TokenUsage getTokenUsage(String userId) {
        return tokenUsageMap.getOrDefault(userId, new TokenUsage());
    }

    public String getTokenUsageSummary(String userId) {
        TokenUsage usage = getTokenUsage(userId);
        return String.format("输入: %s, 输出: %s, 总计: %s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), formatTokens(usage.totalTokens));
    }

    public String getTaskCompletionSummary(String userId, long durationMs) {
        TokenUsage usage = getTokenUsage(userId);
        String duration = formatDuration(durationMs);
        String sessionId = sessionIds.get(userId);
        String sessionInfo = sessionId != null ? "\n📋 Session: `" + sessionId + "`" : "";
        int contextWindow = parseContextWindowSize(claudeConfig.getModel());
        int contextPercent = contextWindow > 0 ? Math.min(100, (int) (usage.inputTokens * 100.0 / contextWindow)) : 0;
        String contextInfo = "\n🧠 上下文: " + contextPercent + "% (" + formatTokens(usage.inputTokens) + "/" + formatTokens(contextWindow) + ")";
        return String.format("---\n📊 本次: %s in / %s out | ⏱️ %s%s%s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), duration, sessionInfo, contextInfo);
    }

    private int parseContextWindowSize(String model) {
        if (model == null) return claudeConfig.getContextWindowSize();
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
        try {
            String summary = extractCoreIntent(originalMessage);
            return summary.isEmpty() ? "任务处理完成" : summary;
        } catch (Exception e) {
            return "任务处理完成";
        }
    }

    private String extractCoreIntent(String message) {
        String cleaned = message
                .replaceAll("^(请|帮我|帮忙|麻烦|能不能|可以|是否|是不是|有没有)\\s*", "")
                .replaceAll("(一下|吗|呢|吧|啊|哦|嗯|好的|谢谢|感谢)\\s*$", "")
                .trim();

        if (cleaned.length() <= 15) {
            return cleaned;
        }

        String[] sentenceEnders = {"。", "！", "？", ".", "!", "?"};
        int minEnd = cleaned.length();
        for (String ender : sentenceEnders) {
            int pos = cleaned.indexOf(ender);
            if (pos > 0 && pos < minEnd) {
                minEnd = pos;
            }
        }

        if (minEnd < cleaned.length()) {
            return cleaned.substring(0, minEnd + 1).trim();
        }

        return cleaned.length() > 20 ? cleaned.substring(0, 20) + "..." : cleaned;
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

    // ==================== 磁盘会话管理 ====================

    /**
     * 扫描 ~/.claude/sessions/ 目录获取所有磁盘会话
     */
    public void scanDiskSessions() {
        diskSessions.clear();
        Path sessionsDir = Path.of(System.getProperty("user.home"), ".claude", "sessions");
        if (!Files.exists(sessionsDir)) {
            log.info("Claude sessions directory not found: {}", sessionsDir);
            return;
        }

        try {
            Files.list(sessionsDir)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        String content = new String(Files.readAllBytes(path));
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(content);

                        String sessionId = node.has("sessionId") ? node.get("sessionId").asText() : null;
                        String pid = node.has("pid") ? node.get("pid").asText() : null;
                        String cwd = node.has("cwd") ? node.get("cwd").asText() : null;
                        long startedAt = node.has("startedAt") ? node.get("startedAt").asLong() : 0;
                        String status = node.has("status") ? node.get("status").asText() : "unknown";

                        if (sessionId != null) {
                            diskSessions.add(new DiskSession(sessionId, pid, cwd, startedAt, status));
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse session file: {}", path, e);
                    }
                });

            log.info("Found {} disk sessions", diskSessions.size());
        } catch (Exception e) {
            log.error("Failed to scan disk sessions", e);
        }
    }

    /**
     * 获取所有磁盘会话列表
     */
    public List<DiskSession> getDiskSessions() {
        scanDiskSessions();
        return diskSessions;
    }

    /**
     * 从磁盘恢复会话
     */
    public boolean resumeDiskSession(String userId, String sessionId) {
        // 检查会话是否存在
        boolean exists = diskSessions.stream().anyMatch(s -> s.sessionId.equals(sessionId));
        if (!exists) {
            // 重新扫描一次
            scanDiskSessions();
            exists = diskSessions.stream().anyMatch(s -> s.sessionId.equals(sessionId));
        }

        if (exists) {
            sessionIds.put(userId, sessionId);
            sessionHistory.computeIfAbsent(userId, k -> new ArrayList<>());
            if (!sessionHistory.get(userId).contains(sessionId)) {
                sessionHistory.get(userId).add(sessionId);
            }
            log.info("Resumed disk session {} for user: {}", sessionId, userId);
            return true;
        }
        return false;
    }

    /**
     * 清理孤儿进程（不属于当前服务的 Claude 进程）
     * 在服务启动时调用，清理异常关闭后遗留的子进程
     */
    public void cleanupOrphanProcesses() {
        log.info("Checking for orphan Claude processes...");

        try {
            // 获取当前 Java 进程的 PID
            long currentPid = ProcessHandle.current().pid();

            // 遍历所有 Java 子进程
            ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command().map(cmd -> cmd.contains("claude")).orElse(false))
                .forEach(ph -> {
                    // 检查父进程是否是当前 Java 进程
                    ph.parent().ifPresent(parent -> {
                        if (parent.pid() == currentPid) {
                            // 这是当前服务的子进程，不处理
                            return;
                        }

                        // 检查父进程是否还活着
                        if (!parent.isAlive()) {
                            // 父进程已死，这是孤儿进程
                            log.info("Found orphan Claude process: PID={}, killing...", ph.pid());
                            ph.destroyForcibly();
                        }
                    });

                    // 没有父进程的也是孤儿
                    if (ph.parent().isEmpty()) {
                        log.info("Found orphan Claude process (no parent): PID={}, killing...", ph.pid());
                        ph.destroyForcibly();
                    }
                });

        } catch (Exception e) {
            log.error("Failed to cleanup orphan processes", e);
        }
    }

    // ==================== Getters and Setters ====================

    public ClaudeConfig getClaudeConfig() { return claudeConfig; }

    public String getApiKey() { return claudeConfig.getApiKey(); }
    public void setApiKey(String key) { claudeConfig.setApiKey(key); }
    public String getApiUrl() { return claudeConfig.getApiUrl(); }
    public void setApiUrl(String url) { claudeConfig.setApiUrl(url); }
    public String getModel() { return claudeConfig.getModel(); }
    public void setModel(String model) { claudeConfig.setModel(model); }
    public String getInstallPath() { return claudeConfig.getInstallPath(); }
    public void setInstallPath(String path) { claudeConfig.setInstallPath(path); }
    public String getWorkDir() { return claudeConfig.getWorkDir(); }
    public void setWorkDir(String dir) { claudeConfig.setWorkDir(dir); }
    public int getContextWindowSize() { return claudeConfig.getContextWindowSize(); }
    public void setContextWindowSize(int size) { claudeConfig.setContextWindowSize(size); }

    // 进程配置
    public int getMaxProcessesPerUser() { return claudeConfig.getMaxProcessesPerUser(); }
    public void setMaxProcessesPerUser(int max) { claudeConfig.setMaxProcessesPerUser(max); }
    public long getProcessIdleTimeoutMs() { return claudeConfig.getProcessIdleTimeoutMs(); }
    public void setProcessIdleTimeoutMs(long timeout) { claudeConfig.setProcessIdleTimeoutMs(timeout); }
    public boolean isPreferNewProcess() { return claudeConfig.isPreferNewProcess(); }
    public void setPreferNewProcess(boolean prefer) { claudeConfig.setPreferNewProcess(prefer); }

    public String getThinkingLevel() { return thinkingConfig.getLevel(); }
    public void setThinkingLevel(String level) { thinkingConfig.setLevel(level); }
    public boolean isThinkingEnabled() { return thinkingConfig.isEnabled(); }
    public int getThinkingBudgetTokens() { return thinkingConfig.getCurrentBudgetTokens(); }
}
