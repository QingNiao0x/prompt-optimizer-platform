import java.nio.file.*;
import com.promptoptimizer.provider.domain.NaturalPromptFidelityInstructions;
/** Freeze both compiled stage instructions without a model call. */
class DumpFidelityInstructions {
 public static void main(String[] a) throws Exception { Files.writeString(Path.of(a[0]),NaturalPromptFidelityInstructions.GROUNDED_DETECT);Files.writeString(Path.of(a[1]),NaturalPromptFidelityInstructions.GROUNDED_PATCH); }
}
