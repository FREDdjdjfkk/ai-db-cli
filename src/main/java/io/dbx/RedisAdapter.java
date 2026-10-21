package io.dbx;

import redis.clients.jedis.*;
import java.util.*;
import java.nio.*;
import java.nio.charset.*;

final class RedisAdapter {
    static final Set<String> READS = Set.of("PING", "GET", "MGET", "EXISTS", "TTL", "PTTL", "TYPE", "STRLEN", "HGET", "HMGET", "HEXISTS", "HLEN", "LLEN", "SCARD", "ZCARD", "GETRANGE", "LRANGE", "ZRANGE");
    static final Set<String> WRITES = Set.of("SET", "DEL", "UNLINK", "EXPIRE", "PEXPIRE", "PERSIST", "HSET", "HDEL", "INCR", "DECR", "SADD", "SREM", "LPUSH", "RPUSH", "LPOP", "RPOP", "ZADD", "ZREM");

    static void validate(Profile p, String command, List<String> args, boolean consent) {
        if (!READS.contains(command) && !WRITES.contains(command)) throw Failure.denied("Redis command is outside the allowlist");
        if (WRITES.contains(command) && (!p.allowWrites || !consent || p.redisWriteCommands.stream().noneMatch(c -> c.equalsIgnoreCase(command))))
            throw Failure.denied("Redis writes require connection permission, --allow-write and command allowlist");
        int n = args.size();
        boolean valid = switch (command) {
            case "PING" -> n == 0;
            case "MGET", "EXISTS", "DEL", "UNLINK" -> n >= 1;
            case "GET", "TTL", "PTTL", "TYPE", "STRLEN", "HLEN", "LLEN", "SCARD", "ZCARD", "PERSIST", "INCR", "DECR", "LPOP", "RPOP" -> n == 1;
            case "HGET", "HEXISTS", "EXPIRE", "PEXPIRE" -> n == 2;
            case "HMGET", "HDEL", "SADD", "SREM", "LPUSH", "RPUSH", "ZREM" -> n >= 2;
            case "HSET", "ZADD" -> n >= 3 && n % 2 == 1;
            case "SET" -> n == 2 || (n == 4 && Set.of("EX", "PX").contains(args.get(2).toUpperCase(Locale.ROOT)));
            case "GETRANGE", "LRANGE", "ZRANGE" -> n == 3;
            default -> false;
        };
        if (!valid || n > p.maxRows) throw Failure.usage("Invalid or excessive Redis arguments");
        if (command.endsWith("RANGE")) {
            try {
                long start = Long.parseLong(args.get(1)), end = Long.parseLong(args.get(2));
                long bound = command.equals("GETRANGE") ? p.maxBytes / 8 : p.maxRows;
                if (start < 0 || end < start || end - start >= bound) throw Failure.usage("Redis ranges must be nonnegative and within result limits");
            } catch (NumberFormatException e) { throw Failure.usage("Redis range indexes must be integers"); }
        }
        if (n > 0) {
            List<String> keys = Set.of("MGET", "EXISTS", "DEL", "UNLINK").contains(command) ? args : args.subList(0, 1);
            if (!p.keyPrefixes.isEmpty()) for (String key : keys)
                if (p.keyPrefixes.stream().noneMatch(key::startsWith)) throw Failure.denied("Redis key is outside the allowed prefixes");
        }
        Main.bounded(args, p.maxBytes);
    }
    static Jedis open(Profile p) {
        if (!"redis".equals(p.type)) throw Failure.usage("Redis commands require a redis connection");
        var tlsParameters = new javax.net.ssl.SSLParameters(); tlsParameters.setEndpointIdentificationAlgorithm("HTTPS");
        var config = DefaultJedisClientConfig.builder().timeoutMillis(p.timeoutSeconds * 1000).database(p.database).ssl(p.tls).sslParameters(tlsParameters);
        if (p.user != null) config.user(p.user);
        String secret = p.password(); if (secret != null) config.password(secret);
        return new Jedis(new HostAndPort(p.host, p.port), config.build());
    }
    static Object execute(Profile p, String command, List<String> args, boolean consent) {
        command = command.toUpperCase(Locale.ROOT); validate(p, command, args, consent);
        try (var jedis = open(p)) {
            Object value = normalize(jedis.sendCommand(Protocol.Command.valueOf(command), args.toArray(String[]::new)));
            var result = new LinkedHashMap<String, Object>(); result.put("command", command); result.put("value", value);
            Main.bounded(result, p.maxBytes); return result;
        }
    }
    static Object normalize(Object value) {
        if (value instanceof byte[] bytes) {
            try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException e) { return Map.of("encoding", "base64", "value", Base64.getEncoder().encodeToString(bytes)); }
        }
        if (value instanceof List<?> list) return list.stream().map(RedisAdapter::normalize).toList();
        return value;
    }
}
