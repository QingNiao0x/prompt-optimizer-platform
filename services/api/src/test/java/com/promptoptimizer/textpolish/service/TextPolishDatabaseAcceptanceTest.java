package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRouteProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.textpolish.controller.TextPolishController;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.mapper.TextPolishMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用固定合成账号与真实PostgreSQL验证XML、CAS、分页、归属、逻辑删除和数据库注释；全部写入回滚。 */
@SpringBootTest(properties = {"app.provider.mode=mock", "spring.flyway.enabled=false", "app.history.enabled=true",
        "app.security.bootstrap-admin.enabled=false", "app.security.bootstrap-user.password=",
        "app.provider.concurrency.store-mode=MEMORY", "app.image-prompt.store-mode=MEMORY", "app.text-polish.store-mode=MEMORY"})
@Transactional
class TextPolishDatabaseAcceptanceTest {
    @Autowired TextPolishHistoryService history;
    @Autowired TextPolishMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TextPolishStateStore states;
    private ActorIdentity actor;
    @BeforeEach void canonicalActor() throws Exception {
        var account = json.readTree(Files.readString(Path.of("../../tmp/local-acceptance/account.json")));
        actor = new ActorIdentity(UUID.fromString(account.path("userId").asText()),UUID.fromString(account.path("tenantId").asText()),
                UUID.fromString(account.path("workspaceId").asText()),"acceptance@local","Canonical acceptance");
    }
    private Document document() {
        var input = new Input("最多3次，保护原句。", Style.NATURAL, Depth.LIGHT,"","",List.of("保护原句。"),"fixture");
        var prepared = TextPolishSkills.prepare(input, TextPolishSkills.AUTO_VERSION);
        var execution = new ExecutionMetadata(TextPolishSkills.AUTO_VERSION, prepared.resources().getLast().version(), "system-hash",
                null, "fixture", "fixture", null, 1, prepared.selection(), prepared.resources());
        return new Document(UUID.randomUUID(),input,"最多3次，保护原句。","最多3次，保护原句。",null,SourceMode.RAW,"fixture","受控模型",true,0,List.of(),false,true,Instant.now(), execution);
    }
    @Test void jsonSnapshotCurrentDraftAndCasRemainConsistentAfterDeletion() {
        var saved = history.save(actor, document());
        var edited = history.edit(actor, saved.id(), "当前最多3次，保护原句。",0);
        assertThat(history.find(actor, saved.id()).executionMetadata()).isEqualTo(saved.executionMetadata());
        assertThat(edited.generatedText()).isEqualTo(saved.generatedText()); assertThat(edited.editRevision()).isEqualTo(1);
        assertThatThrownBy(() -> history.edit(actor,saved.id(),"旧稿，保护原句。",0)).isInstanceOf(TextPolishException.class);
        assertThat(history.save(actor,saved).polishedText()).isEqualTo(edited.polishedText());
        assertThat(history.list(actor,1,10).records()).extracting(Document::id).contains(saved.id());
        history.delete(actor,saved.id());
        assertThat(mapper.find(saved.id(),actor)).isNull();
        assertThatThrownBy(() -> history.find(actor,saved.id())).isInstanceOf(TextPolishException.class);
        assertThatThrownBy(() -> history.edit(actor,saved.id(),"保护原句。",1)).isInstanceOf(TextPolishException.class);
        assertThatThrownBy(() -> history.save(actor,saved)).isInstanceOf(TextPolishException.class);
        assertThat(history.list(actor,1,10).records()).extracting(Document::id).doesNotContain(saved.id());
    }
    @Test void everyScopeIsAppliedInXmlAndModulePolicyWritesAtomically() {
        var saved = history.save(actor,document());
        for(var other:List.of(new ActorIdentity(UUID.randomUUID(),actor.tenantId(),actor.workspaceId(),"x@local","x"),
                new ActorIdentity(actor.userId(),UUID.randomUUID(),actor.workspaceId(),"x@local","x"),
                new ActorIdentity(actor.userId(),actor.tenantId(),UUID.randomUUID(),"x@local","x"))) {
            assertThat(mapper.find(saved.id(),other)).isNull(); assertThat(mapper.edit(saved.id(),other,0,"越权稿")).isZero();
            assertThat(mapper.delete(saved.id(),other)).isZero(); assertThat(history.list(other,1,10).total()).isZero();
        }
        assertThat(mapper.setPolicy("[]",null)).isEqualTo(1);
        assertThat(mapper.policy()).contains("enabledModelIds", "defaultModelId");
    }

