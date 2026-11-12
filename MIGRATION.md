# Symbol index: Valkey → Postgres

The symbol index moves from Heroku Key-Value Store (Valkey) to Heroku Postgres
(`pg_trgm`). Nothing else used Valkey, so the add-on goes away at the end.
Misses during the switch are fine: any lookup that misses falls through to AI
search, and browsing a javadoc index page (or the MCP / REST index tools)
re-indexes that artifact.

Delete this file once the migration is done.

## 1. Provision Postgres

```bash
heroku addons:create heroku-postgresql:essential-1 --as DATABASE
heroku pg:wait
```

Essential-1 is 10 GB for ~$9/month. The current index is ~270 MB freshly indexed, but
GIN trigram indexes bloat about 4× under per-artifact rewrites (see step 4), so
Essential-0's 1 GB would be tight within months. Attaching sets `DATABASE_URL`
and restarts the dyno; the running (Valkey) release ignores it.

## 2. Export the Valkey index

Needs `valkey-cli` (or `redis-cli`), e.g. `nix-shell -p valkey`. The Lua script
pages through the keyspace server-side (5,000 keys per call, read-only), so it
doesn't block Valkey for long.

```bash
mkdir -p target/redis-export && cd target/redis-export
u=$(heroku config:get REDIS_URL); rest=${u#*://}; rest=${rest#*:}
export REDISCLI_AUTH=${rest%%@*}; hp=${rest#*@}; H=${hp%:*}; P=${hp##*:}
c() { valkey-cli -h "$H" -p "$P" --tls --insecure --no-auth-warning "$@"; }
S='local r=redis.call("SCAN",ARGV[1],"COUNT","5000"); local out={r[1]}
for _,k in ipairs(r[2]) do if string.sub(k,1,1)~="_" then
for _,m in ipairs(redis.call("SMEMBERS",k)) do table.insert(out,k.."\t"..m) end end end
return out'
: > symbols.tsv; cur=0
while :; do c EVAL_RO "$S" 0 "$cur" > page.txt; cur=$(head -1 page.txt)
  tail -n +2 page.txt >> symbols.tsv; [ "$cur" = 0 ] && break; done
rm page.txt
c HGETALL _gavCache | paste - - > gavcache.tsv
wc -l symbols.tsv gavcache.tsv   # ~724k symbol rows, ~1.6k artifacts (2026-11-12)
cd ../..
```

## 3. Backfill Postgres

```bash
./sbta "Test/runMain SymbolIndexBackfill target/redis-export heroku"
```

This applies the Flyway migrations (the same ones the release phase runs) and
writes every artifact through `SymbolIndex.replace`. That drops names that
aren't symbols and keeps any newer version already indexed, so it is safe to
re-run. Artifacts without a `_gavCache` version (~3%) are skipped; they
re-index on their next visit. It took 17 s against a local database and should
take ~3 min from a laptop to Heroku.

## 4. Compact the trigram indexes

```bash
heroku pg:psql -c "reindex index concurrently symbol_fqn_trgm_idx" \
               -c "reindex index concurrently symbol_name_trgm_idx"
```

Inserting artifact by artifact leaves the GIN indexes ~4× larger than a fresh
build (fqn: 249 MB → 64 MB). Repeat whenever `heroku pg:info` shows the data
size creeping up; `concurrently` doesn't block reads or writes.

## 5. Deploy

Deploy as usual (`git push heroku master`). The Procfile's `release:
bin/flyway-migrate` runs in Heroku's release phase before the new code serves
traffic; a failed migration aborts the release. Here it is a no-op because
step 3 already applied V1.

Lookups written to Valkey between the export and the deploy are lost; they
re-index on the next miss.

## 6. Verify

- `heroku logs --tail | grep '"Symbol search"'`: each lookup logs its `match`
  tier (`Exact`, `Containing`, `Similar`), result count and `durationMs`.
- `heroku logs --tail | grep 'Index load failed'`: indexing failures are now
  logged (before, they were dropped silently).
- Try `symbol_to_artifact` with `ObjectMapper`, `objectmapper`, `pipelin`,
  `ObjectMaper` (typo) at https://www.javadocs.dev/api/doc.
- `heroku pg:info`: data size and connections (the pool uses at most 5).

## 7. Roll back (if needed, before step 8)

`heroku rollback` returns to the Valkey release; Valkey still has its data
(minus writes since the export). Postgres stays attached and unused.

## 8. Clean up (after a few days)

```bash
heroku addons:destroy redis-angular-97987          # Valkey, also removes REDIS_URL
heroku addons:destroy postgresql-dimensional-64142  # the spike database (SPIKE_DATABASE_URL)
```
