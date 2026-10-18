import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.nio.file.*;
import java.util.*;

/** 仅回放两条无参数旧测试；使用显式冻结父运行，不联网、不修改旧证据或模型指导。 */
public class LegacyContractProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path out = root.resolve(args[0]).normalize();
        Path runtime = Path.of(TaskIntentResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!out.startsWith(root.resolve("tmp/me01-candidate40-20261018")) || !runtime.equals(root.resolve(args[1]).normalize())) throw new IllegalArgumentException("Unbound probe");
        var rows = new ArrayList<Map<String, Object>>();
        for (var entry : Map.of("CandidateNineteenBoundaryTest", "withdrawnGlobalStrategiesDoNotChangeEngineeringOrTranslationApplicability",
                "ReportDraftGuidanceTest", "translationMethodsAndCodeKeepTheirExistingDeliveryProfiles").entrySet()) {
            var type = Class.forName("com.promptoptimizer.enhancement.service.impl." + entry.getKey());
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            var method = type.getDeclaredMethod(entry.getValue()); method.setAccessible(true);
            try { method.invoke(constructor.newInstance()); rows.add(Map.of("test", entry.getKey(), "passed", true)); }
            catch (java.lang.reflect.InvocationTargetException exception) {
                rows.add(Map.of("test", entry.getKey(), "passed", false, "cause", exception.getCause().getClass().getName(), "message", exception.getCause().getMessage()));
            }
        }
        Files.writeString(out, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime", runtime.toString(), "newModelCalls", 0, "observations", rows)), StandardOpenOption.CREATE_NEW);
        System.out.println("Legacy parent probe cases=" + rows.size() + "; newModelCalls=0");
    }
}
