package com.promptoptimizer.enhancement.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.enhancement.service.PlanningSessionStore;
import com.promptoptimizer.enhancement.service.PlanningStoreUnavailableException;
import com.promptoptimizer.identity.support.TestActors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PlanningSessionOwnershipSerializationTest {

    @Test
    @SuppressWarnings("unchecked")
    void requiredRedisModeFailsClosedInsteadOfServingLocalCopy() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        HybridPlanningSessionStore store = new HybridPlanningSessionStore(mapper, provider,
                HybridPlanningSessionStore.Mode.REDIS_REQUIRED);
        assertThatThrownBy(() -> store.savePlan(plan())).isInstanceOf(PlanningStoreUnavailableException.class);
        assertThatThrownBy(() -> store.findPlan("plan-test")).isInstanceOf(PlanningStoreUnavailableException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiredRedisModeDoesNotUseStaleLocalCopyWhenRedisFailsAfterSave() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(values);
        HybridPlanningSessionStore store = new HybridPlanningSessionStore(mapper, provider,
                HybridPlanningSessionStore.Mode.REDIS_REQUIRED);
        store.savePlan(plan());
        when(values.get(anyString())).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> store.findPlan("plan-test"))
                .isInstanceOf(PlanningStoreUnavailableException.class)
                .hasMessageNotContaining("storage unavailable");
    }
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsWithoutOwnerCannotBeDeserialized() throws Exception {
        PlanningSessionStore.PlanSession plan = plan();
        ObjectNode json = mapper.valueToTree(plan);
        json.remove("ownerUserId");
        assertThatThrownBy(() -> mapper.treeToValue(json, PlanningSessionStore.PlanSession.class))
                .hasMessageContaining("ownerUserId must not be null");
        ObjectNode context = mapper.valueToTree(context());
        context.remove("ownerUserId");
        assertThatThrownBy(() -> mapper.treeToValue(context, PlanningSessionStore.ContextSession.class))
                .hasMessageContaining("ownerUserId must not be null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerSurvivesRedisRoundTripAcrossStoreInstances() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        Map<String, String> data = new HashMap<>();
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        HybridPlanningSessionStore writer = new HybridPlanningSessionStore(mapper, provider);
        writer.savePlan(plan());
        writer.saveContext(context());
        HybridPlanningSessionStore reader = new HybridPlanningSessionStore(mapper, provider);
        assertThat(reader.findPlan("plan-test").orElseThrow().ownerUserId()).isEqualTo(TestActors.USER_ID);
        assertThat(reader.findContext("context-test").orElseThrow().ownerUserId()).isEqualTo(TestActors.USER_ID);
        assertThat(data.keySet()).containsExactlyInAnyOrder(
                "prompt-optimizer:plan:v2:plan-test", "prompt-optimizer:planning-context:v2:context-test");
    }

    private PlanningSessionStore.PlanSession plan() {
        return new PlanningSessionStore.PlanSession("plan-test", TestActors.USER_ID, "fingerprint", null,
                List.of(), Instant.now().plusSeconds(60));
    }

    private PlanningSessionStore.ContextSession context() {
        ContextSnapshot snapshot = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "test");
        return new PlanningSessionStore.ContextSession(new PlanningContextReference("context-test", "v1"),
                TestActors.USER_ID, "fingerprint", null, snapshot, Instant.now().plusSeconds(60));
    }
}
