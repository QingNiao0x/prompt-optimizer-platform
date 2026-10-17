package sample.orders.user;
import org.springframework.web.bind.annotation.*;
@RestController
public class LegacyLoginController {
    @GetMapping("/legacy/session")
    public String session() { return "legacy-entry"; }
}
