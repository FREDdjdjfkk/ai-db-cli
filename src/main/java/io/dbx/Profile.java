package io.dbx;

import java.util.*;

final class Profile {
    String type;
    String host = "127.0.0.1";
    int port;
    String user;
    String schema;
    String passwordEnv;
    String passwordKeychain;
    int database;
    boolean tls;
    boolean allowWrites;
    List<String> readTables = new ArrayList<>();
    List<String> writeTables = new ArrayList<>();
    List<String> keyPrefixes = new ArrayList<>();
    List<String> redisWriteCommands = new ArrayList<>();
    int maxRows = 100;
    int timeoutSeconds = 10;
    int maxBytes = 1048576;

    void validate() {
        if (!Set.of("dm", "redis").contains(Objects.toString(type, ""))) throw Failure.usage("type must be dm or redis");
        if (host == null || !host.matches("[a-zA-Z0-9.:-]+")) throw Failure.usage("Invalid host");
        if (port < 1 || port > 65535 || database < 0) throw Failure.usage("Invalid port or database");
        if (maxRows < 1 || maxRows > 10000 || timeoutSeconds < 1 || timeoutSeconds > 120 || maxBytes < 1024 || maxBytes > 16777216)
            throw Failure.usage("Invalid safety limits (rows 1..10000, timeout 1..120s, bytes 1024..16777216)");
        if (readTables == null || writeTables == null || keyPrefixes == null || redisWriteCommands == null) throw Failure.usage("Policy lists cannot be null");
        if (schema != null && !schema.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw Failure.usage("Invalid schema identifier");
        for (String t : concat(readTables, writeTables)) if (t == null || !t.matches("[a-zA-Z_][a-zA-Z0-9_$]*(\\.[a-zA-Z_][a-zA-Z0-9_$]*)?")) throw Failure.usage("Use unquoted table names in policies");
        if (keyPrefixes.stream().anyMatch(s -> s == null || s.isEmpty())) throw Failure.usage("Key prefixes must be nonempty");
        for (String c : redisWriteCommands) if (c == null || !RedisAdapter.WRITES.contains(c.toUpperCase(Locale.ROOT))) throw Failure.usage("Unsupported Redis write command");
        if (passwordEnv != null && !passwordEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) throw Failure.usage("Invalid password environment variable name");
        if (passwordKeychain != null) Keychain.validateReference(passwordKeychain);
    }
    private static List<String> concat(List<String> a, List<String> b) { var c = new ArrayList<>(a); c.addAll(b); return c; }
    String password() {
        return password(System.getenv(), new Keychain());
    }
    String password(Map<String,String> environment, Keychain keychain) {
        String value = passwordEnv == null ? null : environment.get(passwordEnv);
        if (value != null && !value.isEmpty()) return value;
        if (passwordKeychain != null) return keychain.read(passwordKeychain);
        if (passwordEnv == null) return null;
        throw new Failure(2,"MISSING_SECRET","Password environment variable is unset in this session; save it once with dbx connections save-password -c <name>");
    }
}
