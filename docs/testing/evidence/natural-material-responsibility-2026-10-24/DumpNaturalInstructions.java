import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptScopedInstructions;
import java.nio.file.*;

/** 冻结实际编译的准备/正文/Plan指导和真实默认版本；不调用模型。 */
class DumpNaturalInstructions {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        for (int index = 1; index < args.length; index++) {
            String version = args[index];
            Files.writeString(output.resolve("enhancement-v" + version + ".txt"),
                    NaturalPromptInstructions.enhancementInstruction(version), StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("planning-v" + version + ".txt"),
                    NaturalPromptInstructions.planningInstruction(version), StandardOpenOption.CREATE_NEW);
            String preparation = "35".equals(version)
                    ? (String) Class.forName("com.promptoptimizer.provider.domain.NaturalPromptMaterialInstructions").getField("PREPARE").get(null)
                    : NaturalPromptScopedInstructions.PREPARE;
            Files.writeString(output.resolve("preparation-v" + version + ".txt"), preparation,
                    StandardOpenOption.CREATE_NEW);
        }
        Files.writeString(output.resolve("compiled-default-version.txt"), NaturalPromptInstructions.CURRENT_VERSION,
                StandardOpenOption.CREATE_NEW);
    }
}
