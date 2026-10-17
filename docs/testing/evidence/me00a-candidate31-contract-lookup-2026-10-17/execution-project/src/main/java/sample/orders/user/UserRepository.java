package sample.orders.user;
import java.util.Optional;
public interface UserRepository { Optional<UserAccount> findByAccountName(String accountName); }
