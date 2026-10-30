package com.promptoptimizer.context.service.impl;

import javax.xml.stream.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.zip.*;

/**
 * 按 PPTX 的 presentation 关系表解析实际页序及每页备注，不以 slide 文件编号推测顺序。
 * 仅解析包内允许的相对 XML 关系；拒绝外链、缺页和目录越界，不获取外部资源。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PresentationPackageOrder {
    private static final long MAX_METADATA_BYTES = 4L * 1024 * 1024;
    private PresentationPackageOrder() {}

    /** 主清单缺失时保留旧兼容读取并显式提示；清单存在时必须完整解析，不静默降级。 */
    static List<ZipEntry> resolve(ZipFile zip, List<? extends ZipEntry> fallback, List<String> warnings)
            throws IOException, XMLStreamException {
        ZipEntry presentation = zip.getEntry("ppt/presentation.xml");
        if (presentation == null) {
            warnings.add("演示文稿缺少页序清单，暂按包内文件编号提取，实际页序未核实。");
            return new ArrayList<>(fallback);
        }
        long[] metadataBytes = {0};
        var relationships = relationships(zip, "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels", "slide", metadataBytes);
        var result = new ArrayList<ZipEntry>();
        var used = new HashSet<String>();
        visit(zip, presentation, metadataBytes, reader -> {
            if (!"sldId".equals(reader.getLocalName())) return;
            String id = null;
            for (int i = 0; i < reader.getAttributeCount(); i++) {
                if ("id".equals(reader.getAttributeLocalName(i)) && reader.getAttributeNamespace(i) != null
                        && reader.getAttributeNamespace(i).endsWith("/relationships")) id = reader.getAttributeValue(i);
            }
            String target = relationships.get(id);
            if (target == null || !target.matches("ppt/slides/[^/]+\\.xml") || !used.add(target))
                throw new IllegalArgumentException("演示文稿页序关系缺失、重复或无效");
            result.add(requireEntry(zip, target));
            String name = target.substring(target.lastIndexOf('/') + 1);
            var notes = relationships(zip, target, "ppt/slides/_rels/" + name + ".rels", "notesSlide", metadataBytes);
            for (String note : notes.values()) {
                if (!note.matches("ppt/notesSlides/[^/]+\\.xml")) throw new IllegalArgumentException("幻灯片备注关系无效");
                result.add(requireEntry(zip, note));
            }
        });
        if (result.isEmpty()) throw new IllegalArgumentException("演示文稿页序清单中没有幻灯片");
        return result;
    }

    /** 仅信任当前文档内部的同类型关系；不跟随网络地址或越出包内根目录。 */
    private static Map<String, String> relationships(ZipFile zip, String owner, String name, String type, long[] metadataBytes)
            throws IOException, XMLStreamException {
        var result = new LinkedHashMap<String, String>();
        var entry = zip.getEntry(name);
        if (entry == null) return result;
        visit(zip, entry, metadataBytes, reader -> {
            if (!"Relationship".equals(reader.getLocalName())) return;
            String relationType = reader.getAttributeValue(null, "Type");
            if (relationType == null || !relationType.endsWith("/" + type)) return;
            if ("External".equalsIgnoreCase(reader.getAttributeValue(null, "TargetMode")))
                throw new IllegalArgumentException("演示文稿包含外部幻灯片或备注，不能作为本地正文读取");
            String raw = reader.getAttributeValue(null, "Target");
            if (raw == null || raw.contains("\\")) throw new IllegalArgumentException("演示文稿内部关系无效");
            URI uri = URI.create(owner).resolve(raw).normalize();
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getQuery() != null || uri.getFragment() != null
                    || path == null || path.contains(".."))
                throw new IllegalArgumentException("演示文稿内部关系超出允许范围");
            // OPC 允许 /ppt/... 形式的包内绝对部件名；这不是文件系统路径。
            if (path.startsWith("/")) path = path.substring(1);
            if (result.putIfAbsent(reader.getAttributeValue(null, "Id"), path) != null)
                throw new IllegalArgumentException("演示文稿内部关系编号重复");
        });
        return result;
    }

    /** 引用的正文必须实际存在；缺失页面不能被算作完整解析。 */
    private static ZipEntry requireEntry(ZipFile zip, String path) {
        var entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("演示文稿引用的页面或备注缺失");
        return entry;
    }

    /** 元数据限额独立于正文，禁止 DTD 和实体，不允许关系表导致无界解析。 */
    private static void visit(ZipFile zip, ZipEntry entry, long[] metadataBytes, ElementConsumer consumer) throws IOException, XMLStreamException {
        if (entry.getSize() < 0 || entry.getSize() > MAX_METADATA_BYTES)
            throw new IllegalArgumentException("演示文稿关系清单超过安全上限");
        metadataBytes[0] += entry.getSize();
        if (metadataBytes[0] > 16L * 1024 * 1024)
            throw new IllegalArgumentException("演示文稿关系清单总量超过 16 MB 安全上限");
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (var input = zip.getInputStream(entry)) {
            var reader = factory.createXMLStreamReader(input);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw new IllegalArgumentException("文档关系清单不允许 DTD");
                    if (event == XMLStreamConstants.START_ELEMENT) consumer.accept(reader);
                }
            } finally { reader.close(); }
        }
    }

    @FunctionalInterface
    private interface ElementConsumer {
        void accept(XMLStreamReader reader) throws IOException, XMLStreamException;
    }
}
