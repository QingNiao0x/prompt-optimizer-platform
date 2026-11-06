package com.promptoptimizer.context.service.impl;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * 保留 Word 段落样式及相关排版属性，供格式参考使用。只解释段落属性，不模拟分页或字体渲染。
 * 样式继承先于段落直接格式，循环或缺失的继承明确记录为解析缺口。
 * @author QingNiao
 * @since 0.1.0
 */
final class WordParagraphStyles {
    private final Map<String, Style> styles = new LinkedHashMap<>();
    private final Properties defaults = new Properties();
    private String defaultStyle;

    /** 样式清单有独立大小上限，禁止 DTD 和外部实体。正文提取仍由流式解析器完成。 */
    static WordParagraphStyles read(ZipFile zip) throws IOException, XMLStreamException {
        var result = new WordParagraphStyles();
        var entry = zip.getEntry("word/styles.xml");
        if (entry == null) return result;
        if (entry.getSize() < 0 || entry.getSize() > 4 * 1024 * 1024)
            throw new IllegalArgumentException("Word 样式清单超过 4 MiB 安全上限");
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Word 样式清单超过 4 MiB 安全上限");
            var reader = factory.createXMLStreamReader(new java.io.ByteArrayInputStream(bytes));
            Style current = null;
            int paragraphProperties = 0;
            boolean defaultProperties = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD) throw new IllegalArgumentException("Word 样式不允许 DTD");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = reader.getLocalName();
                        if ("pPrDefault".equals(name)) defaultProperties = true;
                        if ("style".equals(name) && "paragraph".equals(attribute(reader, "type"))) {
                            current = new Style(attribute(reader, "styleId"), new Properties());
                            if ("1".equals(attribute(reader, "default")) || "true".equals(attribute(reader, "default")))
                                result.defaultStyle = current.id;
                        } else if (current != null && "name".equals(name)) current.name = attribute(reader, "val");
                        else if (current != null && "basedOn".equals(name)) current.parent = attribute(reader, "val");
                        if ("pPr".equals(name)) paragraphProperties++;
                        // 表格/字符样式中的 pPr 不能污染所有正文的默认段落格式。
                        if (paragraphProperties > 0 && current != null) current.properties.read(reader);
                        else if (paragraphProperties > 0 && defaultProperties) result.defaults.read(reader);
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("pPr".equals(reader.getLocalName())) paragraphProperties--;
                        if ("pPrDefault".equals(reader.getLocalName())) defaultProperties = false;
                        if ("style".equals(reader.getLocalName()) && current != null) {
                            if (result.styles.size() >= 4096) throw new IllegalArgumentException("Word 段落样式数量超过安全上限");
                            if (current.id != null) result.styles.put(current.id, current);
                            current = null;
                        }
                    }
                }
            } finally { reader.close(); }
        }
        return result;
    }

    /** 只在有格式依据时生成相邻元数据，未出现的格式不补成用户要求。 */
    String describe(Properties direct, List<String> warnings) {
        String id = direct.style == null ? defaultStyle : direct.style;
        var values = new LinkedHashMap<>(defaults.values);
        var chain = new ArrayList<Style>();
        var visited = new HashSet<String>();
        String cursor = id;
        while (cursor != null) {
            if (!visited.add(cursor) || chain.size() >= 32) {
                warn(warnings, "Word 段落样式继承存在循环或超过 32 层，部分格式无法确认。");
                break;
            }
            var style = styles.get(cursor);
            if (style == null) {
                warn(warnings, "Word 引用的部分段落样式定义缺失，已保留样式编号及直接格式。");
                break;
            }
            chain.add(style);
            cursor = style.parent;
        }
        for (int index = chain.size() - 1; index >= 0; index--) merge(values, chain.get(index).properties.values);
        merge(values, direct.values);
        var fields = new ArrayList<String>();
        if (id != null) {
            Style style = styles.get(id);
            fields.add("样式=" + id + (style == null || style.name == null ? "" : "（" + style.name + "）"));
        }
        values.forEach((key, value) -> fields.add(key + "=" + value));
        return fields.isEmpty() ? "" : "[段落格式 " + String.join("，", fields) + "] ";
    }

    /** 首行与悬挂缩进互斥，直接格式应覆盖继承的另一种缩进。 */
    private static void merge(Map<String, String> target, Map<String, String> source) {
        if (source.keySet().stream().anyMatch(key -> key.startsWith("首行") || key.startsWith("悬挂")))
            target.keySet().removeIf(key -> key.startsWith("首行") || key.startsWith("悬挂"));
        target.putAll(source);
    }

    private static void warn(List<String> warnings, String value) {
        if (!warnings.contains(value)) warnings.add(value);
    }

    /** 读取属性本地名，同时兼容 OOXML transitional 与 strict 命名空间。 */
    static String attribute(XMLStreamReader reader, String name) {
        for (int index = 0; index < reader.getAttributeCount(); index++)
            if (name.equals(reader.getAttributeLocalName(index))) return reader.getAttributeValue(index);
        return null;
    }

    /** 单个段落或样式中实际声明的属性，保留原始单位。 */
    static final class Properties {
        private String style;
        private final Map<String, String> values = new LinkedHashMap<>();

        /** 仅在段落属性区域调用，避免将运行或图片属性误当作段落格式。 */
        void read(XMLStreamReader reader) {
            switch (reader.getLocalName()) {
                case "pStyle" -> style = attribute(reader, "val");
                case "jc" -> put(reader, "val", "对齐");
                case "ind" -> {
                    put(reader, "left", "左缩进(twip)");
                    put(reader, "right", "右缩进(twip)");
                    put(reader, "start", "起始缩进(twip)");
                    put(reader, "end", "末端缩进(twip)");
                    put(reader, "firstLine", "首行缩进(twip)");
                    put(reader, "hanging", "悬挂缩进(twip)");
                    put(reader, "firstLineChars", "首行缩进(百分之一字符)");
                    put(reader, "hangingChars", "悬挂缩进(百分之一字符)");
                }
                case "spacing" -> {
                    put(reader, "before", "段前(twip)");
                    put(reader, "after", "段后(twip)");
                    put(reader, "line", "行距原始值");
                    put(reader, "lineRule", "行距规则");
                }
                case "outlineLvl" -> put(reader, "val", "大纲级别");
                case "numId" -> put(reader, "val", "列表编号");
                case "ilvl" -> put(reader, "val", "列表级别");
                default -> { }
            }
        }

        private void put(XMLStreamReader reader, String attribute, String label) {
            String value = attribute(reader, attribute);
            if (value != null) values.put(label, value);
        }
    }

    private static final class Style {
        private final String id;
        private final Properties properties;
        private String parent;
        private String name;

        private Style(String id, Properties properties) { this.id = id; this.properties = properties; }
    }
}
