# ai-db-cli · dbx

面向 AI 的多数据源命令行工具。首版支持 **达梦 DM8 + Redis**，按连接名调用，非交互执行，统一 JSON 输出，默认只读。

独立 Java 17+ 项目，不依赖公司框架、Spring、数据库客户端或 `disql`。macOS 分发包内置裁剪后的 OpenJDK、JDBC 驱动和 Redis 客户端；使用者无需另装 Java。

## 安装

下载与你的 Mac 架构一致的构建包（Apple Silicon 为 `arm64`，Intel 为 `x86_64`）。CI 构建产物位于仓库 Actions 的 `macos-*` artifacts；正式版本可从 Releases 获取。

解压后，在包目录执行一条安装命令：

```sh
sh install.sh
```

默认安装到 `~/.local/share/dbx/0.1.0`，启动命令位于 `~/.local/bin/dbx`。将 `~/.local/bin` 加入 PATH 后：

```sh
dbx capabilities
```

可通过 `DBX_INSTALL_DIR` / `DBX_BIN_DIR` 自定义路径，不需要 sudo。分发包按架构构建，目前未进行 Apple Developer ID 签名或公证。

## 添加连接

示例地址与账号均为虚构值。密码只保存为环境变量名，不接受明文密码参数，也不接受带密码的连接 URL。

```sh
export DM_DEV_PASSWORD='your-password'
dbx connections add --name dm-dev --type dm \
  --host 127.0.0.1 --port 5236 --user APP_READER \
  --schema APP --password-env DM_DEV_PASSWORD

export REDIS_DEV_PASSWORD='your-password'
dbx connections add --name redis-dev --type redis \
  --host 127.0.0.1 --port 6379 --password-env REDIS_DEV_PASSWORD \
  --key-prefix 'app:'

dbx connections list
dbx connections remove --name redis-dev
```

配置默认位于 `~/.config/dbx/connections.json`，也可用 `DBX_CONFIG` 或 `--config /absolute/path.json` 指定。配置以 0600 权限原子写入，并使用文件锁防止并发覆盖；连接列表只包含环境变量引用。

Redis 支持 `--user`（ACL 用户）、`--database 0` 和 `--tls`（系统信任链与主机名验证）。DM 默认 schema 可用 `--schema` 设置；首版暂不支持 DM 证书/TLS 配置、Redis Cluster/Sentinel 或 SSH 隧道。

## 查询与表结构

```sh
dbx schema tables -c dm-dev --schema APP
dbx schema describe -c dm-dev --schema APP --table USERS

dbx sql query -c dm-dev \
  --sql 'SELECT ID, NAME FROM APP.USERS WHERE ID = ?' \
  --params '[123]' --limit 20

dbx sql query -c dm-dev --file query.sql --params '[123]'
printf 'SELECT 1 FROM DUAL' | dbx sql query -c dm-dev --file -
```

SQL 使用 `?` 参数绑定，不做字符串替换。结果包含 `columns` 和按列顺序排列的 `rows`，支持重复列名；SQL 数值以字符串返回以保留 BIGINT/DECIMAL 精度，NULL 返回 JSON null。二进制以 base64 对象返回，LOB 返回明确的省略标记。`schema describe` 返回 JDBC 列元数据（类型、长度、精度、可空性、默认值、注释等）；首版不包含完整建表 DDL、索引或外键。

## Redis

```sh
dbx redis command -c redis-dev -- GET app:user:123
dbx redis command -c redis-dev -- MGET app:user:123 app:user:456
dbx redis command -c redis-dev -- HGET app:user:123 name
dbx redis command -c redis-dev -- LRANGE app:recent 0 19
```

通过 `capabilities` 获取完整命令允许列表。禁用 EVAL、脚本、事务、订阅、管理命令、KEYS 和任意命令转发。多 key 命令会逐个检查 key 前缀。首版不提供全库扫描；范围查询必须给出非负且受限的起止位置。返回的非法 UTF-8 字节使用 base64 表示，缺失 key 返回 null。

## 显式启用写入

默认配置只读。SQL 写入需要三个条件：连接开启写入、目标表在写允许列表、调用带 `--allow-write`。

```sh
dbx connections add --name dm-write --type dm --host 127.0.0.1 \
  --user APP_WRITER --password-env DM_WRITE_PASSWORD --schema APP \
  --enable-writes --write-table APP.USERS --max-rows 10

dbx sql exec -c dm-write --allow-write \
  --sql 'UPDATE APP.USERS SET NAME = ? WHERE ID = ?' \
  --params '["example",123]'
```

