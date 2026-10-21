package io.dbx;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.util.*;

final class SqlPolicy {
    static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "FLOOR", "CEIL", "CEILING", "MOD", "LOWER", "UPPER", "LENGTH", "CHAR_LENGTH", "SUBSTR", "SUBSTRING", "TRIM", "LTRIM", "RTRIM", "REPLACE", "CONCAT", "COALESCE", "NULLIF", "NVL", "NVL2", "TO_CHAR", "TO_DATE", "TO_NUMBER", "ROW_NUMBER", "RANK", "DENSE_RANK", "CAST");
    static boolean tableAllowed(String table, List<String> allowed) {
        return allowed.stream().anyMatch(t -> normalize(t).equals(normalize(table)));
    }
    static String normalize(String s) { return s.replace("\"", "").toUpperCase(Locale.ROOT); }
    static void check(String sql, Profile profile, boolean write, boolean consent) {
        if (sql == null || sql.isBlank() || sql.length() > 65536) throw Failure.usage("SQL must contain 1..65536 characters");
        // Conservatively reject comments, procedural/locking syntax, sequences and vendor escapes.
        String tokens = stripStrings(sql).toUpperCase(Locale.ROOT);
        if (tokens.contains("--") || tokens.contains("/*") || tokens.contains(";") || tokens.contains("\\") || tokens.contains("{") || tokens.contains("}")
                || tokens.matches("(?s).*\\b(FOR|NEXTVAL|CURRVAL|CALL|EXEC|EXECUTE|OUTFILE|DUMPFILE|RETURNING)\\b.*"))
            throw Failure.denied("Comments, multiple statements, locks, sequences and procedural syntax are forbidden");
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!write && !(statement instanceof Select)) throw Failure.denied("query accepts SELECT only");
            if (write) {
                if (!profile.allowWrites || !consent || profile.writeTables.isEmpty()) throw Failure.denied("Writes require connection permission, --allow-write and write-table allowlist");
                String target;
                if (statement instanceof Insert i) {
                    if (i.getConflictAction() != null || i.isUseDuplicate() || i.isOverwrite() || i.getOutputClause() != null)
                        throw Failure.denied("Upserts and vendor insert extensions are forbidden");
                    target = i.getTable().getFullyQualifiedName();
                }
                else if (statement instanceof Update u) {
                    if (u.getWhere() == null) throw Failure.denied("UPDATE requires WHERE");
                    if (u.getFromItem() != null || nonempty(u.getJoins()) || nonempty(u.getStartJoins()) || u.getOutputClause() != null)
                        throw Failure.denied("Only single-table UPDATE is supported");
                    target = u.getTable().getFullyQualifiedName();
                } else if (statement instanceof Delete d) {
                    if (d.getWhere() == null) throw Failure.denied("DELETE requires WHERE");
                    if (nonempty(d.getTables()) || nonempty(d.getUsingList()) || nonempty(d.getJoins()) || d.getOutputClause() != null)
                        throw Failure.denied("Only single-table DELETE is supported");
                    target = d.getTable().getFullyQualifiedName();
                } else throw Failure.denied("exec accepts INSERT, UPDATE or DELETE only");
                if (!tableAllowed(target, profile.writeTables)) throw Failure.denied("Write target is outside the allowlist");
            }
            var visitor = new TablesNamesFinder<Void>() {
                @Override public <S> Void visit(Function f, S context) { checkFunction(f.getName()); return super.visit(f, context); }
                @Override public <S> Void visit(AnalyticExpression f, S context) { checkFunction(f.getName()); return super.visit(f, context); }
                @Override public <S> Void visit(PlainSelect select, S context) {
                    if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()) throw Failure.denied("SELECT INTO is forbidden");
                    return super.visit(select, context);
                }
                @Override public <S> Void visit(TableFunction f, S context) { throw Failure.denied("Table functions are forbidden"); }
                @Override public <S> Void visit(Update u, S context) { if (!write || u != statement) throw Failure.denied("Nested mutation is forbidden"); return super.visit(u, context); }
                @Override public <S> Void visit(Delete d, S context) { if (!write || d != statement) throw Failure.denied("Nested mutation is forbidden"); return super.visit(d, context); }
                @Override public <S> Void visit(Insert i, S context) { if (!write || i != statement) throw Failure.denied("Nested mutation is forbidden"); return super.visit(i, context); }
            };
            for (String table : visitor.getTables(statement)) {
                if (!profile.readTables.isEmpty() && !tableAllowed(table, profile.readTables) && !(write && tableAllowed(table, profile.writeTables)))
                    throw Failure.denied("SQL references a table outside the allowlist");
            }
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw Failure.denied("SQL could not be safely parsed; use supported SQL syntax"); }
    }
    private static void checkFunction(String name) {
        if (!FUNCTIONS.contains(name.toUpperCase(Locale.ROOT))) throw Failure.denied("Function is outside the built-in safe allowlist");
    }
    private static boolean nonempty(List<?> list) { return list != null && !list.isEmpty(); }
    private static String stripStrings(String s) {
        var out = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                if (quoted && i + 1 < s.length() && s.charAt(i + 1) == '\'') { i++; continue; }
                quoted = !quoted; out.append(' ');
            } else out.append(quoted ? ' ' : c);
        }
        if (quoted) throw Failure.usage("Unterminated SQL string");
        return out.toString();
    }
}
