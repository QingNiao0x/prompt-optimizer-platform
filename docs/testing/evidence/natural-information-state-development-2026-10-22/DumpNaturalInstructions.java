import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import java.nio.file.*;
/** 冻结实际编译指导；候选仅改增强指导，Plan继续与第四版逐字相同。 */
class DumpNaturalInstructions {
    public static void main(String[] args) throws Exception {
        if (!NaturalPromptInstructions.VERSION.equals("4")) throw new IllegalStateException("Default changed before adoption");
        if (!NaturalPromptInstructions.planningInstruction("4").equals(NaturalPromptInstructions.planningInstruction("25")))
            throw new IllegalStateException("Plan guidance changed");
        Files.writeString(Path.of(args[0]), NaturalPromptInstructions.enhancementInstruction("4"), StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(args[1]), NaturalPromptInstructions.enhancementInstruction("25"), StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(args[2]), NaturalPromptInstructions.planningInstruction("25"), StandardOpenOption.CREATE_NEW);
    }
}
