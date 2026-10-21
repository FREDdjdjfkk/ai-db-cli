# Third-party components

The MIT license covers this project's code, not its dependencies.

| Component | Version | License / upstream |
|---|---|---|
| picocli | 4.7.7 | Apache-2.0, https://github.com/remkop/picocli |
| Gson | 2.13.2 | Apache-2.0, https://github.com/google/gson |
| JSqlParser | 5.3 | Apache-2.0 or LGPL-2.1, https://github.com/JSQLParser/JSqlParser |
| Dameng JDBC | 8.1.3.140 | Apache-2.0 as declared in its published Maven POM; https://gitee.com/dmedu/dm-jdbc-jars |
| Jedis | 5.2.0 | MIT, https://github.com/redis/jedis |
| SLF4J | 1.7.36 | MIT, https://www.slf4j.org |
| Apache Commons Pool | 2.12.0 | Apache-2.0, https://commons.apache.org/proper/commons-pool/ |
| JSON-java | 20240303 | Public domain, https://github.com/stleary/JSON-java |
| Error Prone annotations | 2.41.0 | Apache-2.0, https://github.com/google/error-prone |

Transitive dependency notices remain in the shaded JAR's META-INF directory.
The runtime's legal directory contains its module license notices; retain it.
Build runtime bundles using a redistributable OpenJDK distribution (CI uses
Eclipse Temurin). OpenJDK is GPLv2 with the Classpath Exception; retain its legal
notices and consult the distribution's source availability requirements.

Dameng's pinned Maven artifact declares Apache-2.0 in its published POM:
https://repo.maven.apache.org/maven2/com/dameng/DmJdbcDriver18/8.1.3.140/DmJdbcDriver18-8.1.3.140.pom
Preserve that declaration and its Apache license when redistributing the bundle.
