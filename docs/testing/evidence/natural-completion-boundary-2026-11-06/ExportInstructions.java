import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptPlanContextInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptRevision;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 导出范围准备、正文、Plan及定向修改的完整运行指导，供离线冻结与对照使用。
 * 只加载已编译的公开指导常量，不创建供应商、读取凭据或调用模型。
 */
class ExportInstructions {
    /** 输出目录由本批捕获脚本指定，历史文件由捕获脚本保护。 */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        String version = args.length > 1 ? args[1] : NaturalPromptPlanContextInstructions.VERSION;
        Files.createDirectories(output);
        write(output, "prepare.txt", NaturalPromptPlanContextInstructions.PREPARE);
        write(output, "enhance.txt", NaturalPromptInstructions.enhancementInstruction(version));
        write(output, "plan.txt", NaturalPromptInstructions.planningInstruction(version));
        write(output, "revision-prepare.txt", NaturalPromptRevision.forStage(false));
        write(output, "revision-enhance.txt", NaturalPromptRevision.forStage(true));
    }

    /** 按UTF-8保留原始指导字节，不作换行或格式归一化。 */
    private static void write(Path output, String name, String instruction) throws Exception {
        Files.writeString(output.resolve(name), instruction, StandardCharsets.UTF_8);
    }
}
