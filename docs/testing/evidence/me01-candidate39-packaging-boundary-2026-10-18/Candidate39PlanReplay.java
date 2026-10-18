package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.enhancement.service.impl.*;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用同一413原件比较冻结父/新Plan服务；仅使用内存会话和受控身份夹具，不连接业务数据或模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class Candidate39PlanReplay {
    /** 输出限定在本节点目录，每次独占创建；当前/父类路径必须与显式参数一致。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path out = root.resolve(args[0]).normalize();
        if (!out.startsWith(root.resolve("tmp/me04-candidate39-20261018"))) throw new IllegalArgumentException("Unexpected output path");
        Path runtime = Path.of(OptimizationPlanningServiceImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!runtime.equals(root.resolve(args[1]).normalize())) throw new IllegalArgumentException("Unexpected runtime");
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(Files.readString(root.resolve("services/api/src/test/resources/fixtures/candidate39-planning-packaging-replay.json")));
        String responseText = fixture.path("modelResponseRaw").asText();
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(responseText.getBytes(StandardCharsets.UTF_8)))
                .equals(fixture.path("modelResponseSha256").asText())) throw new IllegalStateException("Original response drift");
        var original = mapper.readTree(responseText);
        var questions = mapper.convertValue(original.path("questions"), new TypeReference<List<PlanQuestion>>() { });
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
        var observations = new ArrayList<Map<String, Object>>();
        for (boolean prepare : List.of(false, true)) {
            String raw = fixture.path("rawPrompt").asText();
            var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock),
                    request -> new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY"),
                    new ProtectedContextFilterImpl(), TestActors.currentActor(), clock);
            var calls = new AtomicInteger();
            var service = new OptimizationPlanningServiceImpl(request -> {
                calls.incrementAndGet();
                return new PlanningProviderResponse(original.path("summary").asText(), questions, "mock", "retained413", true);
            }, new PromptTemplateRegistryImpl(), sessions, clock);
            PlanningContextReference reference = null;
            if (prepare) {
                var context = sessions.prepareContext(new PlanningContextRequest(raw, new ContextAnalysisRequest("", List.of()), PermissionPolicyInput.empty()));
                reference = new PlanningContextReference(context.contextId(), context.version());
            }
            var plan = service.plan(new OptimizationPlanRequest(raw, "", List.of(), reference));
            if (calls.get() != 1 || !Objects.equals(plan.planningContext(), reference)) throw new IllegalStateException("Replay binding drift");
            observations.add(Map.of("preparedEmptyContext", prepare, "summary", plan.summary(), "questions", plan.questions(),
                    "planRegistered", plan.planId() != null, "contextReferenceMatches", true, "template", plan.templateCode(),
                    "providerFixtureInvocations", calls.get(), "realModelCalls", 0));
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("plan-replay.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "runtime", runtime.toString(), "sourceTrace", 413, "sourceResponseSha256", fixture.path("modelResponseSha256").asText(),
                "observations", observations, "persistentAccountsCreated", 0, "realModelCalls", 0)), StandardOpenOption.CREATE_NEW);
        System.out.println("Offline service replay entries=" + observations.size() + "; realModelCalls=0");
    }
}
