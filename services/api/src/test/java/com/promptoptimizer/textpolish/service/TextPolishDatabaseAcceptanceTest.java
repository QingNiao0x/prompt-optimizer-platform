package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.mapper.TextPolishMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

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
    private ActorIdentity actor;
    @BeforeEach void canonicalActor() throws Exception {
        var account = json.readTree(Files.readString(Path.of("../../tmp/local-acceptance/account.json")));
        actor = new ActorIdentity(UUID.fromString(account.path("userId").asText()),UUID.fromString(account.path("tenantId").asText()),
                UUID.fromString(account.path("workspaceId").asText()),"acceptance@local","Canonical acceptance");
    }
    private Document document() {
        var input = new Input("最多3次，保护原句。", Style.NATURAL, Depth.LIGHT,"","",List.of("保护原句。"),"fixture");
        return new Document(UUID.randomUUID(),input,"最多3次，保护原句。","最多3次，保护原句。",null,SourceMode.RAW,"fixture","受控模型",true,0,List.of(),false,true,Instant.now());
    }
    @Test void jsonSnapshotCurrentDraftAndCasRemainConsistentAfterDeletion() {
        var saved = history.save(actor, document());
        var edited = history.edit(actor, saved.id(), "当前最多3次，保护原句。",0);
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
    @Test void allPersistentColumnsAndTablesHaveDatabaseComments() {
        // 检查新模块两张表的实际数据库注释；不使用历史文档冒充已应用迁移证据。
        Integer missing = jdbc.queryForObject("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('text_polish_record','text_polish_model_policy') AND a.attnum>0 AND NOT a.attisdropped AND col_description(c.oid,a.attnum) IS NULL",Integer.class);
        assertThat(missing).isZero();
        Integer tables = jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('text_polish_record','text_polish_model_policy') AND obj_description(oid,'pg_class') IS NOT NULL",Integer.class);
        assertThat(tables).isEqualTo(2);
    }
}
