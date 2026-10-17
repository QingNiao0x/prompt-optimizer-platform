package sample.orders.user;
public interface PasswordVerifier { boolean matches(String rawPassword, String passwordHash); }
