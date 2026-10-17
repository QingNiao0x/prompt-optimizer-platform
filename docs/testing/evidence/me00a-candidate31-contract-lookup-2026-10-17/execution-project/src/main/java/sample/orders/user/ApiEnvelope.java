package sample.orders.user;
public record ApiEnvelope<T>(String code, T data, String message) {}
