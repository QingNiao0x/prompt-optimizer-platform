package com.promptoptimizer.context.service.impl;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCell;

/**
 * 共享表格文本布局：用原始单元格坐标保留稀疏列、隐藏数据和合并范围。
 * 只读取保存的公式及缓存，不计算公式、不刷新外部链接，也不执行宏。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class SpreadsheetTextLayout {
    private SpreadsheetTextLayout() {}

    /** 标记工作表可见性与合并范围；不把隐藏资料或合并单元格静默丢弃。 */
    static String heading(Workbook workbook, int index) {
        var sheet = workbook.getSheetAt(index);
        var result = new StringBuilder("工作表：").append(sheet.getSheetName());
        if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) result.append(" [隐藏工作表]");
        result.append('\n');
        for (var range : sheet.getMergedRegions()) result.append("合并范围：").append(range.formatAsString()).append('\n');
        return result.toString();
    }

    /** 仅遍历真实单元格，坐标保留空列和空行位置，避免为稀疏表扩张大量空占位。 */
    static String row(Row row, DataFormatter formatter) {
        var result = new StringBuilder();
        if (row.getZeroHeight()) result.append("[隐藏行 ").append(row.getRowNum() + 1).append("] ");
        for (var cell : row) {
            if (!result.isEmpty()) result.append('\t');
            result.append(cell.getAddress().formatAsString()).append('=');
            if (row.getSheet().isColumnHidden(cell.getColumnIndex())) result.append("[隐藏列] ");
            if (cell.getCellType() == CellType.FORMULA) {
                result.append('=').append(cell.getCellFormula());
                // 新建 XLSX 公式没有 v 元素时不能把 POI 的默认 0 写成已存在的缓存结果。
                if (cell instanceof XSSFCell xssf && !xssf.getCTCell().isSetV()) result.append(" [无缓存值]");
                else {
                    formatter.setUseCachedValuesForFormulaCells(true);
                    try { result.append(" [缓存值（未重算）：").append(formatter.formatCellValue(cell)).append(']'); }
                    finally { formatter.setUseCachedValuesForFormulaCells(false); }
                }
            } else result.append(formatter.formatCellValue(cell));
        }
        return result.append('\n').toString();
    }
}
