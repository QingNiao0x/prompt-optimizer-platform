package com.promptoptimizer.context.service;

import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.common.logging.LogFields;
import com.promptoptimizer.context.dto.DocumentUploadCreateRequest;
import com.promptoptimizer.context.domain.DocumentProcessingPhase;
import com.promptoptimizer.context.domain.DocumentSelection;
import com.promptoptimizer.context.domain.DocumentUploadStatus;
import com.promptoptimizer.identity.service.CurrentActor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 接收大型文档分片，在临时目录完成异步解析和全文分块索引，并按任务检索相关片段。
 */
@Service
public class TemporaryDocumentIndexService implements DocumentIndexLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(TemporaryDocumentIndexService.class);

    public static final int UPLOAD_CHUNK_BYTES = 1024 * 1024;
    public static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;

    private static final long MAX_ACTIVE_SOURCE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ACTIVE_DOCUMENTS = 100;
    private static final int INDEX_CHUNK_CHARACTERS = 6_000;
    private static final int INDEX_CHUNK_OVERLAP = 400;
    private static final Duration DOCUMENT_TTL = Duration.ofHours(2);
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");
    private static final Pattern TRAVERSAL_PATH = Pattern.compile("(^|/)\\.\\.($|/)");
    private static final Pattern PRODUCTION_CONFIG_PATH = Pattern.compile(
            "(?i)(?:^|/)(?:application[-.](?:prod|production)|config/(?:prod|production))"
                    + "\\.(?:yml|yaml|properties|json|toml)$"
    );
    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".env", ".env.local", ".env.development", ".env.production",
            "id_rsa", "id_ed25519", "credentials", "credentials.json"
    );
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "text", "txt", "markdown", "restructuredtext", "latex", "asciidoc", "csv", "tsv",
            "doc", "docx", "wps", "odt", "ppt", "pptx", "dps", "odp",
            "xls", "xlsx", "et", "ods", "pdf", "jpeg", "jpg", "png", "gif", "webp", "bmp"
    );

    private final StreamingDocumentExtractor documentExtractor;
    private final SemanticVectorIndex semanticVectorIndex;
    private final MapReduceDocumentSummarizer documentSummarizer;
    private final CurrentActor currentActor;
    private final ContentChunkSelector contentChunkSelector = new ContentChunkSelector();
    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Path rootDirectory;

    public TemporaryDocumentIndexService(
            StreamingDocumentExtractor documentExtractor,
            SemanticVectorIndex semanticVectorIndex,
            MapReduceDocumentSummarizer documentSummarizer,
            CurrentActor currentActor
    ) {
        this.documentExtractor = documentExtractor;
        this.semanticVectorIndex = semanticVectorIndex;
        this.documentSummarizer = documentSummarizer;
        this.currentActor = currentActor;
        this.executor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
                Thread.ofPlatform().name("document-index-", 0).factory()
        );
        try {
            this.rootDirectory = Files.createTempDirectory("prompt-optimizer-documents-")
                    .toAbsolutePath()
                    .normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建大型文档临时目录", exception);
        }
    }

    /**
     * 创建上传会话。这里只保存文件元数据，不接收浏览器真实绝对路径。
     */
    public DocumentUploadStatus create(DocumentUploadCreateRequest request) {
        UUID ownerUserId = currentActor.require().userId();
        cleanupExpired();
        String path = normalizeAndValidatePath(request.path());
        String language = normalizeLanguage(request.language());
        if (request.sizeBytes() > MAX_DOCUMENT_BYTES) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.PAYLOAD_TOO_LARGE,
                    "单个文档不能超过 50 MB"
            );
        }
        if (sessions.size() >= MAX_ACTIVE_DOCUMENTS || activeSourceBytes() + request.sizeBytes() > MAX_ACTIVE_SOURCE_BYTES) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.CAPACITY_EXCEEDED,
                    "当前正在处理的文档较多，请稍后重试"
            );
        }

        String documentId = UUID.randomUUID().toString();
        Path directory = rootDirectory.resolve(documentId).normalize();
        assertInsideRoot(directory);
        try {
            Files.createDirectory(directory);
            Path sourceFile = directory.resolve("source.upload");
            Files.createFile(sourceFile);
            UploadSession session = new UploadSession(
                    documentId,
                    ownerUserId,
                    path,
                    language,
                    request.sizeBytes(),
                    directory,
                    sourceFile,
                    directory.resolve("chunks.data"),
                    directory.resolve("vectors.data"),
                    Instant.now().plus(DOCUMENT_TTL)
            );
            sessions.put(documentId, session);
            return statusOf(session);
        } catch (IOException exception) {
            deleteDirectoryQuietly(directory);
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.CONFLICT,
                    "无法创建文档上传任务",
                    exception
            );
        }
    }

    /**
     * 写入一个固定大小分片。相同编号可安全重试，后一次内容会覆盖同一文件区间。
     */
    public DocumentUploadStatus appendChunk(String documentId, int chunkIndex, byte[] content) {
        UploadSession session = requireSession(documentId);
        synchronized (session.monitor) {
            assertPhase(session, DocumentProcessingPhase.UPLOADING);
            if (chunkIndex < 0 || chunkIndex >= session.expectedChunks) {
                throw invalid("分片编号超出范围");
            }
            int expectedLength = expectedChunkLength(session, chunkIndex);
            if (content == null || content.length != expectedLength) {
                throw invalid("分片大小不正确，期望 " + expectedLength + " 字节");
            }
            long offset = (long) chunkIndex * UPLOAD_CHUNK_BYTES;
            try (FileChannel channel = FileChannel.open(
                    session.sourceFile,
                    StandardOpenOption.WRITE
            )) {
                channel.position(offset);
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            } catch (IOException exception) {
                throw new DocumentUploadException(
                        DocumentUploadException.Reason.CONFLICT,
                        "文档分片写入失败，请重试当前分片",
                        exception
                );
            }
            if (!session.receivedChunks.get(chunkIndex)) {
                session.receivedChunks.set(chunkIndex);
                session.uploadedBytes += content.length;
            }
            session.expiresAt = Instant.now().plus(DOCUMENT_TTL);
            session.progressPercent = uploadPercent(session);
            return statusOf(session);
        }
    }

    /**
     * 校验所有分片并启动受限线程池中的解析任务。
     */
    public DocumentUploadStatus completeUpload(String documentId) {
        UploadSession session = requireSession(documentId);
        synchronized (session.monitor) {
            assertPhase(session, DocumentProcessingPhase.UPLOADING);
            if (session.receivedChunks.cardinality() != session.expectedChunks
                    || session.uploadedBytes != session.fileSizeBytes) {
                throw new DocumentUploadException(
                        DocumentUploadException.Reason.CONFLICT,
                        "文件分片尚未上传完整"
                );
            }
            session.phase = DocumentProcessingPhase.QUEUED;
            session.progressPercent = 41;
            session.expiresAt = Instant.now().plus(DOCUMENT_TTL);
            session.requestId = MDC.get("requestId");
            session.task = executor.submit(() -> process(session));
            return statusOf(session);
        }
    }

    public DocumentUploadStatus getStatus(String documentId) {
        UploadSession session = requireSession(documentId);
        session.expiresAt = Instant.now().plus(DOCUMENT_TTL);
        return statusOf(session);
    }

    /**
     * 删除用户不再使用的临时文档。处理中任务先取消，再清理临时文件。
     */
    public void delete(String documentId) {
        requireSession(documentId);
        deleteSession(documentId);
    }

    /** 仅供已授权删除和过期清理使用，不从请求参数推断所有者。 */
    private void deleteSession(String documentId) {
        UploadSession session = sessions.remove(documentId);
        if (session == null) {
            return;
        }
        synchronized (session.monitor) {
            session.cancelled = true;
            session.phase = DocumentProcessingPhase.CANCELLED;
            if (session.task != null) {
                session.task.cancel(true);
            }
        }
        deleteDirectoryQuietly(session.directory);
    }

    /**
     * 从全文索引选取与提示词相关的片段，并同时保留均匀分布的代表片段。
     */
    @Override
    public Optional<DocumentSelection> retrieve(
            String documentId,
            String query,
            int maxCharacters,
            int maxChunks
    ) {
        UUID ownerUserId = currentActor.require().userId();
        if (documentId == null || documentId.isBlank()) {
            return Optional.empty();
        }
        UploadSession session = sessions.get(documentId);
        if (session == null || !session.ownerUserId.equals(ownerUserId)) {
            return Optional.empty();
        }

        // 向量查询可能访问外部或本地模型，不能占用会话锁，否则状态查询和取消操作会被阻塞。
        SemanticVectorIndex.SearchResult semanticResult = semanticVectorIndex.search(
                session.vectorFile,
                query,
                Math.max(64, maxChunks * 8)
        );
        synchronized (session.monitor) {
            if (sessions.get(documentId) != session
                    || (session.phase != DocumentProcessingPhase.READY
                    && session.phase != DocumentProcessingPhase.PARTIAL)) {
                return Optional.empty();
            }
            session.expiresAt = Instant.now().plus(DOCUMENT_TTL);
            List<ChunkRecord> selectedRecords = selectRecords(
                    session,
                    query,
                    maxChunks,
                    semanticResult.scores()
            );
            StringBuilder content = new StringBuilder();
            int selectedCharacters = 0;
            int selectedChunks = 0;
            for (ChunkRecord record : selectedRecords) {
                int remaining = maxCharacters - selectedCharacters;
                if (remaining <= 0) {
                    break;
                }
                String chunk = readChunk(session, record);
                String value = chunk.length() > remaining ? chunk.substring(0, remaining) : chunk;
                if (!content.isEmpty()) {
                    content.append("\n\n");
                }
                content.append("[文档片段 ")
                        .append(record.index() + 1)
                        .append('/')
                        .append(session.chunks.size())
                        .append(" · ")
                        .append(record.label())
                        .append("]\n")
                        .append(value);
                selectedCharacters += value.length();
                selectedChunks++;
            }
            List<String> selectionWarnings = new ArrayList<>(session.warnings);
            addWarning(selectionWarnings, semanticResult.warning());
            return Optional.of(new DocumentSelection(
                    session.path,
                    session.language,
                    content.toString(),
                    session.summary,
                    session.fileSizeBytes,
                    session.extractedCharacters,
                    session.chunks.size(),
                    selectedChunks,
                    selectedCharacters,
                    session.extractionComplete,
                    selectionWarnings
            ));
        }
    }

    /**
     * 在后台依次提取全文、建立向量索引并生成摘要；耗时模型调用不持有会话锁，
     * 使进度查询和取消请求仍能及时响应。
     */
    private void process(UploadSession session) {
        long startedAt = System.nanoTime();
        try (LogCorrelation.Scope requestScope = LogCorrelation.bindRequestId(session.requestId);
             LogCorrelation.Scope workflowScope = LogCorrelation.bindWorkflow("document-upload", session.documentId)) {
            LOGGER.info("event=document.index.started requestId={} workflowId={} fileBytes={} expectedChunks={}",
                    LogFields.value(MDC.get("requestId")),
                    LogFields.value(MDC.get("workflowId")),
                    session.fileSizeBytes,
                    session.expectedChunks);
            processDocument(session, startedAt);
        }
    }

    /** 在异步工作线程执行解析、向量化和摘要，并仅输出聚合统计，不记录文件路径或正文。 */
    private void processDocument(UploadSession session, long startedAt) {
        try {
            synchronized (session.monitor) {
                if (session.cancelled) {
                    logCancelled(session, startedAt);
                    return;
                }
                session.phase = DocumentProcessingPhase.EXTRACTING;
                session.progressPercent = 45;
            }
            StreamingDocumentExtractor.ExtractionReport report;
            try (DocumentChunkWriter writer = new DocumentChunkWriter(session)) {
                report = documentExtractor.extract(
                        session.sourceFile,
                        session.path,
                        session.language,
                        writer::append,
                        (processed, total) -> updateExtractionProgress(session, processed, total)
                );
                writer.finish();
            }
            synchronized (session.monitor) {
                session.phase = DocumentProcessingPhase.INDEXING;
                session.progressPercent = 88;
                session.extractedCharacters = report.extractedCharacters();
                session.extractionComplete = report.complete();
                session.warnings = new ArrayList<>(report.warnings());
            }

            SemanticVectorIndex.BuildReport semanticReport = semanticVectorIndex.build(
                    session.vectorFile,
                    session.chunks.size(),
                    (offset, limit) -> readChunkBatch(session, offset, limit),
                    indexedChunks -> updateSemanticIndexProgress(session, indexedChunks)
            );
            synchronized (session.monitor) {
                if (session.cancelled || Thread.currentThread().isInterrupted()) {
                    logCancelled(session, startedAt);
                    return;
                }
                addWarning(session.warnings, semanticReport.warning());
                session.phase = DocumentProcessingPhase.SUMMARIZING;
                session.progressPercent = 95;
            }

            // 模型摘要可能耗时较长，必须在会话锁外执行，确保状态轮询和取消请求不被阻塞。
            MapReduceDocumentSummarizer.SummaryReport summaryReport = documentSummarizer.summarize(
                    new MapReduceDocumentSummarizer.SummarySource(
                            session.path,
                            session.language,
                            session.chunks.size(),
                            (offset, limit) -> readSummaryChunkBatch(session, offset, limit)
                    ),
                    completionRatio -> updateSummaryProgress(session, completionRatio)
            );
            synchronized (session.monitor) {
                if (session.cancelled || Thread.currentThread().isInterrupted()) {
                    logCancelled(session, startedAt);
                    return;
                }
                summaryReport.warnings().forEach(warning -> addWarning(session.warnings, warning));
                session.summary = summaryReport.summary();
                session.phase = report.complete()
                        ? DocumentProcessingPhase.READY
                        : DocumentProcessingPhase.PARTIAL;
                session.progressPercent = 100;
                session.expiresAt = Instant.now().plus(DOCUMENT_TTL);
            }
            LOGGER.info("event=document.index.completed requestId={} workflowId={} fileBytes={} "
                            + "extractedCharacters={} chunks={} summaryModelCalls={} summaryModelAssisted={} "
                            + "summaryWarnings={} durationMs={}",
                    LogFields.value(MDC.get("requestId")),
                    LogFields.value(MDC.get("workflowId")),
                    session.fileSizeBytes,
                    report.extractedCharacters(),
                    session.chunks.size(),
                    summaryReport.modelCalls(),
                    summaryReport.modelAssisted(),
                    summaryReport.warnings().size(),
                    elapsedMillis(startedAt));
        } catch (Exception exception) {
            synchronized (session.monitor) {
                if (!session.cancelled) {
                    session.phase = DocumentProcessingPhase.FAILED;
                    session.errorMessage = safeErrorMessage(exception);
                    session.progressPercent = 100;
                }
            }
            if (session.cancelled || Thread.currentThread().isInterrupted()) {
                logCancelled(session, startedAt);
            } else {
                LOGGER.error("event=document.index.failed requestId={} workflowId={} fileBytes={} "
                                + "chunks={} failureType={} durationMs={}",
                        LogFields.value(MDC.get("requestId")),
                        LogFields.value(MDC.get("workflowId")),
                        session.fileSizeBytes,
                        session.chunks.size(),
                        LogFields.value(exception.getClass().getSimpleName()),
                        elapsedMillis(startedAt));
            }
            deleteFileQuietly(session.chunkFile);
            deleteFileQuietly(session.vectorFile);
        } finally {
            deleteFileQuietly(session.sourceFile);
        }
    }

    /** 将异步任务经过的单调时钟时长转换为毫秒。 */
    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /** 统一记录文档索引取消结果，且不输出用户文件名、路径或内容。 */
    private void logCancelled(UploadSession session, long startedAt) {
        LOGGER.info("event=document.index.cancelled requestId={} workflowId={} fileBytes={} chunks={} durationMs={}",
                LogFields.value(MDC.get("requestId")),
                LogFields.value(MDC.get("workflowId")),
                session.fileSizeBytes,
                session.chunks.size(),
                elapsedMillis(startedAt));
    }

    private void updateExtractionProgress(UploadSession session, long processed, long total) {
        synchronized (session.monitor) {
            if (session.cancelled || Thread.currentThread().isInterrupted()) {
                throw new DocumentUploadException(
                        DocumentUploadException.Reason.CONFLICT,
                        "文档处理已取消"
                );
            }
            double ratio = total <= 0 ? 0 : Math.min(1D, (double) processed / total);
            session.progressPercent = 45 + (int) Math.round(ratio * 40);
        }
    }

    /** 把向量索引已处理块数映射到索引阶段进度，不倒退已展示的百分比。 */
    private void updateSemanticIndexProgress(UploadSession session, int indexedChunks) {
        synchronized (session.monitor) {
            if (session.cancelled || Thread.currentThread().isInterrupted()) {
                throw new DocumentUploadException(
                        DocumentUploadException.Reason.CONFLICT,
                        "文档处理已取消"
                );
            }
            double ratio = session.chunks.isEmpty()
                    ? 1D
                    : Math.min(1D, (double) indexedChunks / session.chunks.size());
            session.progressPercent = 88 + (int) Math.round(ratio * 6);
        }
    }

    /** 将摘要进度映射到最后阶段，并保持进度比例在有效范围内。 */
    private void updateSummaryProgress(UploadSession session, double completionRatio) {
        synchronized (session.monitor) {
            if (session.cancelled || Thread.currentThread().isInterrupted()) {
                throw new DocumentUploadException(
                        DocumentUploadException.Reason.CONFLICT,
                        "文档处理已取消"
                );
            }
            double boundedRatio = Math.max(0D, Math.min(1D, completionRatio));
            session.progressPercent = 95 + (int) Math.round(boundedRatio * 4D);
        }
    }

    /**
     * 混合代表性片段、关键词与语义得分选取上下文；不足时均匀补位，避免只覆盖文档开头。
     */
    private List<ChunkRecord> selectRecords(
            UploadSession session,
            String query,
            int maxChunks,
            Map<Integer, Double> semanticScores
    ) {
        int limit = Math.max(1, Math.min(maxChunks, session.chunks.size()));
        Set<String> queryTerms = contentChunkSelector.extractSearchTerms(query);
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        // 代表片段只占约三分之一名额，给关键词和语义结果保留足够空间。
        // 最终仍会均匀补位，因此泛化分析不会丢失文档中部和结尾。
        int reservedRepresentatives = Math.min(3, Math.max(1, (limit + 2) / 3));
        representativeIndexes(session.chunks.size(), reservedRepresentatives).forEach(selected::add);
        if (!queryTerms.isEmpty() || !semanticScores.isEmpty()) {
            Map<Integer, Integer> lexicalScores = new LinkedHashMap<>();
            int maxLexicalScore = 0;
            // 每块只保存偏移；检索时顺序读取完整块，避免前 40 个索引词遮蔽片段后部规则。
            // 单次读取约 6,000 字符，不把整份文档或全部词表加载到内存。
            try (FileChannel channel = FileChannel.open(session.chunkFile, StandardOpenOption.READ)) {
                for (ChunkRecord record : session.chunks) {
                    int lexicalScore = queryTerms.isEmpty() ? 0
                            : score(record, readChunk(channel, record), queryTerms);
                    lexicalScores.put(record.index(), lexicalScore);
                    maxLexicalScore = Math.max(maxLexicalScore, lexicalScore);
                }
            } catch (IOException exception) {
                throw new DocumentUploadException(DocumentUploadException.Reason.CONFLICT,
                        "临时文档索引读取失败，请重新上传文件", exception);
            }
            int highestLexicalScore = maxLexicalScore;
            session.chunks.stream()
                    .map(record -> new ScoredRecord(
                            record,
                            lexicalScores.getOrDefault(record.index(), 0),
                            semanticScores.getOrDefault(record.index(), 0D),
                            hybridScore(
                                    lexicalScores.getOrDefault(record.index(), 0),
                                    highestLexicalScore,
                                    semanticScores.getOrDefault(record.index(), 0D),
                                    !semanticScores.isEmpty()
                            )
                    ))
                    .filter(item -> item.lexicalScore() > 0 || item.semanticScore() > 0D)
                    .sorted(Comparator.comparingDouble(ScoredRecord::combinedScore).reversed()
                            .thenComparing(Comparator.comparingInt(ScoredRecord::lexicalScore).reversed())
                            .thenComparingInt(item -> item.record().index()))
                    .forEach(item -> {
                        if (selected.size() < limit) {
                            selected.add(item.record().index());
                        }
                    });
        }
        // 泛化任务可能与正文没有字面关键词，剩余名额使用均匀样本覆盖全文。
        representativeIndexes(session.chunks.size(), limit).forEach(selected::add);
        for (int index = 0; selected.size() < limit && index < session.chunks.size(); index++) {
            selected.add(index);
        }
        return selected.stream()
                .limit(limit)
                .sorted()
                .map(index -> session.chunks.get(index))
                .toList();
    }

    /** 合并精确关键词与语义相似度，优先保留代码符号和字段名等字面命中。 */
    private double hybridScore(
            int lexicalScore,
            int maxLexicalScore,
            double semanticScore,
            boolean semanticAvailable
    ) {
        if (!semanticAvailable) {
            return lexicalScore;
        }
        double normalizedLexical = maxLexicalScore <= 0
                ? 0D
                : (double) lexicalScore / maxLexicalScore;
        // 字面命中更适合类名、字段名和编号，语义相似度更适合自然语言改写。
        // 55/45 的权重优先保证精确命中，同时允许无共同词的相关段落进入结果。
        return normalizedLexical * 0.55D + semanticScore * 0.45D;
    }

    /** 匹配完整块正文和段落标签，不使用截断的文档词表。 */
    private int score(ChunkRecord record, String content, Set<String> queryTerms) {
        int score = contentChunkSelector.score(content, queryTerms);
        for (String term : queryTerms) {
            if (record.label().toLowerCase(Locale.ROOT).contains(term)) {
                score += 8;
            }
        }
        return score;
    }

    private LinkedHashSet<Integer> representativeIndexes(int chunkCount, int limit) {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        int count = Math.min(limit, chunkCount);
        if (count == 1) {
            indexes.add(0);
            return indexes;
        }
        for (int position = 0; position < count; position++) {
            indexes.add(Math.round((float) position * (chunkCount - 1) / (count - 1)));
        }
        return indexes;
    }

    private String readChunk(UploadSession session, ChunkRecord record) {
        try (FileChannel channel = FileChannel.open(session.chunkFile, StandardOpenOption.READ)) {
            return readChunk(channel, record);
        } catch (IOException exception) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.CONFLICT,
                    "临时文档索引读取失败，请重新上传文件",
                    exception
            );
        }
    }

    /** 按索引偏移分批读取全文片段，为向量构建提供有界输入。 */
    private List<String> readChunkBatch(UploadSession session, int offset, int limit) {
        int end = Math.min(session.chunks.size(), offset + limit);
        if (offset < 0 || offset >= end) {
            return List.of();
        }
        try (FileChannel channel = FileChannel.open(session.chunkFile, StandardOpenOption.READ)) {
            List<String> content = new ArrayList<>(end - offset);
            for (int index = offset; index < end; index++) {
                content.add(readChunk(channel, session.chunks.get(index)));
            }
            return content;
        } catch (IOException exception) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.CONFLICT,
                    "临时文档索引读取失败，请重新上传文件",
                    exception
            );
        }
    }

    /** 为分层摘要读取带原始顺序与标签的片段，不把整个文档载入内存。 */
    private List<MapReduceDocumentSummarizer.SourceChunk> readSummaryChunkBatch(
            UploadSession session,
            int offset,
            int limit
    ) {
        int end = Math.min(session.chunks.size(), offset + limit);
        if (offset < 0 || offset >= end) {
            return List.of();
        }
        try (FileChannel channel = FileChannel.open(session.chunkFile, StandardOpenOption.READ)) {
            List<MapReduceDocumentSummarizer.SourceChunk> content = new ArrayList<>(end - offset);
            for (int index = offset; index < end; index++) {
                ChunkRecord record = session.chunks.get(index);
                content.add(new MapReduceDocumentSummarizer.SourceChunk(
                        record.index(),
                        record.label(),
                        readChunk(channel, record)
                ));
            }
            return content;
        } catch (IOException exception) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.CONFLICT,
                    "临时文档索引读取失败，请重新上传文件",
                    exception
            );
        }
    }

    private String readChunk(FileChannel channel, ChunkRecord record) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(record.byteLength());
        channel.position(record.byteOffset());
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // FileChannel 可能分多次返回，持续读取到当前块结束。
        }
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }

    private void addWarning(List<String> warnings, String warning) {
        if (warning != null && !warning.isBlank() && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    /** 从会话当前状态构造可轮询进度快照，避免暴露内部文件句柄。 */
    private DocumentUploadStatus statusOf(UploadSession session) {
        synchronized (session.monitor) {
            return new DocumentUploadStatus(
                    session.documentId,
                    session.path,
                    session.language,
                    session.phase,
                    session.fileSizeBytes,
                    session.uploadedBytes,
                    session.progressPercent,
                    session.extractedCharacters,
                    session.chunks.size(),
                    session.summary,
                    session.warnings,
                    session.errorMessage,
                    session.expiresAt,
                    UPLOAD_CHUNK_BYTES
            );
        }
    }

    /** 查找上传会话前先清理过期任务，避免继续操作已失效的临时数据。 */
    private UploadSession requireSession(String documentId) {
        UUID ownerUserId = currentActor.require().userId();
        cleanupExpired();
        UploadSession session = sessions.get(documentId);
        if (session == null || !session.ownerUserId.equals(ownerUserId)) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.NOT_FOUND,
                    "文档上传任务不存在或已过期"
            );
        }
        return session;
    }

    private void assertPhase(UploadSession session, DocumentProcessingPhase expected) {
        if (session.phase != expected) {
            throw new DocumentUploadException(
                    DocumentUploadException.Reason.CONFLICT,
                    "当前文档状态不允许执行该操作：" + session.phase
            );
        }
    }

    private int expectedChunkLength(UploadSession session, int chunkIndex) {
        long offset = (long) chunkIndex * UPLOAD_CHUNK_BYTES;
        return Math.toIntExact(Math.min(UPLOAD_CHUNK_BYTES, session.fileSizeBytes - offset));
    }

    private int uploadPercent(UploadSession session) {
        return Math.min(40, (int) Math.round((double) session.uploadedBytes / session.fileSizeBytes * 40));
    }

    private long activeSourceBytes() {
        return sessions.values().stream()
                .filter(session -> session.phase == DocumentProcessingPhase.UPLOADING
                        || session.phase == DocumentProcessingPhase.QUEUED
                        || session.phase == DocumentProcessingPhase.EXTRACTING)
                .mapToLong(session -> session.fileSizeBytes)
                .sum();
    }

    /** 只接受非敏感相对路径，拒绝绝对路径、目录穿越与密钥文件进入临时索引。 */
    private String normalizeAndValidatePath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (path.isBlank() || path.startsWith("/") || WINDOWS_ABSOLUTE_PATH.matcher(path).matches()
                || path.indexOf(':') >= 0 || path.chars().anyMatch(character -> character < 32)
                || TRAVERSAL_PATH.matcher(path).find()) {
            throw invalid("只能上传相对路径，不能包含目录穿越");
        }
        if (SENSITIVE_FILE_NAMES.contains(fileName)
                || (fileName.startsWith(".env.") && !".env.example".equals(fileName))
                || fileName.endsWith(".pem") || fileName.endsWith(".key")
                || fileName.endsWith(".p12") || fileName.endsWith(".jks")
                || PRODUCTION_CONFIG_PATH.matcher(path).find()) {
            throw invalid("受保护文件不能进入文档索引");
        }
        return path;
    }

    private String normalizeLanguage(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw invalid("暂不支持该大型文档类型：" + normalized);
        }
        return normalized;
    }

    /** 确认临时文件仍位于服务管理的索引根目录内。 */
    private void assertInsideRoot(Path path) {
        if (!path.startsWith(rootDirectory)) {
            throw invalid("临时文档路径无效");
        }
    }

    private DocumentUploadException invalid(String message) {
        return new DocumentUploadException(DocumentUploadException.Reason.INVALID_ARGUMENT, message);
    }

    private String safeErrorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "文档解析失败，请检查文件是否损坏" : message;
    }

    /** 清理过期上传任务及其临时数据，控制磁盘和内存占用。 */
    private void cleanupExpired() {
        Instant now = Instant.now();
        sessions.values().stream()
                .filter(session -> session.expiresAt.isBefore(now))
                .map(session -> session.documentId)
                .toList()
                .forEach(this::deleteSession);
    }

    private void deleteFileQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 由会话到期清理再次尝试，不能因为临时文件占用覆盖主要处理结果。
        }
    }

    /** 仅在临时索引根目录内递归清理，清理失败不向客户端暴露本地路径。 */
    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !directory.normalize().startsWith(rootDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteFileQuietly);
        } catch (IOException ignored) {
            // 临时目录清理失败不向客户端暴露本机路径。
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        sessions.clear();
        deleteDirectoryQuietly(rootDirectory);
    }

    private final class DocumentChunkWriter implements AutoCloseable {

        private final UploadSession session;
        private final FileChannel channel;
        private final StringBuilder pending = new StringBuilder(INDEX_CHUNK_CHARACTERS * 2);
        private String currentLabel = "文档正文";

        private DocumentChunkWriter(UploadSession session) throws IOException {
            this.session = session;
            this.channel = FileChannel.open(
                    session.chunkFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        }

        private void append(String label, String content) throws IOException {
            if (session.cancelled || Thread.currentThread().isInterrupted()) {
                throw new DocumentUploadException(
                        DocumentUploadException.Reason.CONFLICT,
                        "文档处理已取消"
                );
            }
            // 同一段落可由多个有界读取块组成；只在真正换段时插入标签，保持跨块词组连续。
            if (!label.equals(currentLabel)) {
                appendBounded("\n## " + label + '\n', label);
            }
            currentLabel = label;
            appendBounded(content, label);
        }

        /**
         * 分段追加正文，确保 pending 始终只保留一个索引块和重叠区。
         * PDF 单页或 Word 单段可能非常长，不能先把整段复制到 StringBuilder 再切分。
         */
        private void appendBounded(String value, String label) throws IOException {
            int offset = 0;
            while (offset < value.length()) {
                int available = INDEX_CHUNK_CHARACTERS - pending.length();
                int end = Math.min(value.length(), offset + Math.max(1, available));
                pending.append(value, offset, end);
                offset = end;
                if (pending.length() < INDEX_CHUNK_CHARACTERS) {
                    continue;
                }

                writeChunk(pending.substring(0, INDEX_CHUNK_CHARACTERS), label);
                String overlap = pending.substring(INDEX_CHUNK_CHARACTERS - INDEX_CHUNK_OVERLAP);
                pending.setLength(0);
                pending.append(overlap);
            }
        }

        private void finish() throws IOException {
            if (!pending.isEmpty()
                    && (session.chunks.isEmpty() || pending.length() > INDEX_CHUNK_OVERLAP)) {
                writeChunk(pending.toString(), currentLabel);
                pending.setLength(0);
            }
            channel.force(false);
        }

        private void writeChunk(String content, String label) throws IOException {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            long offset = channel.position();
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            synchronized (session.monitor) {
                int index = session.chunks.size();
                session.chunks.add(new ChunkRecord(
                        index,
                        label,
                        offset,
                        bytes.length
                ));
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private static final class UploadSession {

        private final Object monitor = new Object();
        private final String documentId;
        private final UUID ownerUserId;
        private final String path;
        private final String language;
        private final long fileSizeBytes;
        private final int expectedChunks;
        private final Path directory;
        private final Path sourceFile;
        private final Path chunkFile;
        private final Path vectorFile;
        private final BitSet receivedChunks;
        private final List<ChunkRecord> chunks = new ArrayList<>();

        private volatile DocumentProcessingPhase phase = DocumentProcessingPhase.UPLOADING;
        private volatile long uploadedBytes;
        private volatile int progressPercent;
        private volatile long extractedCharacters;
        private volatile String requestId;
        private volatile boolean extractionComplete;
        private volatile String summary = "";
        private volatile List<String> warnings = List.of();
        private volatile String errorMessage = "";
        private volatile Instant expiresAt;
        private volatile boolean cancelled;
        private volatile Future<?> task;

        private UploadSession(
                String documentId,
                UUID ownerUserId,
                String path,
                String language,
                long fileSizeBytes,
                Path directory,
                Path sourceFile,
                Path chunkFile,
                Path vectorFile,
                Instant expiresAt
        ) {
            this.documentId = documentId;
            this.ownerUserId = ownerUserId;
            this.path = path;
            this.language = language;
            this.fileSizeBytes = fileSizeBytes;
            this.expectedChunks = Math.toIntExact(
                    (fileSizeBytes + UPLOAD_CHUNK_BYTES - 1) / UPLOAD_CHUNK_BYTES
            );
            this.directory = directory;
            this.sourceFile = sourceFile;
            this.chunkFile = chunkFile;
            this.vectorFile = vectorFile;
            this.receivedChunks = new BitSet(expectedChunks);
            this.expiresAt = expiresAt;
        }
    }

    private record ChunkRecord(
            int index,
            String label,
            long byteOffset,
            int byteLength
    ) {
    }

    private record ScoredRecord(
            ChunkRecord record,
            int lexicalScore,
            double semanticScore,
            double combinedScore
    ) {
    }
}
