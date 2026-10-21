package io.dbx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;

class RedisIntegrationTest {
    @TempDir Path dir;
    Process server; Profile p;
    @BeforeEach void start() throws Exception {
        String binary=System.getenv().getOrDefault("REDIS_SERVER","redis-server");
        int port; try (var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
        try { server=new ProcessBuilder(binary,"--bind","127.0.0.1","--port",String.valueOf(port),"--save","","--appendonly","no","--dir",dir.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("redis.log").toFile()).start(); }
        catch (java.io.IOException e) { assumeTrue(false,"redis-server not installed; integration test skipped"); }
        p=PolicyTest.dm(); p.type="redis"; p.port=port; p.timeoutSeconds=2; p.keyPrefixes=List.of("dbx-test:");
        long deadline=System.nanoTime()+5_000_000_000L;
        while (System.nanoTime()<deadline) {
            try (var socket=new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1",port),100); return; }
            catch (java.io.IOException e) { Thread.sleep(25); }
        }
        fail("Disposable Redis did not start");
    }
    @AfterEach void stop() throws Exception { if (server!=null) { server.destroy(); if (!server.waitFor(3,java.util.concurrent.TimeUnit.SECONDS)) server.destroyForcibly(); } }
    @Test void realReadWriteAndDenial() {
        assertThrows(Failure.class,()->RedisAdapter.execute(p,"SET",List.of("dbx-test:key","hello"),true));
        p.allowWrites=true; p.redisWriteCommands=List.of("SET","DEL");
        RedisAdapter.execute(p,"SET",List.of("dbx-test:key","hello"),true);
        assertTrue(Main.JSON.toJson(RedisAdapter.execute(p,"GET",List.of("dbx-test:key"),false)).contains("hello"));
        assertThrows(Failure.class,()->RedisAdapter.execute(p,"GET",List.of("other:key"),false));
        assertThrows(Failure.class,()->RedisAdapter.execute(p,"FLUSHALL",List.of(),true));
        RedisAdapter.execute(p,"DEL",List.of("dbx-test:key"),true);
        assertTrue(Main.JSON.toJson(RedisAdapter.execute(p,"GET",List.of("dbx-test:key"),false)).contains("null"));
    }
    @Test void cliRedisArgumentsAndJson() throws Exception {
        p.allowWrites=true; p.redisWriteCommands=List.of("SET");
        Path config=dir.resolve("connections.json");
        new ConfigStore(config).update(data->data.put("test",p));
        var out=new java.io.StringWriter();
        int status=Main.run(new String[]{"--config",config.toString(),"redis","command","-c","test","--allow-write","--","SET","dbx-test:cli","hello"},new java.io.PrintWriter(out));
        assertEquals(0,status,out.toString());
        out.getBuffer().setLength(0);
        status=Main.run(new String[]{"--config",config.toString(),"redis","command","-c","test","--","GET","dbx-test:cli"},new java.io.PrintWriter(out));
        assertEquals(0,status,out.toString());
        var response=com.google.gson.JsonParser.parseString(out.toString()).getAsJsonObject();
        assertEquals("hello",response.getAsJsonObject("data").get("value").getAsString());
    }
}
