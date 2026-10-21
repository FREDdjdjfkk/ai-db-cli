package io.dbx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class PolicyTest {
    static Profile dm() { var p = new Profile(); p.type="dm"; p.port=5236; return p; }
    @ParameterizedTest
    @ValueSource(strings={"delete from users", "select 1; delete from users", "select 1 into users", "select * from users for update", "select dangerous_udf() from users", "select s.nextval from dual", "call something()", "select * from users -- comment", "with x as (delete from users returning *) select * from x", "select * from users where id in (select dangerous_udf() from secret)", "select sys.dangerous_udf() from users", "select count(*) from users union select dangerous_udf() from secret"})
    void deniesDangerousQueries(String sql) { assertThrows(Failure.class, () -> SqlPolicy.check(sql,dm(),false,false)); }
    @ParameterizedTest
    @ValueSource(strings={"select * from users where id = ?", "select count(*) from users", "with x as (select * from users) select * from x", "select 'a; -- b' from users", "select row_number() over (order by id) from users"})
    void permitsQueries(String sql) { assertDoesNotThrow(() -> SqlPolicy.check(sql,dm(),false,false)); }
    @Test void checksSubqueriesAndJoins() {
        var p=dm(); p.readTables=List.of("USERS");
        assertThrows(Failure.class, () -> SqlPolicy.check("select * from users join secret on users.id=secret.id",p,false,false));
        assertThrows(Failure.class, () -> SqlPolicy.check("select * from users where id in (select id from secret)",p,false,false));
        assertDoesNotThrow(() -> SqlPolicy.check("with x as (select * from users) select * from x",p,false,false));
    }
    @Test void writeGates() {
        var p=dm();
        assertThrows(Failure.class, () -> SqlPolicy.check("update users set name=? where id=?",p,true,true));
        p.allowWrites=true; p.writeTables=List.of("USERS");
        assertThrows(Failure.class, () -> SqlPolicy.check("update users set name=? where id=?",p,true,false));
        assertThrows(Failure.class, () -> SqlPolicy.check("update users set name=?",p,true,true));
        assertThrows(Failure.class, () -> SqlPolicy.check("delete from users",p,true,true));
        assertThrows(Failure.class, () -> SqlPolicy.check("drop table users",p,true,true));
        assertThrows(Failure.class, () -> SqlPolicy.check("update secret set name=? where id=?",p,true,true));
        assertThrows(Failure.class, () -> SqlPolicy.check("update users join secret on users.id=secret.id set secret.name='bad' where users.id=1",p,true,true));
        assertThrows(Failure.class, () -> SqlPolicy.check("delete users,secret from users join secret on users.id=secret.id where users.id=1",p,true,true));
        assertDoesNotThrow(() -> SqlPolicy.check("insert into users(id,name) values(?,?)",p,true,true));
        assertDoesNotThrow(() -> SqlPolicy.check("delete from users where id=?",p,true,true));
    }
    @Test void redisKeyAndCommandGuards() {
        var p=dm(); p.type="redis"; p.port=6379; p.keyPrefixes=List.of("test:");
        assertThrows(Failure.class, () -> RedisAdapter.validate(p,"FLUSHALL",List.of(),false));
        assertThrows(Failure.class, () -> RedisAdapter.validate(p,"EVAL",List.of("return 1","0"),false));
        assertThrows(Failure.class, () -> RedisAdapter.validate(p,"MGET",List.of("test:ok","other:key"),false));
        assertThrows(Failure.class, () -> RedisAdapter.validate(p,"SET",List.of("test:key","v"),true));
        p.allowWrites=true; p.redisWriteCommands=List.of("SET");
        assertDoesNotThrow(() -> RedisAdapter.validate(p,"SET",List.of("test:key","v"),true));
        assertThrows(Failure.class, () -> RedisAdapter.validate(p,"SET",List.of("test:key","v"),false));
        assertThrows(Failure.class, () -> RedisAdapter.validate(p,"LRANGE",List.of("test:key","0","-1"),false));
        assertDoesNotThrow(() -> RedisAdapter.validate(p,"LRANGE",List.of("test:key","0","9"),false));
    }
}
