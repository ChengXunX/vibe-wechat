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

    // 会话管理（tokenUsageMap 按 sessionId 统计，确保切换 session 后数据独立）
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

        // Fork 进程组支持
        boolean isParent = false;   // 是否为父进程
        String parentId = null;     // 父进程的 userId（子进程才有）

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

        // 1. 查找组内空闲 Worker（线程空闲，没有在执行任务）
        for (ClaudeProcess p : pool) {
            if (!p.busy) {
                // Worker 空闲，如果它没有活跃的 CLI 进程，为其创建一个
                if (!p.hasActiveCliProcess()) {
                    if (!attachCliProcess(p, userId)) {
                        return null; // 创建失败
                    }
                }
                return p;
            }
        }

        // 2. 检查进程数上限（克隆进程也计入）
        if (pool.size() >= claudeConfig.getMaxProcessesPerUser()) {
            return null; // 需要排队
        }

        // 3. 进程池为空时创建父进程 Worker
        if (pool.isEmpty()) {
            ClaudeProcess cp = createNewWorker(userId, pool, true);
            if (!attachCliProcess(cp, userId)) {
                pool.remove(cp);
                return null; // 创建失败
            }
            return cp;
        }

        // 4. 有 Worker 但全忙：排队等待空闲 Worker
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

            // 启动 stderr 读取线程（用于日志）
            Thread stderrThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = worker.stderr.readLine()) != null) {
                        log.info("Claude stderr for user {}: {}", userId, line);
                    }
                } catch (Exception e) {
                    log.debug("Claude stderr reader error for user {}: {}", userId, e.getMessage());
                }
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

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
        ClaudeProcess worker = new ClaudeProcess();
        worker.isParent = isParent;
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
     * Fork 子 Worker：共享父 Worker 的 session 和工作目录
     */
    private ClaudeProcess forkProcess(String userId, List<ClaudeProcess> pool) {
        // 找到父 Worker
        ClaudeProcess parent = null;
        for (ClaudeProcess p : pool) {
            if (p.isParent) {
                parent = p;
                break;
            }
        }

        if (parent == null) {
            log.warn("No parent worker found for user: {}, cannot fork", userId);
            return null;
        }

        // 创建子 Worker，共享父 Worker 的 session 和工作目录
        ClaudeProcess child = new ClaudeProcess();
        child.isParent = false;
        child.parentId = userId;
        child.sessionId = parent.sessionId;
        child.workDir = parent.workDir;
        child.thinkingLevel = parent.thinkingLevel;

        pool.add(child);
        log.info("Forked child worker for user: {}, pool size: {}", userId, pool.size());
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
                // processMessage 内部已调用 processEnded，这里不再重复
                processNextInQueue(userId);
            }
        });

        return future;
    }

    private String processMessage(ClaudeProcess cp, String userId, String message) {
        long startTime = System.currentTimeMillis();

        try {
            // 检查 CLI 进程是否还活着
            if (!cp.hasActiveCliProcess()) {
                log.error("Claude CLI process is not alive for user: {}", userId);
                return "Claude CLI 进程已死亡";
            }

            // 通过 stdin 发送消息（text 格式，纯文本）
            log.info("Sending message to Claude stdin for user {}: {}", userId, message.length() > 200 ? message.substring(0, 200) + "..." : message);
            cp.stdin.write((message + "\n").getBytes());
            cp.stdin.flush();
            // 关闭 stdin 通知 Claude 输入结束，开始处理
            cp.stdin.close();
            log.info("Message flushed and stdin closed for user: {}", userId);

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

                            // 父进程 session 变更时同步到子进程
                            if (cp.isParent) {
                                syncSessionToChildren(userId, sessionNode.asText());
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
                    }
                } catch (Exception e) {
                    // 非 JSON 行，可能是纯文本输出
                    output.append(line).append("\n");
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            String result = output.toString().trim();
            log.info("Claude process responded in {}ms for user: {}", duration, userId);

            // 关闭 stdin 后 CLI 进程会退出，销毁 CLI 进程但保留 Worker
            cp.destroyCliProcess();
            quotaManager.processEnded(userId);

            return result.isEmpty() ? "Claude 未返回结果" : result;

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

    /**
     * 父进程 session 变更时同步到子进程
     * 子进程在当前请求完成后，下次 fork 时会使用新 session
     */
    private void syncSessionToChildren(String userId, String newSessionId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return;

        for (ClaudeProcess p : pool) {
            if (!p.isParent && userId.equals(p.parentId)) {
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
        requestQueues.remove(userId);
    }

    /**
     * 销毁进程组（父进程 + 所有子进程）
     */
    public void destroyProcessGroup(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool != null) {
            // 先销毁所有子进程
            pool.removeIf(p -> !p.isParent && userId.equals(p.parentId));
            // 再销毁父进程
            pool.removeIf(p -> p.isParent);
            // 清空剩余进程
            pool.forEach(ClaudeProcess::destroy);
        }
        processPools.remove(userId);
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
        destroyProcessGroup(userId);
    }

    /**
     * 停止当前进程（第一个父进程）的任务
     */
    public String forceStopCurrentProcess(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null || pool.isEmpty()) {
            return "无活跃进程";
        }

        // 找到父 Worker
        ClaudeProcess parent = null;
        for (ClaudeProcess p : pool) {
            if (p.isParent) {
                parent = p;
                break;
            }
        }

        if (parent == null) {
            return "无活跃的父进程";
        }

        if (!parent.busy) {
            return "当前进程没有正在运行的任务";
        }

        parent.destroy();
        pool.remove(parent);
        quotaManager.processEnded(userId);

        return "已停止当前父进程的任务";
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

        String type = target.isParent ? "父进程" : "子进程";
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

    public int getForkChildCount(String userId) {
        List<ClaudeProcess> pool = processPools.get(userId);
        if (pool == null) return 0;
        int count = 0;
        for (ClaudeProcess p : pool) {
            if (!p.isParent && userId.equals(p.parentId)) {
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
            String type = p.isParent ? "父进程" : "子进程";
            String source = p.isParent ? "-" : "克隆自父进程";
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

        if (target.busy) {
            return "进程" + (index + 1) + "正在忙碌中，无法切换";
        }

        // 切换会话
        if (target.sessionId != null) {
            sessionIds.put(userId, target.sessionId);
            syncSessionToChildren(userId, target.sessionId);
        }

        // 切换工作目录
        if (target.workDir != null) {
            claudeConfig.setWorkDir(target.workDir);
            System.setProperty("user.dir", target.workDir);
        }

        String type = target.isParent ? "父进程" : "子进程";
        return String.format("已切换到进程%d (%s)\n\n📋 会话ID: `%s`\n📁 工作目录: `%s`",
                index + 1,
                type,
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

        // 找到父 Worker（克隆操作始终在父 Worker 下进行）
        ClaudeProcess parent = null;
        for (ClaudeProcess p : pool) {
            if (p.isParent) {
                parent = p;
                break;
            }
        }

        if (parent == null) {
            return "无活跃的父进程可克隆";
        }

        // 克隆进程（使用父进程的 session 和工作目录）
        ClaudeProcess cp = forkProcess(userId, pool);
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

        // 创建新独立 Worker（不是克隆）
        ClaudeProcess cp = createNewWorker(userId, pool, true);
        if (cp == null) {
            return "创建新进程失败";
        }

        // 尝试处理排队的消息
        processNextInQueue(userId);

        return String.format("✅ 已创建新进程\n\n📋 工作目录: `%s`",
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
        if (target.isParent) {
            // 父进程：检查是否有子进程
            for (ClaudeProcess p : pool) {
                if (!p.isParent && userId.equals(p.parentId)) {
                    return "该父进程有子进程，无法删除\n请先使用 v-delproc 删除子进程";
                }
            }
        }

        // 检查进程是否忙碌
        if (target.busy) {
            return "进程" + (index + 1) + "正在忙碌中，无法删除";
        }

        // 销毁进程
        target.destroy();
        pool.remove(target);
        quotaManager.processEnded(userId);

        // 如果池空了，移除
        if (pool.isEmpty()) {
            processPools.remove(userId);
        }

        String type = target.isParent ? "父进程" : "子进程";
        return "已删除" + type + " (序号: " + (index + 1) + ")";
    }

    // ==================== 空闲进程清理 ====================

    private void cleanupIdleProcesses() {
        long now = System.currentTimeMillis();
        long timeout = claudeConfig.getProcessIdleTimeoutMs();

        // 收集需要移除的空 userId
        java.util.List<String> emptyPools = new ArrayList<>();

        processPools.forEach((userId, pool) -> {
            // 先清理空闲超时的子进程（fork child）
            pool.removeIf(p -> {
                if (!p.isParent && !p.busy && (now - p.lastActiveTime) > timeout) {
                    log.info("Cleaning up idle fork child process for user: {}", userId);
                    p.destroy();
                    quotaManager.processEnded(userId);
                    return true;
                }
                return false;
            });

            // 清理空闲超时的父进程（仅在没有子进程时）
            boolean hasChildren = pool.stream().anyMatch(p -> !p.isParent && userId.equals(p.parentId));
            if (!hasChildren) {
                pool.removeIf(p -> {
                    if (p.isParent && !p.busy && (now - p.lastActiveTime) > timeout) {
                        log.info("Cleaning up idle parent process for user: {}", userId);
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
                    if (!p.isParent && !p.busy && p.lastActiveTime < oldestTime) {
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
        void onToolUse(String userId, String toolName, String toolInput);
        void onToolResult(String userId, String result);
        void onSubtaskStatus(String userId, String status, boolean isCompleted);
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
        // 清除该用户所有 session 的 token 用量
        List<String> sessions = sessionHistory.get(userId);
        if (sessions != null) {
            sessions.forEach(tokenUsageMap::remove);
        }
        String currentSession = sessionIds.get(userId);
        if (currentSession != null) {
            tokenUsageMap.remove(currentSession);
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
            List<String> history = sessionHistory.get(userId);
            if (history != null) {
                history.remove(currentSession);
            }
        }
        sessionIds.remove(userId);
        quotaManager.reset(userId);
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
            // 同步到子进程
            syncSessionToChildren(userId, sessionId);
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
            return true;
        }
        return false;
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

    public String getTaskCompletionSummary(String userId, long durationMs) {
        TokenUsage usage = getTokenUsage(userId);
        String duration = formatDuration(durationMs);
        String sessionId = sessionIds.get(userId);
        String sessionInfo = sessionId != null ? "\n📋 Session: `" + sessionId + "`" : "";
        int contextWindow = parseContextWindowSize(claudeConfig.getModel());
        int contextPercent = contextWindow > 0 ? Math.min(100, (int) (usage.inputTokens * 100.0 / contextWindow)) : 0;
        String contextInfo = "\n🧠 上下文: " + contextPercent + "% (" + formatTokens(usage.inputTokens) + "/" + formatTokens(contextWindow) + ")";

        // 自动压缩检查：超过阈值时清除会话以压缩上下文
        String compactionInfo = "";
        if (contextPercent >= claudeConfig.getContextCompactionThreshold() && sessionId != null) {
            // 清除当前会话，下次消息将使用新会话（自动压缩）
            sessionIds.remove(userId);
            destroyProcessGroup(userId);
            compactionInfo = "\n\n🔄 **上下文已自动压缩**（使用量 " + contextPercent + "% 超过阈值 " + claudeConfig.getContextCompactionThreshold() + "%）\n下次消息将使用新会话";
            log.info("Auto compaction triggered for user: {}, context: {}%", userId, contextPercent);
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

    public String getThinkingLevel() { return thinkingConfig.getLevel(); }
    public void setThinkingLevel(String level) { thinkingConfig.setLevel(level); }
    public boolean isThinkingEnabled() { return thinkingConfig.isEnabled(); }
    public int getThinkingBudgetTokens() { return thinkingConfig.getCurrentBudgetTokens(); }
}
