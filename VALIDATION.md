# Validation · 2026-10-21

- Automated tests: 30 passed, 0 failed, 0 skipped on macOS Apple Silicon.
- Redis: a disposable loopback-only Redis instance verified reads, explicitly
  permitted writes, key-prefix restrictions and rejection of admin commands.
- SQL: H2 verified prepared parameters, exact decimal output, row truncation,
  column metadata, committing authorized writes and rolling back affected-row
  limit violations.
- DM8: a user-authorized remote database was checked **read-only**. Constant
  SELECT, requested-schema table listing, column metadata and a zero-row query
  against an existing table all passed. No business rows were retrieved, no
  mutation was sent to the server, and credentials/configuration were temporary.
- DM write operations have not been tested against a live DM server.
- Standalone Apple Silicon bundle: built successfully with an embedded OpenJDK
  runtime. Installation into paths containing spaces and startup with system Java
  removed from PATH both passed. Bundle size is approximately 29 MiB compressed.
- Intel Mac and CI bundle checks are separate from local Apple Silicon checks;
  consult the Actions run results for their current status.

No live endpoints, schemas, usernames, passwords or business records are stored
in this report or the repository.
