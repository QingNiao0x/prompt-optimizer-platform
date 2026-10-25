import java.nio.file.*;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** 仅解析本次显式演示配置，不启动应用或访问凭据。 */
class CheckPolishProfile {
    public static void main(String[] args) throws Exception {
        Map<?, ?> document = new Yaml().load(Files.readString(Path.of(args[0])));
        Map<?, ?> app = (Map<?, ?>) document.get("app");
        Map<?, ?> polish = (Map<?, ?>) app.get("text-polish");
        if (!"MEMORY".equals(polish.get("store-mode"))) throw new IllegalStateException("Local mock must be explicit");
        System.out.println("LOCAL_MOCK_YAML_VALID: text-polish.store-mode=MEMORY");
    }
}
