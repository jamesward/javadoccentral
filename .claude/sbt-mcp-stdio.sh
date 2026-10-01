#!/usr/bin/env bash
# stdio MCP bridge to this build's sbt-mcp server (http://127.0.0.1:5106/).
#
# Claude Code connects to MCP servers while the session starts, before sbt could be
# running, so a plain HTTP entry in .mcp.json fails with "connection refused". This
# script is a stdio MCP server instead: it starts sbt when needed (cloud sessions only),
# waits for sbt-mcp to listen, then bridges stdio to it with mcp-remote.
# stdout carries the MCP protocol only. Progress goes to $diag (and stderr); sbt's own
# output goes to $log. If the server doesn't connect, read both files.

set -u
cd "$(dirname "$0")/.." || exit 1

port=5106
log="${TMPDIR:-/tmp}/sbt-mcp-server.log"
diag="${TMPDIR:-/tmp}/sbt-mcp-stdio.log"

say() { echo "$(date -u +%H:%M:%S) $*" | tee -a "$diag" >&2; }
listening() { curl -s -o /dev/null --max-time 2 --noproxy '*' "http://127.0.0.1:${port}/"; }

# Cloud sessions route traffic through an HTTP proxy; never send loopback traffic to it.
export NO_PROXY="127.0.0.1,localhost${NO_PROXY:+,$NO_PROXY}"
export no_proxy="$NO_PROXY"

say "start: pwd=$PWD CLAUDE_CODE_REMOTE=${CLAUDE_CODE_REMOTE:-} java=$(java -version 2>&1 | grep -m1 version)"

if ! listening; then
  if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    say "sbt-mcp is not running on 127.0.0.1:${port}. Start sbt in this project, then reconnect."
    exit 1
  fi
  say "starting sbt (output: $log)"
  # Run sbt in the foreground (`--server`) with a stdin that never closes, detached with
  # setsid. sbt-mcp's sbt-task needs this attached console channel: a daemon started by a
  # one-off `./sbt <cmd>` has none ("no sbt channel available yet"). Later `./sbt <task>`
  # client calls connect to this same server.
  setsid nohup bash -c 'tail -f /dev/null | ./sbt --server --no-colors --supershell=false' > "$log" 2>&1 < /dev/null &
  for _ in $(seq 1 270); do
    listening && break
    sleep 2
  done
  listening || { say "sbt-mcp did not start; last lines of $log:"; tail -20 "$log" | tee -a "$diag" >&2; exit 1; }
fi

say "sbt-mcp is listening on ${port}; starting mcp-remote"
exec npx -y mcp-remote@0.14.3 "http://127.0.0.1:${port}/" --transport http-only 2> >(tee -a "$diag" >&2)
