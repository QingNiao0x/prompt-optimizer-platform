package com.promptoptimizer.context.service.impl;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 Office 活跃正文的包内关系提取图片目录，不读取外链、不解包到文件系统。
 * 图片仍须经过现有像素校验、预览、遮挡和用户确认，目录本身不发起模型调用。
 * @author QingNiao
 * @since 0.1.0
 */
final class OfficeEmbeddedImages {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_XML_BYTES = 256L * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("docx", "pptx", "xlsx", "odt", "odp", "ods");
    private static final Set<String> REFERENCED_PARTS = Set.of("header", "footer", "drawing", "slide", "worksheet");
    private static final Set<String> IMPLICIT_PARTS = Set.of("footnotes", "endnotes", "notesSlide");

    private OfficeEmbeddedImages() { }

    static boolean supports(String language) { return FORMATS.contains(language); }

    /** 只枚举正文实际引用的图片；重复使用同一部件仅审阅一次，超出审阅上限明确报告。 */
    static Catalog inspect(Path source, String language, int maxImages) throws IOException {
        try (var zip = new ZipFile(source.toFile())) {
            if (zip.size() > 10_000) throw new IllegalArgumentException("Office 包内条目超过安全上限");
            var scan = new Scan(zip, maxImages);
            String root = switch (language) {
                case "docx" -> "word/document.xml";
                case "pptx" -> "ppt/presentation.xml";
                case "xlsx" -> "xl/workbook.xml";
                default -> "content.xml";
            };
            if (zip.getEntry(root) == null && "pptx".equals(language)) {
                scan.warn("演示文稿缺少页序清单，图片按包内幻灯片编号审阅，实际页序未核实。");
                var slides = zip.stream().filter(entry -> entry.getName().matches("ppt/slides/slide\\d+\\.xml"))
                        .sorted(java.util.Comparator.comparingInt(entry -> Integer.parseInt(entry.getName().replaceAll("\\D", ""))))
                        .toList();
                for (var slide : slides) scan.part(slide.getName());
            } else scan.part(root);
            return new Catalog(List.copyOf(scan.images), List.copyOf(scan.warnings));
        } catch (XMLStreamException exception) {
            throw new IllegalArgumentException("Office 图片关系无法解析，请检查文件或重新另存。", exception);
        }
    }

