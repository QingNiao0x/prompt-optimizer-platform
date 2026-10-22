import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import java.nio.file.*;

/** 冻结实际编译的各阶段指导；默认版本和对照版本均由已冻结协议显式指定。 */
class DumpNaturalInstructions {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        for (int index = 1; index < args.length; index++) {
            String version = args[index];
            Files.writeString(output.resolve("enhancement-v" + version + ".txt"),
                    NaturalPromptInstructions.enhancementInstruction(version), StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("planning-v" + version + ".txt"),
                    NaturalPromptInstructions.planningInstruction(version), StandardOpenOption.CREATE_NEW);
        }
        Files.writeString(output.resolve("compiled-default-version.txt"), NaturalPromptInstructions.VERSION,
                StandardOpenOption.CREATE_NEW);
    }
}
