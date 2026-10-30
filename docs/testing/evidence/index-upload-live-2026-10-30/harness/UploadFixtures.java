import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;

/** 本轮只生成固定合成文档，不读取用户业务资料或向模型发送内容。 */
public class UploadFixtures {
 public static void main(String[] args) throws Exception {
  Path root=Path.of(args[0]);Files.createDirectories(root);
  try(var doc=new XWPFDocument()) {
   doc.createParagraph().createRun().setText("WORD_HEAD_QINGYE731：合成采购报告；金额12345.67元。");
   for(int i=0;i<700;i++)doc.createParagraph().createRun().setText("材料第"+i+"段：这是文件完整性验收资料。保留原始约束，退款期限为三个工作日，不删除既有记录。".repeat(5));
   var table=doc.createTable(3,3);table.getRow(0).getCell(0).setText("物品");table.getRow(0).getCell(1).setText("数量");table.getRow(0).getCell(2).setText("金额");table.getRow(1).getCell(0).setText("纸张");table.getRow(1).getCell(1).setText("12");table.getRow(1).getCell(2).setText("78.00");
   doc.createParagraph().createRun().setText("WORD_TAIL_QINGYE731：最终验收阈值为27件，超过27件必须人工复核。");
   try(var out=Files.newOutputStream(root.resolve("采购报告.docx"))){doc.write(out);}
  }
  try(var book=new XSSFWorkbook()) {
   for(int s=0;s<2;s++){var sheet=book.createSheet(s==0?"采购明细":"第二张表");
    sheet.createRow(0).createCell(0).setCellValue(s==0?"XLSX_HEAD_QINGYE731":"XLSX_SHEET2_QINGYE731");
    for(int i=1;i<=2000;i++){var row=sheet.createRow(i);row.createCell(0).setCellValue("记录"+i);row.createCell(1).setCellValue(i);row.createCell(2).setCellValue(6.5);}
    sheet.createRow(2001).createCell(0).setCellValue(s==0?"XLSX_MID_QINGYE731":"XLSX_TAIL_QINGYE731：第二张表尾部保留，退款期限三个工作日。");
   }try(var out=Files.newOutputStream(root.resolve("明细表.xlsx"))){book.write(out);}
  }
  try(var doc=new PDDocument()) {
   for(int i=0;i<5;i++){var page=new PDPage();doc.addPage(page);try(var out=new PDPageContentStream(doc,page)){out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(40,750);out.showText(i==0?"PDF_HEAD_QINGYE731":i==4?"PDF_TAIL_QINGYE731: threshold 27; retain history.":"PDF_MIDDLE_QINGYE731 page "+i);for(int j=0;j<35;j++){out.newLineAtOffset(0,-18);out.showText("Synthetic procurement record "+i+"-"+j+"; amount 12345.67; preserve constraints.");}out.endText();}}
   doc.save(root.resolve("采购附件.pdf").toFile());
  }
  String text="TXT_HEAD_QINGYE731：大文件分片验收。\n"+"正文资料：保留文件中的数字、条件和不同业务对象，不截断末尾内容。\n".repeat(35000)+"TXT_TAIL_QINGYE731：最终规则，恰好五万元仍需复核。\n";
  Files.writeString(root.resolve("大文件.txt"),text,StandardCharsets.UTF_8);
  Files.writeString(root.resolve("损坏文档.docx"),"not a valid office archive",StandardCharsets.UTF_8);
  System.out.println("SYNTHETIC_OFFICE_FIXTURES_READY");
 }
}