仅支持单条 INSERT、单表 UPDATE、单表 DELETE；UPDATE/DELETE 必须带 WHERE。DDL、过程调用、锁、序列、注释、多语句及未允许的函数均被拒绝。DML 在事务中执行，直接影响行数超过 `maxRows` 时回滚；触发器影响行数不包含在 JDBC 的计数中。

Redis 写入还需要逐条开放命令：

```sh
dbx connections add --name redis-write --type redis --host 127.0.0.1 \
  --password-env REDIS_WRITE_PASSWORD --key-prefix 'app:' \
  --enable-writes --redis-write-command SET --redis-write-command DEL

dbx redis command -c redis-write --allow-write -- SET app:example value EX 60
```

SQL `--read-table APP.USERS` 可重复设置，限制查询涉及的表，包括子查询和 JOIN；不设置则允许读取当前数据库账号可读的表。允许列表中的限定表名与 SQL 中的限定表名精确对应。未限定表名对应连接默认 schema。Redis `--key-prefix` 不设置时允许读取该账号可读的全部 key。

CLI 策略属于应用层防护。请给默认连接使用数据库只读账号、Redis ACL 与网络访问控制；有权限修改配置或直接访问凭据的调用者可以改变 CLI 策略，SQL 函数名检查也不能替代数据库侧授权。

## AI 输出与限制

stdout 总是一个 JSON 对象，连 `--help` 和参数错误也使用 JSON；不输出表格、提示输入、原始 SQL 或驱动异常。SQL/Redis 返回的数据本身可能包含敏感信息，调用方应按自身规则处理。

```json
{"version":"0.1.0","ok":true,"data":{"columns":[{"name":"ID","type":"BIGINT"}],"rows":[["123"]],"rowCount":1,"truncated":false}}
```

默认每连接：100 行、10 秒驱动超时、1 MiB 数据字节预算。添加连接时可用 `--max-rows`、`--timeout-seconds`、`--max-bytes` 设置；调用时 `--limit` 只能降低行数上限。SQL 超出读取预算返回 `truncated:true`；Redis 超预算返回错误，不静默丢弃结果。字节预算是数据预算，不含 JSON envelope 的少量开销；不保证后端、驱动或 Redis 网络响应的内存使用量，进程堆限制为 256 MiB。

整体命令默认 30 秒期限，可用全局 `--timeout 1..120` 覆盖。写入遇到超时、连接丢失或后端错误时，提交结果可能不确定，**先核对服务端状态，再决定是否重试**。

| 退出码 | 含义 |
|---|---|
| 0 | 成功 |
| 2 | 参数、配置或环境变量错误 |
| 3 | 权限策略拒绝 |
| 4 | 后端、连接或本地 I/O 错误 |
| 5 | 整体命令超时 |
| 6 | Redis/元数据结果超出字节预算 |

## 开发与构建

开发需要 JDK 17+ 和 Maven 3.9+；使用项目自带的 Central 设置，避免开发机的私有镜像影响：

```sh
mvn -B -s settings.xml verify
java -jar target/dbx.jar capabilities
```

有 `redis-server` 时自动启动仅绑定回环地址、无持久化的临时实例完成集成测试，无需已有 Redis 数据库；未安装时该项明确标记为跳过。SQL 集成测试使用 H2 验证通用 JDBC 行为，不能替代 DM8 实库验证。

在 Mac 上构建包含运行时的包：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
sh scripts/package-macos.sh
```

请使用可再分发的 OpenJDK（如 Temurin）。打包目录为 `dist/dbx-0.1.0-macos-<arch>`，同时生成 tar.gz 和 SHA-256。包内保留依赖和运行时许可文件。

GitHub Actions 在 Linux 上测试，在 Apple Silicon / Intel Mac 上分别构建并验证包。可以从 Actions 下载分发包。

## 扩展

`ConfigStore` 负责连接配置，`SqlPolicy` 负责 SQL 策略，`SqlAdapter` 负责 JDBC，`RedisAdapter` 负责 Redis；`Main` 只处理命令和输出协议。新增关系库应增加驱动与连接工厂，并补充方言策略和实库测试；不要通过直接拼接连接 URL 或放开任意命令绕过权限检查。

许可：项目代码 MIT；依赖许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
