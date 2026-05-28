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
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
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

    // 会话管理（tokenUsageMap 按 sessionId 统计，确保切换 session 后数据独立）
    private final Map<String, TokenUsage> tokenUsageMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIds = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessionHistory = new ConcurrentHashMap<>();
    // 每个进程独立的子任务队列（userId:processIndex -> Queue<subject>）
    private final Map<String, Queue<String>> subtaskQueues = new ConcurrentHashMap<>();
    // 追踪 tool_use ID -> toolName 的映射（用于匹配 tool_result）
    private final Map<String, String> pendingToolUseIds = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    // 上下文压缩记忆文档（sessionId -> 文件路径）
    private final Map<String, String> memoryDocuments = new ConcurrentHashMap<>();

    // 进程 ID 生成器
    private static final java.util.concurrent.atomic.AtomicLong processIdGenerator = new java.util.concurrent.atomic.AtomicLong(0);

    // 磁盘会话缓存
    private List<DiskSession> diskSessions = new ArrayList<>();

    // 常驻进程池
    private final Map<String, List<ClaudeProcess>> processPools = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<PendingRequest>> requestQueues = new ConcurrentHashMap<>();
    // 当前活跃进程索引（用于 v-fork 克隆当前进程）
    private final Map<String, Integer> currentProcessIndex = new ConcurrentHashMap<>();
    // 延迟重启标记（groupId -> true，配置变更后等任务完成再重启）
    private final Map<String, Boolean> pendingRestart = new ConcurrentHashMap<>();

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
        // 销毁所有进程组
        processPools.values().forEach(pool ->
            pool.forEach(ClaudeProcess::destroy)
        );
        processPools.clear();
        requestQueues.clear();
    }

    // ==================== 常驻进程内部类 ====================
    // ClaudeProcess 是伪进程（Worker），常驻在进程池中
    // 内部持有的 process/stdin/stderr 是临时的 Claude CLI 进程资源

    static class ClaudeProcess {
        // 临时 Claude CLI 进程资源（每次任务创建，任务完成后销毁）
        Process process;
        OutputStream stdin;
        BufferedReader stdout;
        BufferedReader stderr;

        // Worker 状态（常驻）
        volatile boolean busy = false;
        String sessionId;
        String thinkingLevel;
        String workDir;
        long lastActiveTime = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();

        // 进程标识
        String processId;               // 进程唯一ID（用于父子关系绑定）
        String groupId;                 // 进程组ID（组内共享 session 和工作目录）

        // Fork 进程组支持
        boolean isParent = false;       // 是否为初始主进程
        String parentId = null;         // 父进程的 processId（fork 子进程才有）
        boolean isIndependent = false;  // 是否为独立进程（v-newproc 创建）

        /**
         * 检查 Worker 是否有活跃的 Claude CLI 进程
         */
        synchronized boolean hasActiveCliProcess() {
            return process != null && process.isAlive();
        }

        /**
         * 销毁临时 Claude CLI 进程，但保留 Worker
         */
        synchronized void destroyCliProcess() {
            if (process != null) {
                process.destroyForcibly();
                process = null;
            }
            stdin = null;
            stdout = null;
            stderr = null;
        }

        /**
         * 销毁整个 Worker（包括 CLI 进程）
         */
        synchronized void destroy() {
            busy = false;
            destroyCliProcess();
        }
    }

    static class PendingRequest {
        String message;
        CompletableFuture<String> future;
        String groupId;  // 任务所属进程组

        PendingRequest(String message, CompletableFuture<String> future, String groupId) {
            this.message = message;
            this.future = future;
            this.groupId = groupId;
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

        // 获取当前活跃进程组ID
        int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
        String activeGroupId = null;
        if (activeIndex >= 0 && activeIndex < pool.size()) {
            activeGroupId = pool.get(activeIndex).groupId;
        }

        // 若该组有延迟重启标记，禁止新任务进入（排队等待重启完成后再处理）
        if (activeGroupId != null) {
            String restartKey = userId + ":" + activeGroupId;
            if (pendingRestart.containsKey(restartKey)) {
                return null;
            }
        }

        // 1. 在当前进程组内查找空闲 Worker
        if (activeGroupId != null) {
            for (ClaudeProcess p : pool) {
                if (activeGroupId.equals(p.groupId) && !p.busy) {
                    if (!p.hasActiveCliProcess()) {
                        if (!attachCliProcess(p, userId)) {
                            continue;
                        }
                    }
                    return p;
                }
            }
            // 当前进程组全忙，排队（不让其他进程组帮忙）
            return null;
        }

        // 2. 没有活跃进程组（首次使用），创建主进程
        if (pool.isEmpty()) {
            ClaudeProcess cp = createNewWorker(userId, pool, true);
            if (!attachCliProcess(cp, userId)) {
                pool.remove(cp);
                return null;
            }
            currentProcessIndex.put(userId, 0);
            return cp;
        }

        // 3. 有进程但无活跃组（异常情况），排队
        return null;
    }

    /**
     * 为 Worker 附加一个 Claude CLI 进程
     */
    private boolean attachCliProcess(ClaudeProcess worker, String userId) {
        String installPath = claudeConfig.getInstallPath();
        if (installPath == null || installPath.isEmpty()) {
            return false;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(installPath);
            command.add("--print");
            command.add("--input-format");
            command.add("text");
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

            // 使用 Worker 的 session（恢复上下文）
            String sessionId = worker.sessionId;
            if (sessionId != null) {
                command.add("--resume");
                command.add(sessionId);
                log.info("Worker attaching CLI with session: {} for user: {}", sessionId, userId);
            }

            // 使用 Worker 的工作目录
            String workDir = worker.workDir;
            if (workDir == null || workDir.isEmpty()) {
                workDir = claudeConfig.getWorkDir();
            }
            if (workDir == null || workDir.isEmpty()) {
                workDir = System.getProperty("user.dir");
            }

            log.info("Attaching Claude CLI process for worker, user: {}, model: {}", userId, model);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            if (workDir != null && !workDir.isEmpty()) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            worker.process = process;
            worker.stdin = process.getOutputStream();
            worker.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            worker.stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            quotaManager.processStarted(userId);

            return true;

        } catch (Exception e) {
            log.error("Failed to attach Claude CLI process for user: {}", userId, e);
            return false;
        }
    }

    /**
     * 创建新的 Worker（伪进程）
     */
    private ClaudeProcess createNewWorker(String userId, List<ClaudeProcess> pool, boolean isParent) {
        return createNewWorker(userId, pool, isParent, false);
    }

    private ClaudeProcess createNewWorker(String userId, List<ClaudeProcess> pool, boolean isParent, boolean isIndependent) {
        ClaudeProcess worker = new ClaudeProcess();
        worker.processId = String.valueOf(processIdGenerator.incrementAndGet());
        worker.groupId = worker.processId;  // 新进程自成一组
        worker.isParent = isParent;
        worker.isIndependent = isIndependent;
        worker.thinkingLevel = thinkingConfig.getLevel();

        // 设置工作目录
        String workDir = claudeConfig.getWorkDir();
        if (workDir == null || workDir.isEmpty()) {
            workDir = System.getProperty("user.dir");
        }
        worker.workDir = workDir;

        // 如果有 session，设置到 Worker
        String sessionId = sessionIds.get(userId);
        if (sessionId != null) {
            worker.sessionId = sessionId;
        }

        pool.add(worker);
        log.info("Created new worker for user: {}, isParent: {}", userId, isParent);
        return worker;
    }

    /**
     * Fork 子 Worker：从指定源进程克隆，共享 session、工作目录和 thinking 配置
     * 子进程绑定到进程组根进程（不允许嵌套绑定）
     */
    private ClaudeProcess forkProcess(String userId, List<ClaudeProcess> pool, ClaudeProcess source) {
        if (source == null) {
            log.warn("No source process found for user: {}, cannot fork", userId);
            return null;
        }

        // 找到进程组的根进程（processId == groupId）
        ClaudeProcess groupRoot = null;
        for (ClaudeProcess p : pool) {
            if (source.groupId.equals(p.processId)) {
                groupRoot = p;
                break;
            }
        }
        if (groupRoot == null) groupRoot = source;

        ClaudeProcess child = new ClaudeProcess();
        child.processId = String.valueOf(processIdGenerator.incrementAndGet());
        child.groupId = source.groupId;  // 子进程继承源进程的组ID
        child.isParent = false;
        child.sessionId = source.sessionId;
        child.workDir = source.workDir;
        child.thinkingLevel = source.thinkingLevel;

        // 独立进程的子进程也标记为独立
        if (source.isIndependent) {
            child.isIndependent = true;
        }
        // 绑定到进程组根进程（扁平结构，不允许嵌套）
        child.parentId = groupRoot.processId;

        pool.add(child);
        log.info("Forked child worker for user: {}, source independent: {}, pool size: {}", userId, source.isIndependent, pool.size());
        return child;
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
            // 当前进程组全忙，加入队列
            String groupId = getActiveGroupId(userId);
            BlockingQueue<PendingRequest> queue = requestQueues.computeIfAbsent(userId,
                    k -> new LinkedBlockingQueue<>());
            queue.offer(new PendingRequest(message, future, groupId));
            log.info("Request queued for user: {}, group: {}, queue size: {}", userId, groupId, queue.size());
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
                processNextInQueue(userId);
                checkAndApplyDeferredRestart(userId);
            }
        });

        return future;
    }

    /**
     * 发送消息并返回结果 Future 和处理该消息的进程序号
     * @return SendResult 包含 future 和 processIndex（1-based，排队时为 -1）
     */
    public SendResult sendMessageWithIndex(String userId, String message) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (!quotaManager.canSendToolMessage(userId)) {
            future.complete("消息次数已达上限（10条），请等待当前任务完成");
            return new SendResult(future, -1);
        }

        ClaudeProcess cp = getOrCreateProcess(userId);
        if (cp == null) {
            String groupId = getActiveGroupId(userId);
            BlockingQueue<PendingRequest> queue = requestQueues.computeIfAbsent(userId,
                    k -> new LinkedBlockingQueue<>());
            queue.offer(new PendingRequest(message, future, groupId));
            log.info("Request queued for user: {}, group: {}, queue size: {}", userId, groupId, queue.size());
            return new SendResult(future, -1);
        }

        int processIndex = getProcessIndex(userId, cp);

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
                processNextInQueue(userId);
                checkAndApplyDeferredRestart(userId);
            }
        });

        return new SendResult(future, processIndex);
    }

    public record SendResult(CompletableFuture<String> future, int processIndex) {}

    private String processMessage(ClaudeProcess cp, String userId, String message) {
        long startTime = System.currentTimeMillis();
        int processIndex = getProcessIndex(userId, cp);

        try {
            // 检查 CLI 进程是否还活着
            if (!cp.hasActiveCliProcess()) {
                log.error("Claude CLI process is not alive for user: {}", userId);
                return "Claude CLI 进程已死亡";
            }

            // 如果没有 session（新会话），检查是否有记忆文档可恢复
            String effectiveMessage = message;
            if (cp.sessionId == null) {
                String workDir = cp.workDir;
                if (workDir == null || workDir.isEmpty()) {
                    workDir = claudeConfig.getWorkDir();
                }
                if (workDir == null || workDir.isEmpty()) {
                    workDir = System.getProperty("user.dir");
                }
                String memoryContent = getLatestMemoryDocument(workDir);
                if (memoryContent != null && !memoryContent.isEmpty()) {
                    effectiveMessage = "[系统上下文恢复] 以下是之前会话的压缩记忆文档，请基于此上下文继续工作：\n\n" + memoryContent + "\n\n---\n\n[用户消息] " + message;
                    log.info("Prepended memory document to message for user: {}", userId);
                }
            }

            // 通过 stdin 发送消息（text 格式，纯文本）
            log.info("Sending message to Claude stdin for user {}: {}", userId, effectiveMessage.length() > 200 ? effectiveMessage.substring(0, 200) + "..." : effectiveMessage);
            cp.stdin.write((effectiveMessage + "\n").getBytes());
            cp.stdin.flush();
            // 关闭 stdin 通知 Claude 输入结束，开始处理
            cp.stdin.close();
            log.info("Message flushed and stdin closed for user: {}", userId);

            // 后台收集 stderr 输出（用于错误诊断）
            StringBuilder stderrOutput = new StringBuilder();
            Thread stderrCollector = new Thread(() -> {
                try {
                    String line;
                    while ((line = cp.stderr.readLine()) != null) {
                        stderrOutput.append(line).append("\n");
                        log.info("Claude stderr for user {}: {}", userId, line);
                    }
                } catch (Exception e) {
                    log.debug("Claude stderr reader error for user {}: {}", userId, e.getMessage());
                }
            });
            stderrCollector.setDaemon(true);
            stderrCollector.start();

            // 读取响应
            StringBuilder output = new StringBuilder();
            String line;
            log.info("Waiting for Claude response on stdout for user: {}", userId);
            int lineCount = 0;
            while ((line = cp.stdout.readLine()) != null) {
                lineCount++;
                if (line.isEmpty()) continue;
                log.info("Claude stdout line {} for user {}: {}", lineCount, userId, line.length() > 200 ? line.substring(0, 200) + "..." : line);

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
                                        String text = item.get("text").asText();
                                        output.append(text);
                                        // 发送决策消息（不受配额限制）
                                        if (toolCallback != null && !text.isEmpty()) {
                                            toolCallback.onDecisionMessage(userId, text);
                                        }
                                    } else if ("tool_use".equals(itemType)) {
                                        String toolName = item.get("name").asText();
                                        String toolInput = item.get("input").toString();
                                        String toolUseId = item.has("id") ? item.get("id").asText() : null;
                                        if (toolUseId != null) {
                                            pendingToolUseIds.put(toolUseId, toolName);
                                        }
                                        if (toolCallback != null) {
                                            toolCallback.onToolUse(userId, toolName, toolInput, processIndex);
                                            if (isSubtaskTool(toolName)) {
                                                String subtaskStatus = extractSubtaskStatus(toolName, toolInput, userId, processIndex);
                                                if (subtaskStatus != null) {
                                                    boolean isCompleted = isSubtaskCompleted(toolName, toolInput);
                                                    toolCallback.onSubtaskStatus(userId, subtaskStatus, isCompleted, processIndex);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if ("error".equals(type)) {
                        // 捕获 Claude API 错误（额度用尽、过载等）
                        String errorMsg = "";
                        if (node.has("error")) {
                            com.fasterxml.jackson.databind.JsonNode errorNode = node.get("error");
                            if (errorNode.has("message")) {
                                errorMsg = errorNode.get("message").asText();
                            } else if (errorNode.has("type")) {
                                errorMsg = errorNode.get("type").asText();
                            } else {
                                errorMsg = errorNode.toString();
                            }
                        } else if (node.has("message")) {
                            errorMsg = node.get("message").asText();
                        }
                        if (errorMsg.isEmpty()) {
                            errorMsg = node.toString();
                        }
                        output.append("⚠️ Claude API 错误: ").append(errorMsg).append("\n");
                        log.error("Claude API error for user {}: {}", userId, errorMsg);
                    } else if ("user".equals(type)) {
                        // 处理 tool_result 事件，根据 tool_use_id 匹配对应的工具
                        com.fasterxml.jackson.databind.JsonNode messageNode = node.get("message");
                        if (messageNode != null) {
                            com.fasterxml.jackson.databind.JsonNode contentNode = messageNode.get("content");
                            if (contentNode != null && contentNode.isArray()) {
                                for (com.fasterxml.jackson.databind.JsonNode item : contentNode) {
                                    if (item.has("type") && "tool_result".equals(item.get("type").asText())) {
                                        String toolUseId = item.has("tool_use_id") ? item.get("tool_use_id").asText() : null;
                                        String toolName = toolUseId != null ? pendingToolUseIds.remove(toolUseId) : null;
                                        if (toolName != null && toolCallback != null) {
                                            String result = "";
                                            if (item.has("content")) {
                                                com.fasterxml.jackson.databind.JsonNode resultContent = item.get("content");
                                                if (resultContent.isTextual()) {
                                                    result = resultContent.asText();
                                                } else if (resultContent.isArray()) {
                                                    StringBuilder sb = new StringBuilder();
                                                    for (com.fasterxml.jackson.databind.JsonNode rc : resultContent) {
                                                        if (rc.has("text")) sb.append(rc.get("text").asText());
                                                    }
                                                    result = sb.toString();
                                                }
                                            }
                                            toolCallback.onToolResult(userId, toolName, toolUseId, result, processIndex);
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

                            // session 变更时同步到子进程
                            syncSessionToChildren(userId, cp.processId, sessionNode.asText());
                        }

                        // result 事件也可能包含 content（兼容新版本 Claude CLI）
                        com.fasterxml.jackson.databind.JsonNode resultContent = node.get("content");
                        if (resultContent != null && resultContent.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode item : resultContent) {
                                if (item.has("type") && "text".equals(item.get("type").asText())) {
                                    String text = item.get("text").asText();
                                    output.append(text);
                                }
                            }
                        }

                        // 解析 token 使用量（按 sessionId 统计，确保切换 session 后数据独立）
                        com.fasterxml.jackson.databind.JsonNode usage = node.get("usage");
                        if (usage != null) {
                            int inputTokens = usage.get("input_tokens").asInt();
                            int outputTokens = usage.get("output_tokens").asInt();
                            String currentSessionId = cp.sessionId != null ? cp.sessionId : sessionIds.get(userId);
                            if (currentSessionId != null) {
                                TokenUsage tokenUsage = tokenUsageMap.computeIfAbsent(currentSessionId, k -> new TokenUsage());
                                tokenUsage.add(inputTokens, outputTokens);
                            }
                        }

                        // 收到 result，退出循环
                        break;
                    } else if ("system".equals(type)) {
                        String subtype = node.has("subtype") ? node.get("subtype").asText() : "";
                        if (toolCallback != null) {
                            if ("task_notification".equals(subtype)) {
                                String toolUseId = node.has("tool_use_id") ? node.get("tool_use_id").asText() : null;
                                String matchedToolName = toolUseId != null ? pendingToolUseIds.get(toolUseId) : null;
                                // Agent 的 task_notification 走 onToolResult（去重）
                                if ("Agent".equals(matchedToolName)) {
                                    String summary = node.has("summary") ? node.get("summary").asText() : "";
                                    toolCallback.onToolResult(userId, "Agent", toolUseId, summary, processIndex);
                                } else {
                                    String summary = node.has("summary") ? node.get("summary").asText() : "";
                                    String taskStatus = node.has("status") ? node.get("status").asText() : "";
                                    boolean isCompleted = "completed".equals(taskStatus);
                                    String statusText = isCompleted
                                            ? "✅ 子任务完成" + (summary.isEmpty() ? "" : ": " + summary)
                                            : "🔄 子任务 " + taskStatus + (summary.isEmpty() ? "" : ": " + summary);
                                    toolCallback.onSubtaskStatus(userId, statusText, isCompleted, processIndex);
                                }
                            } else if ("task_started".equals(subtype)) {
                                String description = node.has("description") ? node.get("description").asText() : "";
                                toolCallback.onSubtaskStatus(userId, "📋 子任务开始" + (description.isEmpty() ? "" : ": " + description), false, processIndex);
                            }
                        }
                    }
                } catch (Exception e) {
                    // 非 JSON 行，可能是纯文本输出
                    output.append(line).append("\n");
                }
            }

            // 等待 stderr 收集线程完成
            stderrCollector.join(3000);

            long duration = System.currentTimeMillis() - startTime;

            String result = output.toString().trim();
            log.info("Claude process responded in {}ms for user: {}", duration, userId);

            // 关闭 stdin 后 CLI 进程会退出，销毁 CLI 进程但保留 Worker
            cp.destroyCliProcess();
            quotaManager.processEnded(userId);

            // 检查 stderr 是否有错误信息
            String stderr = stderrOutput.toString().trim();
            boolean hasStderrError = !stderr.isEmpty() && (
                    stderr.contains("error") || stderr.contains("Error") ||
                    stderr.contains("ERROR") || stderr.contains("quota") ||
                    stderr.contains("limit") || stderr.contains("overloaded") ||
                    stderr.contains("rate_limit") || stderr.contains("authentication"));

            if (result.isEmpty()) {
                if (!stderr.isEmpty()) {
                    return "Claude 未返回结果\n\n错误信息:\n" + stderr;
                }
                return "Claude 未返回结果";
            }

            // 如果有 stderr 错误信息，附加到结果中
            if (hasStderrError) {
                result = result + "\n\n⚠️ 错误信息:\n" + stderr;
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to process message for user: {}", userId, e);
            // CLI 进程可能已死亡，销毁但保留 Worker
            cp.destroyCliProcess();
            quotaManager.processEnded(userId);
            return "Claude 执行异常: " + e.getMessage();
        }
    }

    private void processNextInQueue(String userId) {
        BlockingQueue<PendingRequest> queue = requestQueues.get(userId);
        if (queue == null || queue.isEmpty()) return;

        // 先 peek 看下一个任务属于哪个进程组
        PendingRequest peeked = queue.peek();
        if (peeked == null) return;

        // 若该进程组有延迟重启标记，不处理（等重启完成后再释放）
        String groupId = peeked.groupId;
        String restartKey = userId + ":" + groupId;
        if (pendingRestart.containsKey(restartKey)) return;

        // 检查该进程组是否有空闲 Worker
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return;

        boolean hasIdleWorker = pool.stream()
                .anyMatch(p -> groupId.equals(p.groupId) && !p.busy);
        if (!hasIdleWorker) {
            return;
        }

        // 有空闲 Worker，取出并处理
        PendingRequest next = queue.poll();
        if (next == null) return;

        CompletableFuture<String> resultFuture = sendMessageAsync(userId, next.message);
        resultFuture.whenComplete((result, ex) -> {
            if (ex != null) {
                next.future.completeExceptionally(ex);
            } else {
                next.future.complete(result);
            }
        });
    }

    /**
     * 取消所有待处理的排队请求，完成其 future 避免 handleMessage 永久阻塞
     */
    private void cancelPendingRequests(String userId) {
        BlockingQueue<PendingRequest> queue = requestQueues.remove(userId);
        if (queue != null) {
            PendingRequest pending;
            while ((pending = queue.poll()) != null) {
                pending.future.complete("⚠️ 进程已销毁，排队任务已取消");
                log.info("Cancelled pending request for user: {}", userId);
            }
        }
    }

    /**
     * 父进程 session 变更时同步到其子进程
     */
    private void syncSessionToChildren(String userId, String parentProcessId, String newSessionId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return;

        for (ClaudeProcess p : pool) {
            if (parentProcessId.equals(p.parentId)) {
                p.sessionId = newSessionId;
                log.info("Synced new session {} to fork child for user: {}", newSessionId, userId);
            }
        }
    }

    // ==================== 进程管理命令 ====================

    public void destroyProcess(String userId) {
        List<ClaudeProcess> pool = processPools.remove(userId);
        if (pool != null) {
            pool.forEach(ClaudeProcess::destroy);
        }
        cancelPendingRequests(userId);
    }

    /**
     * 销毁用户所有进程
     */
    public void destroyProcessGroup(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool != null) {
            pool.forEach(ClaudeProcess::destroy);
        }
        processPools.remove(userId);
        cancelPendingRequests(userId);
    }

    public void destroyAllProcesses(String userId) {
        destroyProcess(userId);
    }

    /**
     * 销毁当前活跃进程组的所有进程（配置变更时使用）
     * 其他进程组不受影响，下次使用时会自动应用新配置
     */
    public void destroyCurrentGroupProcesses(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return;
        String groupId = getActiveGroupId(userId);
        if (groupId == null) return;
        pool.removeIf(p -> {
            if (groupId.equals(p.groupId)) {
                p.destroy();
                quotaManager.processEnded(userId);
                return true;
            }
            return false;
        });
        // 清除当前 session（仅影响当前组）
        sessionIds.remove(userId);
        if (pool.isEmpty()) {
            processPools.remove(userId);
            currentProcessIndex.remove(userId);
        }
    }

    public int getProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        return pool != null ? pool.size() : 0;
    }

    /**
     * 获取当前活跃进程的组ID
     */
    private String getActiveGroupId(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) return null;
        int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
        if (activeIndex >= 0 && activeIndex < pool.size()) {
            return pool.get(activeIndex).groupId;
        }
        return null;
    }

    /**
     * 标记当前进程组需要延迟重启（配置变更后调用）
     * 不会中断正在执行的任务，等所有任务完成后再静默重启
     */
    public void markGroupForRestart(String userId) {
        String groupId = getActiveGroupId(userId);
        if (groupId != null) {
            pendingRestart.put(userId + ":" + groupId, true);
            log.info("Marked group {} for deferred restart for user: {}", groupId, userId);
        }
    }

    /**
     * 检查并执行延迟重启：当进程组内所有进程都空闲时，销毁并重建
     * 在任务完成时调用
     */
    private void checkAndApplyDeferredRestart(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) return;
        String groupId = getActiveGroupId(userId);
        if (groupId == null) return;

        String key = userId + ":" + groupId;
        if (!pendingRestart.containsKey(key)) return;

        // 检查该组是否全部空闲
        boolean groupIdle = pool.stream()
                .filter(p -> groupId.equals(p.groupId))
                .noneMatch(p -> p.busy);
        if (!groupIdle) return;

        // 全部空闲，执行重启
        pendingRestart.remove(key);
        pool.removeIf(p -> {
            if (groupId.equals(p.groupId)) {
                p.destroy();
                quotaManager.processEnded(userId);
                return true;
            }
            return false;
        });
        sessionIds.remove(userId);
        if (pool.isEmpty()) {
            processPools.remove(userId);
            currentProcessIndex.remove(userId);
        }
        log.info("Applied deferred restart for group {} user: {}", groupId, userId);

        // 重启完成后，处理排队任务（会用新配置创建新进程）
        // 先确保有可用的 Worker
        pool = processPools.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        if (pool.isEmpty()) {
            // 没有 Worker，创建新的（使用新配置）
            ClaudeProcess newWorker = createNewWorker(userId, pool, true);
            if (attachCliProcess(newWorker, userId)) {
                currentProcessIndex.put(userId, 0);
            } else {
                pool.remove(newWorker);
            }
        }
        processNextInQueue(userId);
    }

    public boolean hasBusyProcesses(String userId) {
        return getBusyProcessCount(userId) > 0;
    }

    /**
     * 检查是否有空闲 worker 可立即处理消息
     */
    public boolean hasIdleProcess(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return false;

        for (ClaudeProcess p : pool) {
            if (!p.busy) {
                return true;
            }
        }

        // 如果池为空或全忙，但未达上限，可以创建新 worker
        return pool.size() < claudeConfig.getMaxProcessesPerUser();
    }

    public void forceStopAll(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return;
        String groupId = getActiveGroupId(userId);
        if (groupId == null) return;
        // 只停止当前进程组的进程
        pool.removeIf(p -> {
            if (groupId.equals(p.groupId)) {
                p.destroy();
                quotaManager.processEnded(userId);
                return true;
            }
            return false;
        });
        if (pool.isEmpty()) {
            processPools.remove(userId);
            currentProcessIndex.remove(userId);
        }
        processNextInQueue(userId);
    }

    /**
     * 停止当前活跃进程的任务
     */
    public String forceStopCurrentProcess(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
        if (activeIndex < 0 || activeIndex >= pool.size()) {
            activeIndex = 0;
        }

        ClaudeProcess target = pool.get(activeIndex);
        if (!target.busy) {
            return "当前进程没有正在运行的任务";
        }

        target.destroy();
        pool.remove(target);
        quotaManager.processEnded(userId);

        // 修正索引
        if (activeIndex >= pool.size()) {
            currentProcessIndex.put(userId, Math.max(0, pool.size() - 1));
        }
        if (pool.isEmpty()) {
            processPools.remove(userId);
            currentProcessIndex.remove(userId);
        }

        processNextInQueue(userId);
        return "已停止当前进程的任务";
    }

    /**
     * 停止指定进程的任务
     */
    public String forceStopProcess(String userId, int index) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        if (index < 0 || index >= pool.size()) {
            return "无效序号，范围: 1-" + pool.size();
        }

        ClaudeProcess target = pool.get(index);

        if (!target.busy) {
            return "进程" + (index + 1) + "没有正在运行的任务";
        }

        target.destroy();
        pool.remove(target);
        quotaManager.processEnded(userId);

        // 修正索引
        int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
        if (activeIndex >= pool.size()) {
            currentProcessIndex.put(userId, Math.max(0, pool.size() - 1));
        }
        if (pool.isEmpty()) {
            processPools.remove(userId);
            currentProcessIndex.remove(userId);
        }

        String type;
        if (target.parentId != null) {
            type = "子进程";
        } else {
            type = "独立进程";
        }

        processNextInQueue(userId);
        return "已停止" + type + " (序号: " + (index + 1) + ")";
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

    /**
     * 获取当前活跃进程组的忙碌进程数
     */
    public int getGroupBusyProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        String groupId = getActiveGroupId(userId);
        if (groupId == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (groupId.equals(p.groupId) && p.busy) count++;
        }
        return count;
    }

    /**
     * 获取当前进程组的空闲进程数（排除独立进程）
     * 普通消息只会分配给进程组内的 worker，独立进程不会被使用
     */
    public int getGroupIdleProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        String groupId = getActiveGroupId(userId);
        if (groupId == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (groupId.equals(p.groupId) && !p.busy) count++;
        }
        return count;
    }

    /**
     * 获取进程在池中的序号（1-based），找不到返回 -1
     */
    public int getProcessIndex(String userId, ClaudeProcess target) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || target == null) return -1;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i) == target) return i + 1;
        }
        return -1;
    }

    /**
     * 获取当前活跃进程组的进程数
     */
    public int getGroupProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        String groupId = getActiveGroupId(userId);
        if (groupId == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (groupId.equals(p.groupId)) count++;
        }
        return count;
    }

    public int getIndependentProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (p.isIndependent) count++;
        }
        return count;
    }

    /**
     * 统计有 fork 子进程的进程数
     */
    public int getParentProcessCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess parent : pool) {
            boolean hasChildren = pool.stream().anyMatch(c -> parent.processId.equals(c.parentId));
            if (hasChildren) count++;
        }
        return count;
    }

    /**
     * 统计指定进程的 fork 子进程数
     */
    public int getForkChildCount(String userId, String parentProcessId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (parentProcessId.equals(p.parentId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 统计所有 fork 子进程数（兼容旧调用）
     */
    public int getForkChildCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (p.parentId != null) {
                count++;
            }
        }
        return count;
    }

    public String getProcessStatus(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) return "无活跃进程";

        StringBuilder sb = new StringBuilder();
        sb.append("| # | 类型 | 来源 | 会话ID | 工作目录 | 状态 |\n");
        sb.append("|---|------|------|--------|----------|------|\n");

        for (int i = 0; i < pool.size(); i++) {
            ClaudeProcess p = pool.get(i);
            // 判断该进程是否有子进程
            boolean hasChildren = pool.stream().anyMatch(c -> p.processId.equals(c.parentId));

            String type;
            String source;
            if (hasChildren) {
                type = "父进程";
                source = "-";
            } else if (p.parentId != null) {
                // fork 子进程：找到父进程的序号
                type = "子进程";
                int srcIdx = -1;
                for (int j = 0; j < pool.size(); j++) {
                    if (pool.get(j).processId.equals(p.parentId)) {
                        srcIdx = j + 1;
                        break;
                    }
                }
                source = srcIdx > 0 ? "克隆自#" + srcIdx : "-";
            } else {
                type = "独立进程";
                source = "-";
            }
            String sessionId = p.sessionId != null ? p.sessionId.substring(0, Math.min(12, p.sessionId.length())) + "..." : "无";
            String workDir = p.workDir != null ? p.workDir : "默认";
            String status = p.busy ? "忙碌" : "空闲";

            sb.append(String.format("| %d | %s | %s | `%s` | `%s` | %s |\n",
                    i + 1,
                    type,
                    source,
                    sessionId,
                    workDir,
                    status
            ));
        }
        return sb.toString();
    }

    /**
     * 切换到指定序号的进程（同时切换会话和工作目录）
     */
    public String switchProcess(String userId, int index) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        if (index < 0 || index >= pool.size()) {
            return "无效序号，范围: 1-" + pool.size();
        }

        ClaudeProcess target = pool.get(index);

        // 记录当前进程索引
        currentProcessIndex.put(userId, index);

        // 切换会话
        if (target.sessionId != null) {
            sessionIds.put(userId, target.sessionId);
            syncSessionToChildren(userId, target.processId, target.sessionId);
        }

        String type;
        if (pool.stream().anyMatch(p -> target.processId.equals(p.parentId))) {
            type = "父进程";
        } else if (target.parentId != null) {
            type = "子进程";
        } else {
            type = "独立进程";
        }
        String busyHint = target.busy ? " [忙碌中]" : "";
        return String.format("已切换到进程%d (%s)%s\n\n📋 会话ID: `%s`\n📁 工作目录: `%s`",
                index + 1,
                type,
                busyHint,
                target.sessionId != null ? target.sessionId.substring(0, Math.min(12, target.sessionId.length())) + "..." : "无",
                target.workDir != null ? target.workDir : "默认"
        );
    }

    /**
     * 克隆当前进程
     * 如果当前进程是子进程，则克隆到父进程下（不传递子进程）
     */
    public String cloneCurrentProcess(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);

        // 检查进程数上限
        int currentCount = pool != null ? pool.size() : 0;
        if (currentCount >= claudeConfig.getMaxProcessesPerUser()) {
            return "进程数已达上限 (" + currentCount + "/" + claudeConfig.getMaxProcessesPerUser() + ")";
        }

        // 检查是否有进程可以克隆
        if (pool == null || pool.isEmpty()) {
            return "无进程可克隆，请先发送消息创建父进程";
        }

        // 获取当前活跃进程索引，默认为 0（父进程）
        int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
        if (activeIndex < 0 || activeIndex >= pool.size()) {
            activeIndex = 0;
        }

        ClaudeProcess activeProcess = pool.get(activeIndex);

        // 克隆当前活跃进程
        // 克隆当前活跃进程（而非固定克隆父进程）
        ClaudeProcess cp = forkProcess(userId, pool, activeProcess);

        if (cp == null) {
            return "克隆进程失败";
        }

        // 尝试处理排队的消息
        processNextInQueue(userId);

        return String.format("✅ 已克隆进程\n\n📋 会话ID: `%s`\n📁 工作目录: `%s`",
                cp.sessionId != null ? cp.sessionId.substring(0, Math.min(12, cp.sessionId.length())) + "..." : "无",
                cp.workDir != null ? cp.workDir : "默认"
        );
    }

    /**
     * 创建新独立进程（非克隆），可选绑定指定 session
     * 新进程不在任何进程组，不共享任务
     */
    public String createNewProcess(String userId, String sessionId) {
        return createNewProcess(userId, sessionId, null);
    }

    /**
     * 创建新独立进程（非克隆），可选绑定指定 session 和工作目录
     * 新进程不在任何进程组，不共享任务
     * @param sessionId 会话ID，null 表示不绑定会话
     * @param workDir 工作目录，null 表示使用默认目录
     */
    public String createNewProcess(String userId, String sessionId, String workDir) {
        List<ClaudeProcess> pool = processPools.get(userId);

        // 检查进程数上限
        int currentCount = pool != null ? pool.size() : 0;
        if (currentCount >= claudeConfig.getMaxProcessesPerUser()) {
            return "进程数已达上限 (" + currentCount + "/" + claudeConfig.getMaxProcessesPerUser() + ")";
        }

        // 如果指定了 session，验证是否有效
        if (sessionId != null) {
            List<String> history = sessionHistory.get(userId);
            if (history == null || !history.contains(sessionId)) {
                return "未找到会话: " + sessionId;
            }
            // 临时设置 session 用于创建进程
            sessionIds.put(userId, sessionId);
        }

        // 获取或创建进程池
        if (pool == null) {
            pool = processPools.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        }

        // 创建新独立 Worker（不是克隆，标记为独立进程，isParent=false 避免被当作父进程统计）
        ClaudeProcess cp = createNewWorker(userId, pool, false, true);
        if (cp == null) {
            return "创建新进程失败";
        }

        // 如果指定了工作目录，覆盖默认值
        if (workDir != null) {
            cp.workDir = workDir;
        }

        // 如果未指定 session，清除 worker 继承的 sessionId（使用路径参数时不绑定会话）
        if (sessionId == null) {
            cp.sessionId = null;
        }

        // 更新当前活跃进程索引为新创建的进程
        currentProcessIndex.put(userId, pool.size() - 1);

        // 尝试处理排队的消息
        processNextInQueue(userId);

        String sessionDisplay = cp.sessionId != null ? cp.sessionId.substring(0, Math.min(12, cp.sessionId.length())) + "..." : "首次使用时自动创建";
        return String.format("✅ 已创建新进程\n\n📋 会话: `%s`\n📁 工作目录: `%s`",
                sessionDisplay,
                cp.workDir != null ? cp.workDir : "默认"
        );
    }

    /**
     * 删除指定进程
     * 如果该进程有子进程（克隆进程），则不可删除，必须先删除子进程
     */
    public String deleteProcess(String userId, int index) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        if (index < 0 || index >= pool.size()) {
            return "无效序号，范围: 1-" + pool.size();
        }

        ClaudeProcess target = pool.get(index);

        // 检查是否有子进程
        boolean hasChildren = pool.stream().anyMatch(p -> target.processId.equals(p.parentId));
        if (hasChildren) {
            return "该进程有子进程，无法删除\n请先使用 v-delproc 删除子进程";
        }

        // 检查进程是否忙碌
        if (target.busy) {
            return "进程" + (index + 1) + "正在忙碌中，无法删除";
        }

        // 销毁进程
        target.destroy();
        pool.remove(target);
        quotaManager.processEnded(userId);

        // 修正 currentProcessIndex（删除进程后索引可能偏移）
        int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
        if (activeIndex >= pool.size()) {
            currentProcessIndex.put(userId, Math.max(0, pool.size() - 1));
        } else if (activeIndex == index) {
            // 删除的是当前活跃进程，重置到 0
            currentProcessIndex.put(userId, 0);
        }

        // 如果池空了，移除
        if (pool.isEmpty()) {
            processPools.remove(userId);
            currentProcessIndex.remove(userId);
        }

        String type;
        if (target.parentId != null) {
            type = "子进程";
        } else {
            type = "独立进程";
        }
        return "已删除" + type + " (序号: " + (index + 1) + ")";
    }

    // ==================== 空闲进程清理 ====================

    private void cleanupIdleProcesses() {
        long now = System.currentTimeMillis();
        long timeout = claudeConfig.getProcessIdleTimeoutMs();

        // 收集需要移除的空 userId
        java.util.List<String> emptyPools = new ArrayList<>();

        processPools.forEach((userId, pool) -> {
            // 先清理空闲超时的 fork 子进程（有 parentId 且非独立进程）
            pool.removeIf(p -> {
                if (p.parentId != null && !p.isIndependent && !p.busy && (now - p.lastActiveTime) > timeout) {
                    log.info("Cleaning up idle fork child process for user: {}", userId);
                    p.destroy();
                    quotaManager.processEnded(userId);
                    return true;
                }
                return false;
            });

            // 清理空闲超时的非子进程（仅在没有子进程时）
            boolean hasAnyChildren = pool.stream().anyMatch(p -> p.parentId != null);
            if (!hasAnyChildren) {
                pool.removeIf(p -> {
                    if (p.parentId == null && !p.busy && (now - p.lastActiveTime) > timeout) {
                        log.info("Cleaning up idle {} process for user: {}", p.isIndependent ? "independent" : "parent", userId);
                        p.destroy();
                        quotaManager.processEnded(userId);
                        return true;
                    }
                    return false;
                });
            }

            // 数量超限时，清理最早的空闲子进程
            int maxProcesses = claudeConfig.getMaxProcessesPerUser();
            while (pool.size() > maxProcesses) {
                // 找到最早的空闲子进程
                ClaudeProcess oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (ClaudeProcess p : pool) {
                    if (p.parentId != null && !p.busy && p.lastActiveTime < oldestTime) {
                        oldest = p;
                        oldestTime = p.lastActiveTime;
                    }
                }
                if (oldest != null) {
                    log.info("Cleaning up excess fork child process for user: {}, pool size: {}", userId, pool.size());
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
        void onToolUse(String userId, String toolName, String toolInput, int processIndex);
        void onToolResult(String userId, String toolName, String toolUseId, String result, int processIndex);
        void onSubtaskStatus(String userId, String status, boolean isCompleted, int processIndex);
        void onDecisionMessage(String userId, String message);
    }

    private ToolCallback toolCallback;

    public void setToolCallback(ToolCallback callback) {
        this.toolCallback = callback;
    }

    private boolean isSubtaskTool(String toolName) {
        return "TaskCreate".equals(toolName) || "TaskUpdate".equals(toolName)
                || "TaskGet".equals(toolName) || "TaskList".equals(toolName);
    }

    private String extractSubtaskStatus(String toolName, String toolInput, String userId, int processIndex) {
        try {
            String queueKey = userId + ":" + processIndex;
            Queue<String> queue = subtaskQueues.computeIfAbsent(queueKey, k -> new LinkedList<>());
            com.fasterxml.jackson.databind.JsonNode inputNode = objectMapper.readTree(toolInput);
            return switch (toolName) {
                case "TaskCreate" -> {
                    String subject = inputNode.has("subject") ? inputNode.get("subject").asText() : "";
                    if (!subject.isEmpty()) {
                        queue.offer(subject);
                        log.debug("Subtask queue[{}] offer: {}", queueKey, subject);
                    }
                    yield "📋 创建子任务: " + subject;
                }
                case "TaskUpdate" -> {
                    String status = inputNode.has("status") ? inputNode.get("status").asText() : "更新";
                    // 从队列头部获取 subject（不立即移除，等 completed 才移除）
                    String subject = queue.peek();
                    if ("completed".equals(status)) {
                        queue.poll();
                        log.debug("Subtask queue[{}] poll: {}, remaining={}", queueKey, subject, queue.size());
                        yield "✅ 子任务完成" + (subject == null || subject.isEmpty() ? "" : ": " + subject);
                    }
                    yield "🔄 子任务 " + status + (subject == null || subject.isEmpty() ? "" : ": " + subject);
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
        // 清除该用户所有 session 的 token 用量
        List<String> sessions = sessionHistory.get(userId);
        if (sessions != null) {
            sessions.forEach(tokenUsageMap::remove);
            sessions.forEach(memoryDocuments::remove); // 清理记忆文档绑定
        }
        String currentSession = sessionIds.get(userId);
        if (currentSession != null) {
            tokenUsageMap.remove(currentSession);
            memoryDocuments.remove(currentSession); // 清理记忆文档绑定
        }
        sessionIds.remove(userId);
        sessionHistory.remove(userId);
        quotaManager.reset(userId);
        destroyProcessGroup(userId);
    }

    /**
     * 仅清除当前 session，保留其他 session
     */
    public void clearCurrentSession(String userId) {
        String currentSession = sessionIds.get(userId);
        if (currentSession != null) {
            tokenUsageMap.remove(currentSession);
            memoryDocuments.remove(currentSession);
            List<String> history = sessionHistory.get(userId);
            if (history != null) {
                history.remove(currentSession);
            }
        }
        sessionIds.remove(userId);
        quotaManager.reset(userId);

        // 只清除当前活跃进程组的 worker sessionId
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool != null) {
            String groupId = getActiveGroupId(userId);
            for (ClaudeProcess p : pool) {
                if (groupId != null && groupId.equals(p.groupId)) {
                    p.sessionId = null;
                    p.destroyCliProcess();
                }
            }
        }
    }

    /**
     * 清除所有 session，但保留进程
     */
    public void clearAllSessions(String userId) {
        List<String> sessions = sessionHistory.get(userId);
        if (sessions != null) {
            sessions.forEach(tokenUsageMap::remove);
            sessions.forEach(memoryDocuments::remove); // 清理记忆文档绑定
        }
        String currentSession = sessionIds.get(userId);
        if (currentSession != null) {
            tokenUsageMap.remove(currentSession);
            memoryDocuments.remove(currentSession); // 清理记忆文档绑定
        }
        sessionIds.remove(userId);
        sessionHistory.remove(userId);
        quotaManager.reset(userId);
    }

    public String getSessionId(String userId) {
        return sessionIds.get(userId);
    }

    /**
     * 清除用户的当前 sessionId（切换 API/Key/工作区后调用，避免使用旧 session）
     */
    public void clearSessionId(String userId) {
        sessionIds.remove(userId);
    }

    public List<String> getSessionHistory(String userId) {
        return sessionHistory.getOrDefault(userId, new ArrayList<>());
    }

    public boolean switchSession(String userId, String sessionId) {
        List<String> history = sessionHistory.get(userId);
        if (history != null && history.contains(sessionId)) {
            sessionIds.put(userId, sessionId);
            // 更新当前进程及其子进程的 sessionId
            List<ClaudeProcess> pool = processPools.get(userId);
            if (pool != null && !pool.isEmpty()) {
                int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
                if (activeIndex >= 0 && activeIndex < pool.size()) {
                    ClaudeProcess current = pool.get(activeIndex);
                    current.sessionId = sessionId;
                    current.destroyCliProcess(); // 销毁旧 CLI，下次用新 session 重建
                    syncSessionToChildren(userId, current.processId, sessionId);
                }
            }
            return true;
        }
        return false;
    }

    public boolean deleteSession(String userId, String sessionId) {
        List<String> history = sessionHistory.get(userId);
        if (history != null && history.remove(sessionId)) {
            if (sessionId.equals(sessionIds.get(userId))) {
                sessionIds.remove(userId);
                destroyProcessGroup(userId);
            }
            // 清理记忆文档绑定
            memoryDocuments.remove(sessionId);
            return true;
        }
        return false;
    }

    // ==================== 记忆文档管理 ====================

    /**
     * 保存上下文压缩记忆文档
     * @param sessionId 会话ID
     * @param workDir 工作目录
     * @param content 文档内容
     * @return 文件路径，失败返回 null
     */
    public String saveMemoryDocument(String sessionId, String workDir, String content) {
        try {
            Path memoryDir = Path.of(workDir, ".vibe-memory");
            if (!Files.exists(memoryDir)) {
                Files.createDirectories(memoryDir);
            }

            String fileName = "context-" + sessionId.substring(0, Math.min(12, sessionId.length())) + ".md";
            Path filePath = memoryDir.resolve(fileName);

            String documentContent = String.format("""
                    # 上下文压缩文档

                    **会话ID**: %s
                    **工作目录**: %s
                    **生成时间**: %s

                    ---

                    %s
                    """,
                    sessionId,
                    workDir,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    content
            );

            Files.write(filePath, documentContent.getBytes());
            memoryDocuments.put(sessionId, filePath.toString());
            log.info("Saved memory document for session {}: {}", sessionId, filePath);
            return filePath.toString();
        } catch (Exception e) {
            log.error("Failed to save memory document for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * 获取指定工作目录的最新记忆文档内容
     * @param workDir 工作目录
     * @return 文档内容，不存在返回 null
     */
    public String getLatestMemoryDocument(String workDir) {
        try {
            Path memoryDir = Path.of(workDir, ".vibe-memory");
            if (!Files.exists(memoryDir)) {
                return null;
            }

            // 查找最新的记忆文档
            Path latestFile = null;
            long latestTime = 0;

            try (var stream = Files.list(memoryDir)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (file.toString().endsWith(".md")) {
                        long lastModified = Files.getLastModifiedTime(file).toMillis();
                        if (lastModified > latestTime) {
                            latestTime = lastModified;
                            latestFile = file;
                        }
                    }
                }
            }

            if (latestFile != null) {
                String content = new String(Files.readAllBytes(latestFile));
                log.info("Loaded memory document from: {}", latestFile);
                return content;
            }
        } catch (Exception e) {
            log.error("Failed to load memory document from {}", workDir, e);
        }
        return null;
    }

    /**
     * 删除指定会话的记忆文档
     * @param sessionId 会话ID
     */
    public void deleteMemoryDocument(String sessionId) {
        String filePath = memoryDocuments.remove(sessionId);
        if (filePath != null) {
            try {
                Files.deleteIfExists(Path.of(filePath));
                log.info("Deleted memory document for session {}: {}", sessionId, filePath);
            } catch (Exception e) {
                log.error("Failed to delete memory document for session {}", sessionId, e);
            }
        }
    }

    /**
     * 删除指定工作目录下的所有记忆文档
     * @param workDir 工作目录
     */
    public void deleteAllMemoryDocuments(String workDir) {
        try {
            Path memoryDir = Path.of(workDir, ".vibe-memory");
            if (Files.exists(memoryDir)) {
                try (var stream = Files.list(memoryDir)) {
                    for (Path file : (Iterable<Path>) stream::iterator) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(memoryDir);
                log.info("Deleted all memory documents from {}", workDir);
            }
        } catch (Exception e) {
            log.error("Failed to delete memory documents from {}", workDir, e);
        }
        // 清理绑定关系
        memoryDocuments.entrySet().removeIf(entry -> entry.getValue().startsWith(workDir));
    }

    public TokenUsage getTokenUsage(String userId) {
        String sessionId = sessionIds.get(userId);
        if (sessionId != null) {
            return tokenUsageMap.getOrDefault(sessionId, new TokenUsage());
        }
        return new TokenUsage();
    }

    public String getTokenUsageSummary(String userId) {
        TokenUsage usage = getTokenUsage(userId);
        String sessionId = sessionIds.get(userId);
        String sessionLabel = sessionId != null ? " (Session: " + sessionId.substring(0, Math.min(12, sessionId.length())) + "...)" : "";
        return String.format("输入: %s, 输出: %s, 总计: %s%s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), formatTokens(usage.totalTokens), sessionLabel);
    }

    public String getTaskCompletionSummary(String userId, long durationMs, String responseContent) {
        TokenUsage usage = getTokenUsage(userId);
        String duration = formatDuration(durationMs);
        String sessionId = sessionIds.get(userId);
        String sessionInfo = sessionId != null ? "\n📋 Session: `" + sessionId + "`" : "";
        int contextWindow = parseContextWindowSize(claudeConfig.getModel());
        int contextPercent = contextWindow > 0 ? Math.min(100, (int) (usage.inputTokens * 100.0 / contextWindow)) : 0;
        String contextInfo = "\n🧠 上下文: " + contextPercent + "% (" + formatTokens(usage.inputTokens) + "/" + formatTokens(contextWindow) + ")";

        // 自动压缩检查：超过阈值时生成记忆文档并清除会话
        String compactionInfo = "";
        if (contextPercent >= claudeConfig.getContextCompactionThreshold() && sessionId != null) {
            // 保存记忆文档
            String workDir = claudeConfig.getWorkDir();
            if (workDir == null || workDir.isEmpty()) {
                workDir = System.getProperty("user.dir");
            }
            String memoryPath = saveMemoryDocument(sessionId, workDir, responseContent);
            String memoryNote = memoryPath != null ? "\n📁 记忆文档已保存: `.vibe-memory/`" : "";

            // 清除当前会话，下次消息将使用新会话（自动压缩）
            sessionIds.remove(userId);
            destroyProcessGroup(userId);
            compactionInfo = "\n\n🔄 **上下文已自动压缩**（使用量 " + contextPercent + "% 超过阈值 " + claudeConfig.getContextCompactionThreshold() + "%）" + memoryNote + "\n下次消息将自动读取记忆文档恢复上下文";
            log.info("Auto compaction triggered for user: {}, context: {}%, memory: {}", userId, contextPercent, memoryPath);
        }

        return String.format("---\n📊 本次: %s in / %s out | ⏱️ %s%s%s%s",
                formatTokens(usage.inputTokens), formatTokens(usage.outputTokens), duration, sessionInfo, contextInfo, compactionInfo);
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
            // 更新当前进程的 sessionId
            List<ClaudeProcess> pool = processPools.get(userId);
            if (pool != null && !pool.isEmpty()) {
                int activeIndex = currentProcessIndex.getOrDefault(userId, 0);
                if (activeIndex >= 0 && activeIndex < pool.size()) {
                    ClaudeProcess current = pool.get(activeIndex);
                    current.sessionId = sessionId;
                    current.destroyCliProcess();
                }
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
    public void setWorkDir(String dir) {
        claudeConfig.setWorkDir(dir);
    }

    /**
     * 切换当前用户当前进程组的工作目录
     */
    public void setCurrentGroupWorkDir(String userId, String dir) {
        claudeConfig.setWorkDir(dir);
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool != null) {
            String groupId = getActiveGroupId(userId);
            for (ClaudeProcess p : pool) {
                if (groupId != null && groupId.equals(p.groupId) && !p.busy) {
                    p.workDir = dir;
                    p.sessionId = null;
                    p.destroyCliProcess();
                }
            }
            // 清除当前用户的 sessionId
            sessionIds.remove(userId);
        }
    }

    public int getContextWindowSize() { return claudeConfig.getContextWindowSize(); }
    public void setContextWindowSize(int size) { claudeConfig.setContextWindowSize(size); }

    // 进程配置
    public int getMaxProcessesPerUser() { return claudeConfig.getMaxProcessesPerUser(); }
    public void setMaxProcessesPerUser(int max) { claudeConfig.setMaxProcessesPerUser(max); }
    public long getProcessIdleTimeoutMs() { return claudeConfig.getProcessIdleTimeoutMs(); }
    public void setProcessIdleTimeoutMs(long timeout) { claudeConfig.setProcessIdleTimeoutMs(timeout); }

    public String getThinkingLevel() { return thinkingConfig.getLevel(); }
    public void setThinkingLevel(String level) { thinkingConfig.setLevel(level); }
    public boolean isThinkingEnabled() { return thinkingConfig.isEnabled(); }
    public int getThinkingBudgetTokens() { return thinkingConfig.getCurrentBudgetTokens(); }
}
