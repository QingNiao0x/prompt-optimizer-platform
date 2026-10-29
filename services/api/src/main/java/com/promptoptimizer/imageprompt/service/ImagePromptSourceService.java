package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.context.service.impl.StreamingDocumentExtractor;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.Input;
import com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * 素材工作台的非执行型资料准备。只进行本地提取、凭据脱敏和有界分块，不调用视觉、摘要或向量模型。
 * 原件在解析结束即删除；私有片段在身份隔离的短时状态中保留一小时，不提供公开URL。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ImagePromptSourceService {
    public static final Duration TTL = Duration.ofHours(1);
    public static final int MAX_CHARACTERS = 120000, MAX_UNITS = 40, MAX_PREPARED = 600000;
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Set<String> DOCUMENTS = Set.of("doc", "docx", "pdf", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp");
    private static final Set<String> CODE = Set.of("js", "jsx", "ts", "tsx", "vue", "java", "py", "go", "rs", "c", "h", "cpp", "cs", "kt", "php", "rb", "swift", "sql", "css", "scss", "html", "htm");
    private static final Set<String> TEXT = Set.of("txt", "md", "rst", "csv", "tsv", "json", "yaml", "yml", "xml", "toml", "ini", "properties", "log", "example", "dockerfile");
    private final ImagePromptStateStore states;
    private final StreamingDocumentExtractor extractor;
    private final ObjectMapper json;
    private final SensitiveValueScanner scanner = new SensitiveValueScanner();

    public ImagePromptSourceService(ImagePromptStateStore states, StreamingDocumentExtractor extractor, ObjectMapper json) {
        this.states = states; this.extractor = extractor; this.json = json;
    }

    /** 返回当前入口真实开放格式；网页只支持HTML快照，不承诺URL抓取、运行态或全项目调用图。 */
    public Capabilities capabilities() {
        var formats = new TreeSet<>(DOCUMENTS); formats.addAll(CODE); formats.addAll(TEXT);
        return new Capabilities(20, MAX_BYTES, MAX_UNITS, MAX_CHARACTERS, 5000, List.copyOf(formats));
    }

    /** Office/PDF按真实解析器读取；其他白名单文件按UTF-8读取，未知二进制不作为文本送模型。 */
    public Asset upload(ActorIdentity actor, MultipartFile file) {
        String name = path(file.getOriginalFilename()); String extension = extension(name);
        if (!supported(extension) || file.isEmpty() || file.getSize() > (DOCUMENTS.contains(extension) ? MAX_BYTES : 5L * 1024 * 1024)) {
            throw ImagePromptException.invalid("资料格式未支持或超过大小限制；文本最多5 MiB，Office/PDF最多50 MiB。");
        }
        var units = new ArrayList<Unit>(); var warnings = new ArrayList<String>(); Path temporary = null; boolean partial = false;
        try {
            temporary = Files.createTempFile("prompt-source-", "." + extension);
            file.transferTo(temporary);
            if (DOCUMENTS.contains(extension)) {
                try {
                    var sections = new SectionBuffer(name, units, warnings);
                    var report = extractor.extract(temporary, name, extension,
                            sections::accept, (done, total) -> { });
                    sections.flush();
                    warnings.addAll(report.warnings()); partial = !report.complete();
                } catch (PreparationLimit limit) { partial = true; warnings.add("解析达到准备上限（600000字符或1000片段），后续正文未读取；请缩小文件或分批添加。"); }
            } else {
                String text = Files.readString(temporary, StandardCharsets.UTF_8);
                rejectBinary(text);
                if (extension.equals("html") || extension.equals("htm")) text = htmlText(text);
                try { append(units, name, "文本正文", text, warnings); }
                catch (PreparationLimit limit) { partial = true; warnings.add("解析达到准备上限（600000字符或1000片段），后续正文未读取；请缩小文件或分批添加。"); }
            }
            return save(actor, name, DOCUMENTS.contains(extension) ? Kind.DOCUMENT : CODE.contains(extension) ? Kind.CODE : Kind.TEXT, units, warnings, partial);
        } catch (ImagePromptException failure) { throw failure; }
        catch (IOException | IllegalArgumentException failure) {
            throw ImagePromptException.invalid(extension.equals("pdf")
                    ? "无法提取PDF文字层；扫描页请使用上下文视觉识别，或先导出图片后在视觉素材区审阅。"
                    : "无法读取该资料，请检查格式、UTF-8编码、文件加密或内容完整性。");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException failure) { temporary.toFile().deleteOnExit(); }
        }
    }

    /** 接受用户主动选择的目录文件集合或粘贴文本；相对路径仅作证据标签，绝不读取对应本地路径。 */
    public Asset text(ActorIdentity actor, TextUpload upload) {
        if (upload == null || upload.name() == null || upload.name().isBlank() || upload.name().length() > 160
                || upload.entries() == null || upload.entries().isEmpty() || upload.entries().size() > 5000) throw ImagePromptException.invalid("资料集合名称或文件数量无效。");
        var paths = new HashSet<String>(); long bytes = 0;
        // 先校验完整清单，不能因前部达到解析预算而跳过后部的受保护路径/重复文件检查。
        for (Entry entry : upload.entries()) {
            if (entry == null || entry.content() == null || entry.content().length() > 500000) throw ImagePromptException.invalid("单项文本最多500000字符。");
            String name = path(entry.path());
            if (!supported(extension(name)) || DOCUMENTS.contains(extension(name)) || !paths.add(name)) throw ImagePromptException.invalid("集合只接受不同路径的受支持纯文本、代码或配置文件。");
            rejectBinary(entry.content()); bytes += entry.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 5L * 1024 * 1024) throw ImagePromptException.invalid("所选文字合计超过5 MiB，请缩小范围或分批添加。");
        }
        var units = new ArrayList<Unit>(); var warnings = new ArrayList<String>(); boolean partial = false;
        try {
            for (Entry entry : upload.entries()) {
                String content = Set.of("html", "htm").contains(extension(entry.path())) ? htmlText(entry.content()) : entry.content();
                append(units, path(entry.path()), "文件片段", content, warnings);
            }
        } catch (PreparationLimit limit) { partial = true; warnings.add("集合解析达到准备上限（600000字符或1000片段），后续文件或片段未读取；请缩小范围后重新添加。"); }
        catch (IOException failure) { throw ImagePromptException.invalid("HTML快照无法提取正文。"); }
        if (upload.entries().size() > 1) warnings.add("文件夹仅包含本次主动提交的文件；未执行项目，未解析完整调用图或运行界面。");
        return save(actor, safeName(upload.name()), upload.entries().size() > 1 ? Kind.COLLECTION : Kind.TEXT, units, warnings, partial);
    }

    /** 私有来源过期或已撤销后拒绝读取；随机编号不构成授权。 */
    public Asset require(ActorIdentity actor, UUID id) {
        Stored stored = states.get(actor, "source:" + id, Stored.class);
        if (stored == null || stored.revoked() || !stored.asset().expiresAt().isAfter(Instant.now())) throw ImagePromptException.expired();
        return stored.asset();
    }

    /** 撤销是逻辑状态，不让已排队生成或旧Plan继续访问原片段。 */
    public void revoke(ActorIdentity actor, UUID id) {
        Asset asset = require(actor, id); states.put(actor, "source:" + id, new Stored(asset, true), TTL);
    }

    /** 只解析本次明确勾选的单元，最终输入和历史不会包含未选片段。 */
    public List<Source> resolve(ActorIdentity actor, Task task) {
        if (task == null || task.taskType() == null || task.sources() == null || task.sources().size() > 20) throw ImagePromptException.invalid("资料任务类型或来源数量无效。");
        var result = new ArrayList<Source>(); var assetIds = new HashSet<UUID>(); var unitIds = new HashSet<UUID>(); int characters = 0;
        for (Reference reference : task.sources()) {
            if (reference == null || reference.assetId() == null || reference.track() == null || reference.role() == null
                    || !assetIds.add(reference.assetId()) || reference.unitIds() == null || reference.unitIds().isEmpty()) throw ImagePromptException.invalid("请为每份资料选择有效片段及用途。");
            Asset asset = require(actor, reference.assetId()); var selected = new ArrayList<Unit>();
            for (UUID id : reference.unitIds()) {
                if (id == null || !unitIds.add(id)) throw ImagePromptException.invalid("资料片段不能重复选择。");
                Unit unit = asset.units().stream().filter(value -> value.id().equals(id)).findFirst()
                        .orElseThrow(() -> ImagePromptException.invalid("片段不属于所选资料。"));
                characters += unit.text().length(); selected.add(unit);
            }
            var warnings = new ArrayList<>(asset.warnings());
            if (selected.size() < asset.units().size()) warnings.add("本次仅使用已勾选片段，未选部分不视为已分析。");
            result.add(new Source(asset.id(), asset.name(), asset.kind(), reference.track(), reference.role(), List.copyOf(selected), List.copyOf(warnings)));
        }
        if (unitIds.size() > MAX_UNITS || characters > MAX_CHARACTERS) throw ImagePromptException.invalid("本次最多40个资料片段、120000字符，请缩小使用范围。");
        return List.copyOf(result);
    }

    /** 同意绑定完整任务、来源范围及模型；请求只凭consentId不能更改已审阅内容。 */
    public Consent consent(ActorIdentity actor, Input input) {
        resolve(actor, input.sourceTask());
        UUID id = UUID.randomUUID(); Instant expiry = input.sourceTask().sources().stream().map(ref -> require(actor, ref.assetId()).expiresAt())
                .min(Comparator.naturalOrder()).orElse(Instant.now().plus(TTL));
        states.put(actor, "source-consent:" + id, new BoundConsent(binding(input), expiry), Duration.between(Instant.now(), expiry));
        return new Consent(id, expiry);
    }

    /** 生成与Plan确认均核验已确认的快照；范围、模式、目标或接收模型改变即失效。 */
    public void requireConsent(ActorIdentity actor, Input input) {
        if (input.sourceTask() == null || input.sourceTask().consentId() == null) throw ImagePromptException.invalid("请先审阅资料并确认本次模型外发。");
        BoundConsent consent = states.get(actor, "source-consent:" + input.sourceTask().consentId(), BoundConsent.class);
        if (consent == null || !consent.expiresAt().isAfter(Instant.now()) || !consent.fingerprint().equals(binding(input))) {
            throw ImagePromptException.conflict("资料、目标或接收模型已改变，请重新核对外发范围并确认隐私。");
        }
    }

    /** 排除凭据本身再计算绑定；同意凭据不参与其自身指纹，其他输入全部保留。 */
    private String binding(Input input) {
        Task task = input.sourceTask();
        Input view = new Input(input.mode(), input.modelId(), input.images(), input.creationIntent(), input.exactText(), input.context(),
                input.language(), input.targetTool(), input.outputType(), input.materials(), input.relations(), new Task(task.taskType(), task.sources(), null));
        try { return ImagePromptAssetService.hash(json.writeValueAsBytes(view)); }
        catch (JsonProcessingException failure) { throw ImagePromptException.invalid("资料授权无法绑定。"); }
    }

    /** 脱敏在分块前执行，避免跨块凭据漏检；块保留路径和连续范围，不假称AST或运行分析。 */
    private void append(List<Unit> units, String path, String label, String content, List<String> warnings) {
        if (content == null || content.isBlank()) return;
        String safe = scanner.redact(content);
        if (!safe.equals(content)) warnings.add("已对可识别的凭据值执行脱敏，请继续人工检查其他隐私。");
        int used = units.stream().mapToInt(unit -> unit.text().length()).sum();
        for (int offset = 0; offset < safe.length(); offset += 6000) {
            if (used >= MAX_PREPARED || units.size() >= 1000) throw new PreparationLimit();
            int end = Math.min(safe.length(), Math.min(offset + 6000, offset + MAX_PREPARED - used));
            units.add(new Unit(UUID.randomUUID(), path, label + " · 字符 " + (offset + 1) + "–" + end, safe.substring(offset, end)));
            used += end - offset;
        }
    }

    /** 一小时资产名额有界；原件、全文和凭据不进入技术日志。 */
    private synchronized Asset save(ActorIdentity actor, String name, Kind kind, List<Unit> units, List<String> warnings, boolean partial) {
        if (units.isEmpty()) throw ImagePromptException.invalid("资料没有可提取文字；请提供文字层、文本导出或视觉参考。");
        UUID[] old = states.get(actor, "source-index", UUID[].class); var active = new ArrayList<UUID>();
        if (old != null) for (UUID id : old) { try { require(actor, id); active.add(id); } catch (ImagePromptException ignored) { } }
        if (active.size() >= 20) throw ImagePromptException.invalid("最多保留20份临时资料，请移除不用的资料后继续。");
        UUID id = UUID.randomUUID(); Instant expiry = Instant.now().plus(TTL);
        var asset = new Asset(id, name, kind, partial ? PreparationStatus.PARTIAL : PreparationStatus.READY, List.copyOf(units), warnings.stream().distinct().toList(), expiry, units.size());
        states.put(actor, "source:" + id, new Stored(asset, false), TTL); active.add(id);
        states.put(actor, "source-index", active.toArray(UUID[]::new), TTL); return asset;
    }

    /** 文件路径只作相对标签；密钥、生产配置及穿越路径继续遵守上下文默认红线。 */
    private String path(String raw) {
        String value = raw == null ? "" : raw.replace('\\', '/').strip();
        String file = value.substring(value.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.length() > 300 || value.startsWith("/") || value.contains(":") || value.chars().anyMatch(c -> c < 32)
                || Arrays.stream(value.split("/")).anyMatch(part -> part.equals(".."))) throw ImagePromptException.invalid("资料只接受安全相对路径。");
        if (Arrays.stream(value.toLowerCase(Locale.ROOT).split("/")).anyMatch(part -> Set.of(".git", ".ssh", ".aws").contains(part))
                || Set.of(".env", "id_rsa", "id_ed25519", "credentials", "credentials.json").contains(file)
                || file.startsWith(".env.") && !file.equals(".env.example") || file.matches(".*\\.(pem|key|p12|jks)$")
                || value.toLowerCase(Locale.ROOT).matches(".*(?:application[-.](?:prod|production)|config/(?:prod|production))\\.(?:yml|yaml|properties|json|toml)$")) {
            throw ImagePromptException.invalid("受保护的凭据或生产配置文件不能进入素材工作台。");
        }
        return safeName(value);
    }
    private String safeName(String value) { if (scanner.containsCredential(value)) throw ImagePromptException.invalid("资料名称含疑似凭据。"); return value; }
    private String extension(String path) { String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT); return file.contains(".") ? file.substring(file.lastIndexOf('.') + 1) : file; }
    private boolean supported(String extension) { return DOCUMENTS.contains(extension) || TEXT.contains(extension) || CODE.contains(extension); }
    private void rejectBinary(String value) { if (value.indexOf('\0') >= 0 || value.indexOf('\uFFFD') >= 0) throw ImagePromptException.invalid("文件不是可识别的UTF-8文本。"); }

    /**
     * Office解析器可能把同一逻辑段按读取缓冲区切开。先合并相同段标签的连续回调，再脱敏和分块，
     * 防止字段名或凭据值横跨读取边界后漏检；段、页发生变化时立即消费，不累积整份文档。
     */
    private final class SectionBuffer {
        private final String path;
        private final List<Unit> units;
        private final List<String> warnings;
        private String label;
        private final StringBuilder pending = new StringBuilder();

        private SectionBuffer(String path, List<Unit> units, List<String> warnings) {
            this.path = path; this.units = units; this.warnings = warnings;
        }

        private void accept(String nextLabel, String content) {
            if (label != null && !label.equals(nextLabel)) flush();
            label = nextLabel;
            // 单次巨型页也受准备预算约束；明确拒绝而不是为扫描整页分配无界额外内存。
            if (content.length() > MAX_PREPARED + 16000) throw ImagePromptException.invalid("单段文字超过安全准备上限，请拆分资料后重新添加。");
            pending.append(content);
            if (pending.length() > MAX_PREPARED) flush();
        }

        private void flush() {
            if (pending.isEmpty()) return;
            String content = pending.toString(), sourceLabel = label;
            pending.setLength(0); label = null;
            append(units, path, sourceLabel, content, warnings);
        }
    }

    /** JDK HTML解析器仅取可见文字，不执行脚本、不解析外部链接、不加载图片或样式资源。 */
    private String htmlText(String text) throws IOException {
        var output = new StringBuilder();
        new ParserDelegator().parse(new StringReader(text), new HTMLEditorKit.ParserCallback() {
            private int hidden;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) { if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden++; }
            @Override public void handleEndTag(HTML.Tag tag, int position) { if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden = Math.max(0, hidden - 1); }
            @Override public void handleText(char[] data, int position) { if (hidden == 0) output.append(data).append('\n'); }
        }, true);
        return output.toString();
    }
    private static final class PreparationLimit extends IllegalArgumentException { }
}
