package io.dbx;

import picocli.CommandLine;
import picocli.CommandLine.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Command(name="dbx", mixinStandardHelpOptions=true, version="dbx 0.1.0", description="AI-first database CLI. Every response is one JSON document.", subcommands={Main.Connections.class, Main.Sql.class, Main.Schema.class, Main.Redis.class, Main.Capabilities.class})
public final class Main implements Runnable {
    static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    @Option(names="--config", scope=ScopeType.INHERIT, description="Config path (default: DBX_CONFIG or ~/.config/dbx/connections.json)")
    Path config = Path.of(System.getenv().getOrDefault("DBX_CONFIG", Path.of(System.getProperty("user.home"), ".config", "dbx", "connections.json").toString()));
    @Option(names="--timeout", scope=ScopeType.INHERIT, defaultValue="30", description="Overall process deadline in seconds (1..120)") int timeout;
    Object result;
    ConfigStore store() { return new ConfigStore(config); }
    public void run() { throw Failure.usage("Choose a subcommand; use --help or capabilities"); }
    static void bounded(Object data, int maximum) {
        if (JSON.toJson(data).getBytes(StandardCharsets.UTF_8).length > maximum) throw new Failure(6, "RESULT_TOO_LARGE", "Result exceeds configured byte limit; request a smaller range");
    }
    public static void main(String[] args) {
        System.exit(run(args, new PrintWriter(System.out, true)));
    }
    static int run(String[] args, PrintWriter output) {
        var root = new Main(); var help = new StringWriter(); var cli = new CommandLine(root);
        cli.setOut(new PrintWriter(help)).setErr(new PrintWriter(new StringWriter()));
        final Failure[] failure = new Failure[1];
        cli.setParameterExceptionHandler((e, argv) -> { failure[0] = Failure.usage("Invalid command arguments; use --help"); return 2; });
        cli.setExecutionExceptionHandler((e, command, parse) -> {
            failure[0] = e instanceof Failure f ? f : new Failure(4, "BACKEND_ERROR", "Database or local I/O operation failed; check endpoint, credentials and server permissions");
            return failure[0].exit;
        });
        int status;
        try {
            cli.parseArgs(args);
            if (root.timeout < 1 || root.timeout > 120) throw Failure.usage("timeout must be 1..120 seconds");
            // execute() reparses and resets option fields on the worker; snapshot before starting it.
            final int deadlineSeconds = root.timeout;
            var executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "dbx-command"); t.setDaemon(true); return t; });
            try { status = executor.submit(() -> cli.execute(args)).get(deadlineSeconds, TimeUnit.SECONDS); }
            catch (TimeoutException e) { failure[0] = new Failure(5, "TIMEOUT", "Process deadline exceeded; for writes, verify server state before retrying"); status = 5; }
            finally { executor.shutdownNow(); }
        } catch (Failure f) { failure[0] = f; status = f.exit; }
        catch (ParameterException e) { failure[0] = Failure.usage("Invalid command arguments; use --help"); status = 2; }
        catch (Exception e) { failure[0] = new Failure(4, "INTERNAL_ERROR", "Command execution failed"); status = 4; }
        var envelope = new LinkedHashMap<String, Object>(); envelope.put("version", "0.1.0"); envelope.put("ok", status == 0);
        if (status == 0) envelope.put("data", help.getBuffer().isEmpty() ? root.result : Map.of("help", help.toString()));
        else { Failure f = failure[0]; envelope.put("error", Map.of("code", f == null ? "INTERNAL_ERROR" : f.code, "message", f == null ? "Command failed" : f.getMessage())); }
        output.println(JSON.toJson(envelope)); output.flush(); return status;
    }
    static abstract class Group implements Runnable {
        @ParentCommand Main root;
        public void run() { throw Failure.usage("Choose a subcommand; use --help"); }
    }
    static abstract class Connected implements Callable<Integer> {
        @Option(names={"-c", "--connection"}, required=true) String connection;
    }
    @Command(name="connections", mixinStandardHelpOptions=true, subcommands={ListConnections.class, AddConnection.class, RemoveConnection.class, ConnectionStatus.class, SavePassword.class})
    static final class Connections extends Group {}
    @Command(name="list", mixinStandardHelpOptions=true)
    static final class ListConnections implements Callable<Integer> {
        @ParentCommand Connections parent;
        public Integer call() throws Exception { parent.root.result = parent.root.store().read(); return 0; }
    }
    @Command(name="status", mixinStandardHelpOptions=true, description="Show configuration location and credential availability without revealing passwords.")
    static final class ConnectionStatus implements Callable<Integer> {
        @ParentCommand Connections parent;
        public Integer call() throws Exception {
            var root=parent.root; var store=root.store(); var items=new ArrayList<Object>();
            for (var entry:store.read().entrySet()) {
                Profile p=entry.getValue(); var item=new LinkedHashMap<String,Object>();
                String env=p.passwordEnv == null ? null : System.getenv(p.passwordEnv);
                String source="not_configured"; boolean available=false; String keychainStatus=null;
                if (env!=null && !env.isEmpty()) { source="environment"; available=true; }
                else if (p.passwordKeychain!=null) {
                    source="keychain";
                    try { available=new Keychain().contains(p.passwordKeychain); keychainStatus=available ? "record_found" : "access_required_or_missing"; }
                    catch (Failure f) { keychainStatus="unavailable"; }
                } else if (p.passwordEnv!=null) source="environment_missing";
                item.put("name",entry.getKey()); item.put("type",p.type); item.put("readOnly",!p.allowWrites);
                item.put("credentialSource",source); item.put("passwordAvailable",available);
                if (keychainStatus!=null) item.put("keychainStatus",keychainStatus);
                if (p.passwordKeychain!=null && !available && source.equals("keychain"))
                    item.put("nextAction","Check login keychain and execution permissions before asking for a password or replacing saved credentials");
                items.add(item);
            }
            Path defaultPath=Path.of(System.getProperty("user.home"),".config","dbx","connections.json").toAbsolutePath().normalize();
            Path selected=root.config.toAbsolutePath().normalize();
            root.result=Map.of("configPath",selected.toString(),"defaultConfigPath",defaultPath.toString(),
                    "usingDefaultConfig",selected.equals(defaultPath),"configExists",Files.exists(selected),"connections",items);
            return 0;
        }
    }
    @Command(name="save-password", mixinStandardHelpOptions=true, description="Save a password to macOS Keychain once for use in future sessions.")
    static final class SavePassword extends Connected {
        @ParentCommand Connections parent;
        @Option(names="--from-env", description="Read the password from this environment variable") String fromEnv;
        @Option(names="--password-stdin", description="Read a single-line password from stdin (never from argv)") boolean stdin;
        public Integer call() throws Exception {
            if (fromEnv!=null && stdin) throw Failure.usage("Choose --from-env or --password-stdin");
            // Validate the profile before reading a password or making keychain changes.
            var store=parent.root.store(); store.get(connection);
            String password;
            if (fromEnv!=null) {
                if (!fromEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) throw Failure.usage("Invalid environment variable name");
                password=System.getenv(fromEnv);
            } else if (stdin) {
                byte[] bytes=System.in.readNBytes(65537);
                if (bytes.length>65536) throw Failure.usage("Password input exceeds 64 KiB");
                password=new String(bytes,StandardCharsets.UTF_8);
                if (password.endsWith("\n")) { password=password.substring(0,password.length()-1); if (password.endsWith("\r")) password=password.substring(0,password.length()-1); }
            } else {
                var console=System.console();
                if (console==null) throw Failure.usage("Noninteractive use requires --from-env <name> or --password-stdin");
                System.err.print("Database password: ");
                char[] entered=console.readPassword(); System.err.println();
                password=entered==null ? null : new String(entered);
                if (entered!=null) Arrays.fill(entered,'\0');
            }
            if (password==null || password.isEmpty()) throw new Failure(2,"MISSING_SECRET","Password input or selected environment variable is empty");
            String value=password;
            store.update(data -> {
                Profile p=data.get(connection);
                if (p==null) throw new Failure(2,"CONNECTION_NOT_FOUND","Connection no longer exists");
                String reference=p.passwordKeychain==null ? UUID.randomUUID().toString() : p.passwordKeychain;
                new Keychain().save(reference,value);
                p.passwordKeychain=reference;
            });
            parent.root.result=Map.of("name",connection,"saved",true,"credentialSource","keychain","reusableAcrossSessions",true);
            return 0;
        }
    }
    @Command(name="add", mixinStandardHelpOptions=true, description="Add a named connection. Secrets are environment references only.")
    static final class AddConnection implements Callable<Integer> {
        @ParentCommand Connections parent;
        @Option(names="--name", required=true) String name;
        @Option(names="--type", required=true) String type;
        @Option(names="--host", defaultValue="127.0.0.1") String host;
        @Option(names="--port") Integer port;
        @Option(names="--user") String user;
        @Option(names="--schema", description="Default SQL schema") String schema;
        @Option(names="--password-env") String passwordEnv;
        @Option(names="--database", defaultValue="0") int database;
        @Option(names="--tls") boolean tls;
        @Option(names="--enable-writes") boolean writes;
        @Option(names="--read-table") List<String> readTables = new ArrayList<>();
        @Option(names="--write-table") List<String> writeTables = new ArrayList<>();
        @Option(names="--key-prefix") List<String> prefixes = new ArrayList<>();
        @Option(names="--redis-write-command") List<String> writeCommands = new ArrayList<>();
        @Option(names="--max-rows", defaultValue="100") int maxRows;
        @Option(names="--timeout-seconds", defaultValue="10") int seconds;
        @Option(names="--max-bytes", defaultValue="1048576") int maxBytes;
        public Integer call() throws Exception {
            ConfigStore.validateName(name);
            var p = new Profile(); p.type=type; p.host=host; p.port=port == null ? ("dm".equals(type) ? 5236 : 6379) : port;
            p.user=user; p.schema=schema; p.passwordEnv=passwordEnv; p.database=database; p.tls=tls; p.allowWrites=writes;
            p.readTables=readTables; p.writeTables=writeTables; p.keyPrefixes=prefixes; p.redisWriteCommands=writeCommands;
            p.maxRows=maxRows; p.timeoutSeconds=seconds; p.maxBytes=maxBytes; p.validate();
            if ("dm".equals(type) && tls) throw Failure.usage("DM TLS is not supported in this release");
            parent.root.store().update(data -> { if (data.containsKey(name)) throw Failure.usage("Connection already exists; remove it before replacing"); data.put(name, p); });
            parent.root.result=Map.of("name", name, "created", true); return 0;
        }
    }
    @Command(name="remove", mixinStandardHelpOptions=true)
    static final class RemoveConnection implements Callable<Integer> {
        @ParentCommand Connections parent;
        @Option(names="--name", required=true) String name;
        public Integer call() throws Exception {
            parent.root.store().update(data -> { if (data.remove(name) == null) throw Failure.usage("Connection does not exist"); });
            parent.root.result=Map.of("removed", name); return 0;
        }
    }
    @Command(name="sql", mixinStandardHelpOptions=true, subcommands={Query.class, Exec.class})
    static final class Sql extends Group {}
    static abstract class SqlCommand extends Connected {
        @ParentCommand Sql parent;
        @Option(names="--sql") String sql;
        @Option(names="--file", description="UTF-8 SQL file, or - for stdin") String file;
        @Option(names="--params", defaultValue="[]", description="JSON array bound to ? placeholders") String params;
        String text() throws IOException {
            if ((sql == null) == (file == null)) throw Failure.usage("Specify exactly one of --sql or --file");
            if (sql != null) return sql;
            try (InputStream input = file.equals("-") ? System.in : Files.newInputStream(Path.of(file))) {
                byte[] bytes = input.readNBytes(65537); if (bytes.length > 65536) throw Failure.usage("SQL file exceeds 64 KiB");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        JsonArray parameters() {
            try { var array = JsonParser.parseString(params).getAsJsonArray(); if (array.size() > 1000 || params.length() > 65536) throw Failure.usage("Too many SQL parameters"); return array; }
            catch (JsonParseException | IllegalStateException e) { throw Failure.usage("params must be a JSON array"); }
        }
    }
    @Command(name="query", mixinStandardHelpOptions=true)
    static final class Query extends SqlCommand {
        @Option(names="--limit") Integer limit;
        public Integer call() throws Exception {
            Profile p=parent.root.store().get(connection); String statement=text(); var args=parameters();
            int rows=limit == null ? p.maxRows : limit;
            if (rows < 1 || rows > p.maxRows) throw Failure.usage("limit must be within the connection row limit");
            SqlPolicy.check(statement,p,false,false);
            try (var c=SqlAdapter.open(p)) { parent.root.result=SqlAdapter.query(c,p,statement,args,rows); } return 0;
        }
    }
    @Command(name="exec", mixinStandardHelpOptions=true)
    static final class Exec extends SqlCommand {
        @Option(names="--allow-write") boolean consent;
        public Integer call() throws Exception {
            Profile p=parent.root.store().get(connection); String statement=text(); var args=parameters(); SqlPolicy.check(statement,p,true,consent);
            try (var c=SqlAdapter.open(p)) { parent.root.result=SqlAdapter.exec(c,p,statement,args,consent); } return 0;
        }
    }
    @Command(name="schema", mixinStandardHelpOptions=true, subcommands={Tables.class, Describe.class})
    static final class Schema extends Group {}
    @Command(name="tables", mixinStandardHelpOptions=true)
    static final class Tables extends Connected {
        @ParentCommand Schema parent;
        @Option(names="--schema") String schema;
        public Integer call() throws Exception {
            Profile p=parent.root.store().get(connection);
            try (var c=SqlAdapter.open(p)) { parent.root.result=SqlAdapter.tables(c,p,schema); } return 0;
        }
    }
    @Command(name="describe", mixinStandardHelpOptions=true)
    static final class Describe extends Connected {
        @ParentCommand Schema parent;
        @Option(names="--schema") String schema;
        @Option(names="--table", required=true) String table;
        public Integer call() throws Exception {
            Profile p=parent.root.store().get(connection);
            try (var c=SqlAdapter.open(p)) { parent.root.result=SqlAdapter.describe(c,p,schema,table); } return 0;
        }
    }
    @Command(name="redis", mixinStandardHelpOptions=true, subcommands={RedisCommand.class})
    static final class Redis extends Group {}
    @Command(name="command", mixinStandardHelpOptions=true, description="Run an allowlisted Redis command. Use -- before command arguments.")
    static final class RedisCommand extends Connected {
        @ParentCommand Redis parent;
        @Option(names="--allow-write") boolean consent;
        @Parameters(index="0") String command;
        @Parameters(index="1..*", arity="0..*") List<String> args = new ArrayList<>();
        public Integer call() throws Exception {
            Profile p=parent.root.store().get(connection); parent.root.result=RedisAdapter.execute(p,command,args,consent); return 0;
        }
    }
    @Command(name="capabilities", mixinStandardHelpOptions=true)
    static final class Capabilities implements Runnable {
        @ParentCommand Main root;
        public void run() { root.result=Map.of("sources",List.of("dm","redis"),"redisReadCommands", new TreeSet<>(RedisAdapter.READS),"redisWriteCommands",new TreeSet<>(RedisAdapter.WRITES),"sqlFunctions",new TreeSet<>(SqlPolicy.FUNCTIONS),"defaultReadOnly",true,"parameterStyle","?","exitCodes",Map.of("0","success","2","invalid input/config","3","permission denied","4","backend or I/O error","5","timeout; write outcome may be unknown","6","result too large")); }
    }
}