    /** 读取已在本机目录中确认的部件；不接受浏览器提供的资源路径。 */
    static byte[] bytes(Path source, ImagePart image) throws IOException {
        try (var zip = new ZipFile(source.toFile())) {
            var entry = zip.getEntry(image.name());
            if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_IMAGE_BYTES)
                throw new IllegalArgumentException("内嵌图片缺失或超过 10 MiB 上限");
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("内嵌图片超过 10 MiB 上限");
                return bytes;
            }
        }
    }

    record ImagePart(int number, String name, String label, String blockedReason) { }
    record Catalog(List<ImagePart> images, List<String> warnings) { }
    private record Relation(String type, String path, boolean external) { }

    private static final class Scan {
        private final ZipFile zip;
        private final int limit;
        private final Set<String> visited = new HashSet<>();
        private final Set<String> imageNames = new HashSet<>();
        private final List<ImagePart> images = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private long readBytes;

        private Scan(ZipFile zip, int limit) { this.zip = zip; this.limit = limit; }

        /** 递归范围仅限正文、页、工作表、绘图和注释，忽略任意超链接及未引用资源。 */
        private void part(String name) throws IOException, XMLStreamException {
            part(name, 0);
        }

        private void part(String name, int depth) throws IOException, XMLStreamException {
            if (depth > 32) throw new IllegalArgumentException("Office 图片关系超过 32 层安全上限");
            if (!visited.add(name)) return;
            if (visited.size() > 2048) throw new IllegalArgumentException("Office 图片关系层级或数量超过安全上限");
            var relations = relationships(name);
            var references = new LinkedHashSet<String>();
            var children = new LinkedHashSet<String>();
            int[] skippedDepth = {0};
            visit(name, reader -> {
                if (reader.getEventType() == XMLStreamConstants.END_ELEMENT) {
                    if (skippedDepth[0] > 0) skippedDepth[0]--;
                    return;
                }
                if (skippedDepth[0] > 0) { skippedDepth[0]++; return; }
                String local = reader.getLocalName();
                String namespace = reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI();
                if (namespace.contains("wordprocessingml") && Set.of("del", "moveFrom").contains(local)) {
                    skippedDepth[0] = 1;
                    return;
                }
                if ("image".equals(local) && namespace.contains("opendocument")) {
                    String href = attribute(reader, "href");
                    if (href == null) warn("文档含无法解析的内嵌图片，画面未识别。");
                    else addImage(resolve(name, href));
                }
                if ("blip".equals(local) || "imagedata".equals(local)) {
                    String id = relationAttribute(reader, "embed");
                    if (id == null) id = relationAttribute(reader, "id");
                    if (id != null) references.add(id);
                    else if (relationAttribute(reader, "link") == null) warn("文档含未关联到包内图片的绘图，画面未识别。");
                    if (relationAttribute(reader, "link") != null) warn("文档含外链图片，未获取外部资源。");
                }
                if (Set.of("chart", "oleObj", "OLEObject", "altChunk", "relIds").contains(local))
                    warn("文档含图表或嵌入对象，仅保留可读取文字，该对象画面尚未识别。");
                String id = relationAttribute(reader, "id");
                Relation relation = relations.get(id);
                if (relation != null && REFERENCED_PARTS.contains(relation.type())) children.add(id);
            });
            for (String id : references) {
                Relation relation = relations.get(id);
                if (relation == null || !"image".equals(relation.type())) warn("文档含缺失或无效的图片关系，画面未识别。");
                else if (relation.external()) warn("文档含外链图片，未获取外部资源。");
                else addImage(relation.path());
            }
            for (var entry : relations.entrySet())
                if (IMPLICIT_PARTS.contains(entry.getValue().type())) children.add(entry.getKey());
            for (String id : children) {
                Relation relation = relations.get(id);
                if (relation.external()) warn("文档含外部正文或绘图，未获取外部资源。");
                else part(relation.path(), depth + 1);
            }
        }

        /** 图片编号只在当前上传会话中使用，不代表 Word 页码。 */
        private void addImage(String name) {
            if (name == null) { warn("文档含外链或越界图片，未读取其内容。"); return; }
            if (!imageNames.add(name)) return;
            if (images.size() >= limit) { warn("内嵌图片超过本次审阅上限，部分图片未列入且未识别。"); return; }
            ZipEntry entry = zip.getEntry(name);
            String blocked = entry == null || entry.isDirectory() ? "图片部件缺失，无法预览或识别。"
                    : entry.getSize() < 0 || entry.getSize() > MAX_IMAGE_BYTES ? "图片超过 10 MiB 上限，无法识别。" : null;
            int number = images.size() + 1;
            images.add(new ImagePart(number, name, "内嵌图片 " + number + " · " + name, blocked));
        }

        /** 关系表只登记部件身份，不跟随外部目标；重复编号明确拒绝。 */
        private Map<String, Relation> relationships(String owner) throws IOException, XMLStreamException {
            int separator = owner.lastIndexOf('/');
            String path = owner.substring(0, separator + 1) + "_rels/" + owner.substring(separator + 1) + ".rels";
            var result = new LinkedHashMap<String, Relation>();
            if (zip.getEntry(path) == null) return result;
            visit(path, reader -> {
                if (reader.getEventType() != XMLStreamConstants.START_ELEMENT || !"Relationship".equals(reader.getLocalName())) return;
                String type = attribute(reader, "Type"), id = attribute(reader, "Id");
                if (type == null || id == null) throw new IllegalArgumentException("Office 关系清单缺少类型或编号");
                type = type.substring(type.lastIndexOf('/') + 1);
                boolean external = "External".equalsIgnoreCase(attribute(reader, "TargetMode"));
                String target = external ? null : resolve(owner, attribute(reader, "Target"));
                if (result.putIfAbsent(id, new Relation(type, target, external || target == null)) != null)
                    throw new IllegalArgumentException("Office 关系编号重复");
            });
            return result;
        }

        /** 声明大小和实际展开字节均受限，禁用实体并及时响应取消。 */
        private void visit(String name, ElementConsumer consumer) throws IOException, XMLStreamException {
            var entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) { warn("部分 Office 正文或绘图部件缺失，其图片未识别。"); return; }
            if (entry.getSize() < 0 || entry.getSize() > 64L * 1024 * 1024)
                throw new IllegalArgumentException("Office 图片关系正文超过 64 MiB 安全上限");
            var factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            try (var input = new FilterInputStream(zip.getInputStream(entry)) {
                @Override public int read() throws IOException { int value = super.read(); count(value < 0 ? 0 : 1); return value; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = super.read(bytes, offset, length); count(Math.max(0, count)); return count;
                }
                private void count(int count) {
                    readBytes += count;
                    if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("文档处理已取消");
                    if (readBytes > MAX_XML_BYTES) throw new IllegalArgumentException("Office 图片关系展开超过 256 MiB 安全上限");
                }
            }) {
                var reader = factory.createXMLStreamReader(input);
                try {
                    while (reader.hasNext()) {
                        int event = reader.next();
                        if (event == XMLStreamConstants.DTD) throw new IllegalArgumentException("Office 图片关系不允许 DTD");
                        if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT) consumer.accept(reader);
                    }
                } finally { reader.close(); }
            }
        }

        private void warn(String warning) { if (warnings.size() < 100 && !warnings.contains(warning)) warnings.add(warning); }
    }

    /** 包内路径规范化独立于系统路径；拒绝网络、查询、片段和根目录越界。 */
    private static String resolve(String owner, String raw) {
        if (raw == null || raw.contains("\\")) return null;
        try {
            URI uri = URI.create(owner).resolve(raw).normalize();
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getQuery() != null || uri.getFragment() != null
                    || path == null || List.of(path.split("/")).contains("..")) return null;
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (IllegalArgumentException exception) { return null; }
    }

    private static String attribute(XMLStreamReader reader, String name) { return WordParagraphStyles.attribute(reader, name); }

    /** PPT 的普通 id 是页标识，r:id 才是关系编号，不能按属性顺序取第一个同名值。 */
    private static String relationAttribute(XMLStreamReader reader, String name) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            String namespace = reader.getAttributeNamespace(index);
            if (name.equals(reader.getAttributeLocalName(index)) && namespace != null && namespace.endsWith("/relationships"))
                return reader.getAttributeValue(index);
        }
        return null;
    }

    @FunctionalInterface
    private interface ElementConsumer { void accept(XMLStreamReader reader) throws IOException, XMLStreamException; }
}
