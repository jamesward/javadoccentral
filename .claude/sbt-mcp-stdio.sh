#!/usr/bin/env bash
# stdio MCP bridge to this build's sbt-mcp server (http://127.0.0.1:5106/).
#
# Claude Code connects to MCP servers while the session starts, before sbt could be
# running, so a plain HTTP entry in .mcp.json fails with "connection refused". This
# script is a stdio MCP server instead: it starts sbt when needed (cloud sessions only),
# waits for sbt-mcp to listen, then bridges stdio to it with mcp-remote.
# stdout carries the MCP protocol only; everything else goes to stderr or the log.

set -u
cd "$(dirname "$0")/.." || exit 1

port=5106
log="${TMPDIR:-/tmp}/sbt-mcp-server.log"

listening() { curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${port}/"; }

if ! listening; then
  if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    echo "sbt-mcp is not running on 127.0.0.1:${port}. Start sbt in this project, then reconnect." >&2
    exit 1
  fi
  # In client mode `./sbt` starts the persistent sbt server, which keeps running (with
  # sbt-mcp) after this command exits. Later `./sbt <task>` calls reuse the same server.
  timeout 540 ./sbt --no-colors about > "$log" 2>&1 < /dev/null
  for _ in $(seq 1 30); do
    listening && break
    sleep 2
  done
  listening || { echo "sbt-mcp did not start; see ${log}" >&2; exit 1; }
fi

exec npx -y mcp-remote@0.14.3 "http://127.0.0.1:${port}/" --transport http-only
