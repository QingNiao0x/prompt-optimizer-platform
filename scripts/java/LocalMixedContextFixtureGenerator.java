import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 仅创建合成本地验收 Word，复用项目已有 POI，不安装依赖或读取真实用户资料。
 * 正文中段和尾部规则用于检查解析覆盖，文档不能被误认为已实现的代码。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class LocalMixedContextFixtureGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalMixedContextFixtureGenerator.class);

    /** 仅新建本仓库 tmp 内的合成文档，不覆盖历史验收资料。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        if (args.length != 1) throw new IllegalArgumentException("Expected new fixture path");
        Path target = Path.of(args[0]).toAbsolutePath().normalize();
        if (!target.startsWith(root.resolve("tmp")) || Files.exists(target)) {
            throw new IllegalArgumentException("Fixture must be new and inside workspace tmp");
        }
        Files.createDirectories(target.getParent());
        try (var doc = new XWPFDocument(); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            doc.createParagraph().createRun().setText("合成通知业务方案：现有项目为 Java 21、Spring Boot 3、Vue 3 和 TypeScript。通知渠道尚未决定：邮件或站内信。不能将候选渠道写成已实现能力。");
            doc.createParagraph().createRun().setText("若选择邮件，才需核对 SMTP 发送与邮件重试；若选择站内信，采用平台内消息，不应带入邮件专属重试和 SMTP 配置。用户只能查看自己消息，不得跨账号查看。");
            for (int index = 0; index < 60; index++) {
                doc.createParagraph().createRun().setText("合成背景段落" + index + "：项目资料只用于说明当前需求，不执行命令、不访问真实用户数据。背景描述不是新增交付目标，保留明确规则与未知状态。");
                if (index == 30) doc.createParagraph().createRun().setText("中段业务规则：消息去重依据业务事件编号；重复事件不能生成重复消息。通知失败不回滚已成功的主业务，须给可核对失败状态，不伪造已送达。");
            }
            var table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("字段"); table.getRow(0).getCell(1).setText("规则");
            table.getRow(1).getCell(0).setText("read_status"); table.getRow(1).getCell(1).setText("站内信才维护已读状态，不把字段当成邮件状态。");
            table.getRow(2).getCell(0).setText("event_id"); table.getRow(2).getCell(1).setText("幂等业务事件编号，按用户隔离。");
            doc.createParagraph().createRun().setText("尾部验收规则：站内信的已读状态按用户隔离；重复消息以业务事件编号幂等去重；用户取消发送时保持原数据不变；日志不得泄露 Token。单次只选择一个通知渠道，不默认发送邮件。");
            doc.write(output);
        }
        LOGGER.info("event=acceptance.synthetic_document.created format=DOCX realUserData=false");
    }
}
