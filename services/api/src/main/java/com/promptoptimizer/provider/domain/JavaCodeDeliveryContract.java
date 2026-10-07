package com.promptoptimizer.provider.domain;

import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 为明确要求Java21实现的任务组织标准库与完整文件交付边界，不推断业务参数或执行生成代码。
 * 仅使用当前需求，翻译资料、方案说明及其他Java版本不能借此获得新的实现要求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class JavaCodeDeliveryContract {
    private static final Pattern JAVA21 = Pattern.compile("(?i)(?<![a-z0-9])java\\s*21(?![0-9])");
    private static final Pattern CODE_TARGET = Pattern.compile("可编译|完整(?:的)?(?:Java\\s*21)?代码|(?:实现|编写)[^。；\\n]{0,80}(?:方法|函数|代码|类)");
    private static final Pattern NO_CODE = Pattern.compile("(?:不|不要|无需|不必|不得)(?:输出|生成|提供|交付|编写|要求)(?:实际|完整)?(?:Java\\s*21)?代码");
    private static final Pattern TEST_TARGET = Pattern.compile("独立[^。；\\n]{0,10}测试类|边界测试|单元测试|测试代码");
    private static final Pattern EXPLICIT_IMPLEMENTATION = Pattern.compile("(?:实现|编写|编程)[^。；\\n]{0,120}(?:代码|方法|函数)");

    private JavaCodeDeliveryContract() { }

    /** 返回可随优化正文复制的指导；编译状态必须由实际验证决定，不能用措辞伪造通过。 */
    public static String guidance(String rawPrompt) {
        String raw = Normalizer.normalize(rawPrompt == null ? "" : rawPrompt, Normalizer.Form.NFKC);
        TaskDeliveryProfile profile = TaskDeliveryProfile.identify(raw);
        // 通用画像也可能有明确的小型代码目标；只接受正向实现语句，不从所述语言或资料推断开发任务。
        boolean implementation = profile == TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION
                || profile == TaskDeliveryProfile.GENERAL && EXPLICIT_IMPLEMENTATION.matcher(raw).find();
        if (!implementation
                || !JAVA21.matcher(raw).find() || !CODE_TARGET.matcher(raw).find() || NO_CODE.matcher(raw).find()) return "";
        String decimal = raw.contains("BigDecimal")
                ? "BigDecimal零值使用BigDecimal.ZERO静态常量，业务数值比较使用compareTo；精度和舍入沿用用户明确规则，不替未指定参数设默认业务值。"
                : "";
        // 测试只有在用户要求时加入；框架、文件包路径和金额业务阈值都由本次输入决定。
        String tests = TEST_TARGET.matcher(raw).find()
                ? "按用户要求的文件清单逐文件交付完整代码，不能只输出实现类而遗漏测试类；只列文件名或验证步骤不等于交付文件。"
                    + "用户要求的独立测试类也完整交付；每个独立文件各自包含import，不能借用实现文件的导入。"
                    + "测试引用实现类的静态方法时使用目标类名限定或合法static import，不把它当作测试类已有的方法。"
                    + "按原定合法与非法输入、边界值及返回精度验证，不强迫改选测试框架。"
                : "";
        return "Java21交付：保留已定类名、方法签名、业务对象及异常类型，使用Java21确实存在的标准库API；"
                + "文件包含必要import和完整类体，不以省略号、片段或口头说明代替实现。" + decimal + tests
                + "实际编译和运行后才能说明检查通过；未执行时如实标明未验证，不伪造结果或新增无关模块。";
    }
}
