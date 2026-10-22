import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 冻结实际运行副本中的4/20生成指导及共同Plan指导；不读账号或调用模型。 */
class DumpNaturalInstructions {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !NaturalPromptInstructions.VERSION.equals("4")) {
            throw new IllegalArgumentException("Expected unchanged default version and three output paths");
        }
        String baseline = NaturalPromptInstructions.enhancementInstruction("4");
        String candidate = NaturalPromptInstructions.enhancementInstruction("20");
        String plan = NaturalPromptInstructions.planningInstruction("4");
        if (baseline.equals(candidate) || !plan.equals(NaturalPromptInstructions.planningInstruction("20"))) {
            throw new IllegalStateException("Expected changed single-pass check and unchanged Plan instruction");
        }
        Files.writeString(Path.of(args[0]), baseline, StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(args[1]), candidate, StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(args[2]), plan, StandardOpenOption.CREATE_NEW);
    }
}
