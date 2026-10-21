package io.dbx;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import com.google.gson.*;

class SqlIntegrationTest {
    Connection c; Profile p;
    @BeforeEach void setup() throws Exception {
        c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()); p=PolicyTest.dm();
        c.createStatement().execute("create table users(id bigint, name varchar(100), amount decimal(30,2))");
        c.createStatement().execute("insert into users values(1,'alpha',12345678901234567890.12),(2,'beta',null),(3,'gamma',null)");
    }
    @AfterEach void close() throws Exception { c.close(); }
    @Test void parameterBindingAndExactNumbers() throws Exception {
        Object result=SqlAdapter.query(c,p,"select id,name,amount from users where id=?",JsonParser.parseString("[1]").getAsJsonArray(),10);
        var json=Main.JSON.toJsonTree(result).getAsJsonObject();
        var row=json.getAsJsonArray("rows").get(0).getAsJsonArray();
        assertEquals("12345678901234567890.12",row.get(2).getAsString()); assertEquals(1,json.get("rowCount").getAsInt());
    }
    @Test void truncationIsExplicit() throws Exception {
        var result=Main.JSON.toJsonTree(SqlAdapter.query(c,p,"select * from users order by id",new JsonArray(),2)).getAsJsonObject();
        assertTrue(result.get("truncated").getAsBoolean()); assertEquals(2,result.get("rowCount").getAsInt());
    }
    @Test void writeCommitsAndExcessRollsBack() throws Exception {
        p.allowWrites=true; p.writeTables=List.of("USERS"); p.maxRows=1;
        SqlAdapter.exec(c,p,"update users set name=? where id=?",JsonParser.parseString("[\"ok\",1]").getAsJsonArray(),true);
        assertThrows(Failure.class,()->SqlAdapter.exec(c,p,"update users set name='bad' where id>0",new JsonArray(),true));
        var rs=c.createStatement().executeQuery("select name from users where id=1"); rs.next(); assertEquals("ok",rs.getString(1));
    }
    @Test void schemaDescription() throws Exception {
        assertTrue(Main.JSON.toJson(SqlAdapter.describe(c,p,"PUBLIC","USERS")).contains("AMOUNT"));
        assertTrue(Main.JSON.toJson(SqlAdapter.tables(c,p,"PUBLIC")).contains("USERS"));
    }
}
