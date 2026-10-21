package io.dbx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

class CliTest {
    @TempDir Path dir;
    record Response(int status, JsonObject json, String output) {}
    Response run(String... args) {
        var out=new StringWriter(); var argv=new ArrayList<>(List.of("--config",dir.resolve("connections.json").toString())); argv.addAll(List.of(args));
        int status=Main.run(argv.toArray(String[]::new),new PrintWriter(out));
        assertEquals(1,out.toString().lines().count());
        return new Response(status,JsonParser.parseString(out.toString()).getAsJsonObject(),out.toString());
    }
    @Test void configRoundtripAndPermissions() throws Exception {
        assertEquals(0,run("connections","add","--name","dev","--type","dm","--password-env","DBX_TEST_PASSWORD").status);
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(dir.resolve("connections.json")));
        var result=run("connections","list"); assertTrue(result.json.getAsJsonObject("data").has("dev"));
        assertEquals(2,run("connections","add","--name","dev","--type","dm").status);
        assertEquals(0,run("connections","remove","--name","dev").status);
    }
    @Test void helpAndErrorsAreJsonWithoutEchoingInput() {
        assertEquals(0,run("--help").status);
        var result=run("--password=do-not-print-me");
        assertEquals(2,result.status); assertFalse(result.output.contains("do-not-print-me"));
        assertEquals(2,run("--timeout","0","capabilities").status);
    }
    @Test void deniesWritesBeforeOpeningNetwork() {
        run("connections","add","--name","dev","--type","dm");
        assertEquals(3,run("sql","exec","-c","dev","--sql","delete from users where id=1","--allow-write").status);
        assertEquals(3,run("sql","query","-c","dev","--sql","drop table users").status);
    }
    @Test void invalidConfigFailsClosed() throws Exception {
        Files.writeString(dir.resolve("connections.json"),"{broken"); assertEquals(2,run("connections","list").status);
        Files.writeString(dir.resolve("connections.json"),"null"); assertEquals(2,run("connections","list").status);
    }
}
