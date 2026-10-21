package io.dbx;

import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;

final class SqlAdapter {
    static Connection open(Profile p) throws SQLException {
        if (!"dm".equals(p.type)) throw Failure.usage("SQL commands require a dm connection");
        if (p.tls) throw Failure.usage("DM TLS needs vendor certificate configuration; not supported in this release");
        var properties = new Properties();
        if (p.user != null) properties.setProperty("user", p.user);
        if (p.schema != null) properties.setProperty("schema", p.schema);
        String secret = p.password(); if (secret != null) properties.setProperty("password", secret);
        properties.setProperty("connectTimeout", Integer.toString(p.timeoutSeconds * 1000));
        properties.setProperty("socketTimeout", Integer.toString(p.timeoutSeconds * 1000));
        String host = p.host.contains(":") ? "[" + p.host + "]" : p.host;
        return DriverManager.getConnection("jdbc:dm://" + host + ":" + p.port, properties);
    }
    static Object query(Connection c, Profile p, String sql, JsonArray args, int limit) throws SQLException {
        SqlPolicy.check(sql, p, false, false);
        c.setReadOnly(true); c.setAutoCommit(false);
        try (var s = c.prepareStatement(sql)) {
            s.setQueryTimeout(p.timeoutSeconds); s.setMaxRows(limit + 1); s.setFetchSize(Math.min(limit + 1, 100)); bind(s, args);
            try (var rs = s.executeQuery()) { return rows(rs, limit, p.maxBytes); }
        } finally { c.rollback(); }
    }
    static Object exec(Connection c, Profile p, String sql, JsonArray args, boolean consent) throws SQLException {
        SqlPolicy.check(sql, p, true, consent);
        c.setAutoCommit(false);
        try (var s = c.prepareStatement(sql)) {
            s.setQueryTimeout(p.timeoutSeconds); bind(s, args);
            int count = s.executeUpdate();
            if (count > p.maxRows) throw Failure.denied("Affected-row limit exceeded; transaction rolled back");
            c.commit(); return Map.of("affectedRows", count, "committed", true);
        } catch (RuntimeException | SQLException e) { c.rollback(); throw e; }
    }
    static void bind(PreparedStatement s, JsonArray args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            JsonElement item = args.get(i);
            if (item.isJsonNull()) s.setObject(i + 1, null);
            else if (item.isJsonPrimitive()) {
                JsonPrimitive v = item.getAsJsonPrimitive();
                if (v.isBoolean()) s.setBoolean(i + 1, v.getAsBoolean());
                else if (v.isNumber()) s.setBigDecimal(i + 1, v.getAsBigDecimal());
                else s.setString(i + 1, v.getAsString());
            } else throw Failure.usage("SQL parameters must be JSON scalars");
        }
    }
    static Object rows(ResultSet rs, int limit, int maxBytes) throws SQLException {
        var meta = rs.getMetaData(); var columns = new ArrayList<Object>(); var data = new ArrayList<Object>();
        for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(Map.of("name", meta.getColumnLabel(i), "type", meta.getColumnTypeName(i)));
        int bytes = Main.JSON.toJson(columns).getBytes(StandardCharsets.UTF_8).length; boolean truncated = false;
        while (rs.next()) {
            if (data.size() >= limit) { truncated = true; break; }
            var row = new ArrayList<Object>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                int type = meta.getColumnType(i); Object v;
                if (Set.of(Types.BLOB, Types.CLOB, Types.NCLOB, Types.LONGVARBINARY, Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.SQLXML).contains(type))
                    v = Map.of("omitted", true, "reason", "large_object");
                else {
                    v = rs.getObject(i);
                    if (v instanceof byte[] b) v = Map.of("encoding", "base64", "value", Base64.getEncoder().encodeToString(b));
                    else if (v instanceof Number) v = v.toString(); // preserve exact DECIMAL/BIGINT precision for AI clients
                    else if (v != null && !(v instanceof Boolean) && !(v instanceof String)) v = v.toString();
                }
                row.add(v);
            }
            bytes += Main.JSON.toJson(row).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxBytes) { truncated = true; break; }
            data.add(row);
        }
        return Map.of("columns", columns, "rows", data, "rowCount", data.size(), "truncated", truncated);
    }
    static Object tables(Connection c, Profile p, String schema) throws SQLException {
        schema = effectiveSchema(c, p, schema);
        if (schema != null && !schema.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw Failure.usage("Invalid schema identifier");
        String escape = c.getMetaData().getSearchStringEscape();
        String schemaPattern = schema == null ? null : schema.replace("_", escape + "_");
        var data = new ArrayList<Object>(); boolean truncated = false;
        try (var rs = c.getMetaData().getTables(null, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME"), owner = rs.getString("TABLE_SCHEM");
                if (!metadataAllowed(c, p, owner, name)) continue;
                if (data.size() >= p.maxRows) { truncated = true; break; }
                var item = new LinkedHashMap<String, Object>(); item.put("schema", owner); item.put("name", name); item.put("type", rs.getString("TABLE_TYPE")); data.add(item);
                Main.bounded(data, p.maxBytes);
            }
        }
        return Map.of("tables", data, "truncated", truncated);
    }
    static Object describe(Connection c, Profile p, String schema, String table) throws SQLException {
        schema = effectiveSchema(c, p, schema);
        if (!table.matches("[A-Za-z_][A-Za-z0-9_$]*") || (schema != null && !schema.matches("[A-Za-z_][A-Za-z0-9_$]*"))) throw Failure.usage("Use exact unquoted identifiers");
        if (!metadataAllowed(c, p, schema, table)) throw Failure.denied("Table is outside the allowlist");
        String escape = c.getMetaData().getSearchStringEscape();
        String pattern = table.replace("_", escape + "_");
        String schemaPattern = schema == null ? null : schema.replace("_", escape + "_");
        try (var rs = c.getMetaData().getColumns(null, schemaPattern, pattern, "%")) { return rows(rs, p.maxRows, p.maxBytes); }
    }
    static String effectiveSchema(Connection c, Profile p, String requested) throws SQLException {
        if (requested != null) return requested;
        if (p.schema != null) return p.schema;
        return c.getSchema();
    }
    private static boolean metadataAllowed(Connection c, Profile p, String schema, String table) throws SQLException {
        if (p.readTables.isEmpty()) return true;
        if (SqlPolicy.tableAllowed(schema + "." + table, p.readTables)) return true;
        String defaultSchema=effectiveSchema(c,p,null);
        return defaultSchema != null && schema != null && defaultSchema.equalsIgnoreCase(schema) && SqlPolicy.tableAllowed(table,p.readTables);
    }
}
