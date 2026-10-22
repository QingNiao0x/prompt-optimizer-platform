import java.nio.file.*;
import com.promptoptimizer.provider.domain.NaturalPromptFidelityInstructions;
/** Freeze the actual compiled instruction, without importing credentials or running a model. */
class DumpFidelityInstructions {
 public static void main(String[] args) throws Exception { Files.writeString(Path.of(args[0]), NaturalPromptFidelityInstructions.REVIEW); }
}
