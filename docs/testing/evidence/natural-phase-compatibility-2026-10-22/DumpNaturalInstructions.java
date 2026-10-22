import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 冻结24的运行指导，确认没有通过改正文指导处理解析器的未知字段误拒。 */
class DumpNaturalInstructions {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !NaturalPromptInstructions.VERSION.equals("4")) throw new IllegalArgumentException("Unchanged default required");
        String enhance=NaturalPromptInstructions.enhancementInstruction("24");
        String plan=NaturalPromptInstructions.planningInstruction("24");
        if (!enhance.equals(NaturalPromptInstructions.enhancementInstruction("21"))
                || !plan.equals(NaturalPromptInstructions.planningInstruction("21"))) throw new IllegalStateException("Guidance changed");
        Files.writeString(Path.of(args[0]),enhance,StandardOpenOption.CREATE_NEW);
        Files.writeString(Path.of(args[2]),plan,StandardOpenOption.CREATE_NEW);
    }
}
