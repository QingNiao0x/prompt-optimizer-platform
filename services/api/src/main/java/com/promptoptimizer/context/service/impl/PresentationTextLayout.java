package com.promptoptimizer.context.service.impl;

import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.hslf.usermodel.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * PPT/PPTX 形状遍历；内联入口与旧 PPT 上传入口共享分组文本和表格坐标语义。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PresentationTextLayout {
    private PresentationTextLayout() {}

    /** 只读取包内形状文本；显式栈遍历分组，保持原顺序并避免深层分组耗尽调用栈。 */
    static void append(StringBuilder result, List<XSLFShape> shapes) {
        Deque<Iterator<XSLFShape>> pending = new ArrayDeque<>();
        pending.push(shapes.iterator());
        while (!pending.isEmpty()) {
            if (!pending.peek().hasNext()) { pending.pop(); continue; }
            var shape = pending.peek().next();
            if (shape instanceof XSLFGroupShape group) pending.push(group.getShapes().iterator());
            else if (shape instanceof XSLFTextShape text) result.append(text.getText()).append('\n');
            else if (shape instanceof XSLFTable table) {
                for (int row = 0; row < table.getNumberOfRows(); row++) {
                    for (int col = 0; col < table.getNumberOfColumns(); col++) {
                        var cell = table.getCell(row, col);
                        if (cell != null) result.append("[表格 行").append(row + 1).append(" 列").append(col + 1)
                                .append("] ").append(cell.getText()).append('\n');
                    }
                }
            }
        }
    }

    /** 旧 PPT 同样保留表格和分组，不把表格组内的单元格重新当作散落文本重复输出。 */
    static void appendLegacy(StringBuilder result, List<HSLFShape> shapes) {
        Deque<Iterator<HSLFShape>> pending = new ArrayDeque<>();
        pending.push(shapes.iterator());
        while (!pending.isEmpty()) {
            if (!pending.peek().hasNext()) { pending.pop(); continue; }
            var shape = pending.peek().next();
            if (shape instanceof HSLFTable table) {
                for (int row = 0; row < table.getNumberOfRows(); row++) {
                    for (int col = 0; col < table.getNumberOfColumns(); col++) {
                        var cell = table.getCell(row, col);
                        if (cell != null) result.append("[表格 行").append(row + 1).append(" 列").append(col + 1)
                                .append("] ").append(cell.getText()).append('\n');
                    }
                }
            } else if (shape instanceof HSLFGroupShape group) pending.push(group.getShapes().iterator());
            else if (shape instanceof HSLFTextShape text) result.append(text.getText()).append('\n');
        }
    }
}
