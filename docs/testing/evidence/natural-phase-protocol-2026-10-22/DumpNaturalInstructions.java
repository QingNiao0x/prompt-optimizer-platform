import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 冻结运行副本内的分阶段协议及两个单变量框架候选，不读取账号或调用模型。 */
class DumpNaturalInstructions {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !NaturalPromptInstructions.VERSION.equals("4")) {
            throw new IllegalArgumentException("Expected unchanged default version and three output paths");
        }
        Path directory = Path.of(args[0]).getParent();
        for (String version : new String[] {"4", "21", "22", "23"}) {
            Files.writeString(directory.resolve("enhancement-v" + version + ".txt"),
                    NaturalPromptInstructions.enhancementInstruction(version), StandardOpenOption.CREATE_NEW);
        }
        for (String version : new String[] {"4", "21"}) {
            Files.writeString(directory.resolve("planning-v" + version + ".txt"),
                    NaturalPromptInstructions.planningInstruction(version), StandardOpenOption.CREATE_NEW);
        }
    }
}