    /** 从工作台使用的生成和历史接口验证试用策略，受控上游响应不产生模型费用。 */
    @Test void workbenchRequestSavesScopedStrategyAndActualBody() throws Exception {
        String endpoint = "https://fixture.invalid/chat/completions";
        var route = new OpenAiCompatibleRouteProperties();
        route.setEndpoint(URI.create(endpoint));
        route.setApiKey("fixture-key");
        route.setModel("deepseek-flash");
        var properties = new OpenAiCompatibleProperties();
        properties.setMultiProviderEnabled(true);
        properties.setProviders(Map.of("deepseek", route));
        properties.setTemperature(0.2);
        properties.setMaxTokens(8000);
        properties.setJsonResponseFormatEnabled(true);
        var rest = RestClient.builder();
        var upstream = MockRestServiceServer.bindTo(rest).build();
        var fidelity = new TextPolishFidelity();
        var provider = new OpenAiTextPolishProvider(rest.build(), json, properties, fidelity);
        provider.configureParagraphV2EnglishRestructure(true);

        var identity = mock(CurrentActor.class);
        when(identity.require()).thenReturn(actor);
        var models = mock(TextPolishModelService.class);
        String modelId = "deepseek:deepseek-flash";
        when(models.require(modelId)).thenReturn(new ModelEntry(UUID.randomUUID(), modelId, "deepseek",
                "deepseek-flash", "受控文章模型", true, true, 0));
        var service = new TextPolishService(identity, models, provider, history, states, fidelity, json,
                mock(AnalyticsEventService.class));
        var mvc = MockMvcBuilders.standaloneSetup(new TextPolishController(service, history, identity))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        var input = new Input("We plan to send the revised guide to all members on Thursday.", Style.FORMAL,
                Depth.RESTRUCTURE, "", "Put the schedule up front.", List.of(), modelId);
        String output = "On Thursday, we plan to send the revised guide to all members.";
        var content = json.writeValueAsString(Map.of("polishedText", output));
        var envelope = json.writeValueAsString(Map.of("choices",
                List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
        upstream.expect(requestTo(endpoint)).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));

        UUID id = UUID.randomUUID();
        var sent = mvc.perform(post("/api/v1/text-polish").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Generate(id, input, null, null, SourceMode.RAW))))
                .andExpect(status().isOk()).andReturn();
        var loaded = mvc.perform(get("/api/v1/text-polish/history/{id}", id))
                .andExpect(status().isOk()).andReturn();
        var result = json.readTree(sent.getResponse().getContentAsByteArray()).path("data");
        var persisted = json.readTree(loaded.getResponse().getContentAsByteArray()).path("data");
        assertThat(result.path("executionMetadata").path("strategyVersion").asText())
                .isEqualTo(TextPolishInstructions.PARAGRAPH_V2_VERSION);
        assertThat(persisted.path("executionMetadata")).isEqualTo(result.path("executionMetadata"));
        assertThat(persisted.path("polishedText").asText()).isEqualTo(output);
        upstream.verify();
    }
    @Test void allPersistentColumnsAndTablesHaveDatabaseComments() {
        // 检查新模块两张表的实际数据库注释；不使用历史文档冒充已应用迁移证据。
        Integer missing = jdbc.queryForObject("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('text_polish_record','text_polish_model_policy') AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL",Integer.class);
        assertThat(missing).isZero();
        Integer tables = jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('text_polish_record','text_polish_model_policy') AND obj_description(oid,'pg_class') IS NOT NULL",Integer.class);
        assertThat(tables).isEqualTo(2);
    }
}
