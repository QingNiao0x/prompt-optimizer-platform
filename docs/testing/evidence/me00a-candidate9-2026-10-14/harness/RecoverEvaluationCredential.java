import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import org.springframework.boot.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import java.util.*;
/** Rotate only this run's synthetic fixture after its stdin closed before login; never create another account. */
public class RecoverEvaluationCredential {
 public static void main(String[] args)throws Exception{
  Path root=Path.of("tmp/local-acceptance");
  var account=new ObjectMapper().readTree(root.resolve("account.json").toFile());
  if(!"LOCAL_SYNTHETIC_FIXTURE".equals(account.path("kind").asText()) || !account.path("identifier").asText().equals(System.getenv("PROMPT_OPTIMIZER_ACCEPTANCE_IDENTIFIER")))throw new IllegalStateException("Fixture mismatch");
  var env=new StandardEnvironment();
  for(var s:new YamlPropertySourceLoader().load("fixture",new FileSystemResource("services/api/src/main/resources/application.yml")))env.getPropertySources().addLast(s);
  if(!env.getProperty("spring.datasource.url","").matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):\\d+/[a-zA-Z0-9_-]+"))throw new IllegalStateException("Local only");
  var app=new SpringApplication(LocalAcceptanceAccountProvisioner.LocalConfiguration.class);app.setWebApplicationType(WebApplicationType.NONE);app.setEnvironment(env);
  try(var ctx=app.run("--spring.flyway.enabled=false","--spring.main.banner-mode=off","--logging.level.root=WARN")){
   var mapper=ctx.getBean(UserAccountMapper.class);UUID id=UUID.fromString(account.path("userId").asText());
   var entity=mapper.selectById(id);
   if(entity==null || !entity.getTenantId().toString().equals(account.path("tenantId").asText()))throw new IllegalStateException("Wrong fixture tenant");
   if(!"本地业务验收".equals(entity.getDisplayName()) || entity.getEmail()!=null
       || !"USER".equals(entity.getPlatformRole()) || !"ACTIVE".equals(entity.getStatus()))throw new IllegalStateException("Not the active synthetic fixture");
   if(mapper.updatePasswordHash(id,new BCryptPasswordEncoder(12).encode(System.getenv("PROMPT_OPTIMIZER_ACCEPTANCE_PASSWORD")))!=1)throw new IllegalStateException("Rotation failed");
   Files.writeString(root.resolve("fixture-credential-recovery-"+System.currentTimeMillis()+".json"),"{\"sameAccount\":true,\"reason\":\"evaluation browser login setup retry\",\"newAccountsCreated\":0,\"normalCaptchaLoginRequired\":true}",StandardOpenOption.CREATE_NEW);
  }
 }
}
