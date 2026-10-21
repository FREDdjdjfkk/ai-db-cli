package io.dbx;

final class Failure extends RuntimeException {
    final int exit;
    final String code;
    Failure(int exit, String code, String message) { super(message); this.exit = exit; this.code = code; }
    static Failure usage(String message) { return new Failure(2, "INVALID_INPUT", message); }
    static Failure denied(String message) { return new Failure(3, "PERMISSION_DENIED", message); }
}
