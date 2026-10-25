package com.promptoptimizer.context.infrastructure.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 单页多模态传输、明确用量与失败不重试；全部使用本地受控 HTTP 响应。 */
class OpenAiContextVisionProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiContextVisionProvider provider;
    @BeforeEach void setup() {
        var builder=RestClient.builder(); server=MockRestServiceServer.bindTo(builder).build();
        var properties=new OpenAiCompatibleProperties(); properties.setEndpoint(URI.create("https://fixture.invalid/chat/completions"));
        properties.setApiKey("fixture-api-key"); properties.setModel("fixture");
        var route=properties.getConfiguredRoutes().getFirst();
        var catalog=mock(PlatformModelCatalog.class);
        when(catalog.resolve("fixture")).thenReturn(new PlatformModelCatalog.ModelEntry(UUID.randomUUID(),"fixture",route.key(),"fixture","Fixture",true,true,0));
        provider=new OpenAiContextVisionProvider(builder.build(),json,properties,catalog);
    }
    @Test void sendsPixelsSeparatelyAndAuditsActualUsage() throws Exception {
        server.expect(requestTo("https://fixture.invalid/chat/completions")).andExpect(request -> {
            var body=json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString());
            var parts=body.path("messages").get(1).path("content");
            assertThat(parts.get(0).path("text").asText()).doesNotContain("base64");
            assertThat(parts.get(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,AQID");
        }).andRespond(withSuccess(envelope("{\"page\":2,\"text\":\"原始文字 27\",\"tables\":[],\"description\":\"\",\"uncertain\":[],\"complete\":true}"),MediaType.APPLICATION_JSON));
        List<ModelCallObservation> facts=new ArrayList<>();
        try(var context=ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(UUID.randomUUID(),(id,fact)->facts.add(fact)))) {
            assertThat(provider.recognize("fixture",2,new byte[]{1,2,3},8000).text()).isEqualTo("原始文字 27");
        }
        assertThat(facts).singleElement().satisfies(fact->{ assertThat(fact.operation()).isEqualTo("context.vision"); assertThat(fact.usage().totalTokens()).isEqualTo(15); });
        server.verify();
    }
    @Test void wrongPageAndInvalidJsonDoNotTriggerAutomaticRepair() {
        server.expect(requestTo("https://fixture.invalid/chat/completions")).andRespond(withSuccess(envelope("{}"),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->provider.recognize("fixture",1,new byte[]{1},8000)).isInstanceOf(ProviderException.class);
        server.verify();
    }
    @Test void timeoutDoesNotRetryAndDoesNotEchoTransportDetails() {
        server.expect(requestTo("https://fixture.invalid/chat/completions")).andRespond(request->{throw new java.net.SocketTimeoutException("private_transport_detail");});
        assertThatThrownBy(()->provider.recognize("fixture",1,new byte[]{1},8000)).isInstanceOf(ProviderException.class).hasMessageNotContaining("private_transport_detail");
        server.verify();
    }
    @Test void preservesObservedTableMatrixWithoutInventingHeadersOrAmounts() throws Exception {
        try (var resource = getClass().getResourceAsStream("/context/vision-table-matrix.json")) {
            assertThat(resource).isNotNull();
            var result = provider.parse(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), 2);
            assertThat(result.complete()).isTrue();
            assertThat(result.tables()).singleElement().asString()
                    .contains("[\"纸张\",\"12\",\"6.50\"]", "[\"墨盒\",\"3\",\"99.00\"]", "[\"文件夹\",\"8\",\"4.20\"]");
            assertThat(result.text()).contains("不补造总价");
        }
    }
    @Test void keepsMarkdownAndScalarCellValuesButRejectsAmbiguousOrTrailingStructures() {
        String template = "{\"page\":1,\"text\":\"\",\"tables\":%s,\"description\":\"\",\"uncertain\":[],\"complete\":true}";
        assertThat(provider.parse(template.formatted("[\"|项目|金额|\\n|---|---|\\n|A|6.50|\"]"),1).tables())
                .containsExactly("|项目|金额|\n|---|---|\n|A|6.50|");
        assertThat(provider.parse(template.formatted("[[\"A\",6.50,null,true]]"),1).tables().getFirst())
                .contains("[[\"A\",6.5,null,true]]");
        assertThat(provider.parse(template.formatted("[[\"原始精度\",12345678901234567890.123456789]]"),1).tables().getFirst())
                .contains("12345678901234567890.123456789");
        assertThatThrownBy(()->provider.parse(template.formatted("[]").replace("\"page\":1", "\"page\":4294967297"),1))
                .isInstanceOf(ProviderException.class);
        for (String invalid : List.of("[[[\"nested\"]]]", "[[\"A\"],\"mixed\"]", "[[{}]]", "[[]]")) {
            assertThatThrownBy(()->provider.parse(template.formatted(invalid),1)).isInstanceOf(ProviderException.class);
        }
        assertThatThrownBy(()->provider.parse(template.formatted("[]") + " {}",1)).isInstanceOf(ProviderException.class);
        assertThatThrownBy(()->provider.parse(template.formatted("[]").replace("\"page\":1", "\"page\":1,\"page\":1"),1))
                .isInstanceOf(ProviderException.class);
    }
    private String envelope(String content) {
        try { return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content))),"usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15))); }
        catch(Exception exception){throw new AssertionError(exception);}
    }
}
