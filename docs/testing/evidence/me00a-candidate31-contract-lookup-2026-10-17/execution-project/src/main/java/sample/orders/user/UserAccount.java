package sample.orders.user;
public record UserAccount(long id, String accountName, String passwordHash, boolean enabled) {}
