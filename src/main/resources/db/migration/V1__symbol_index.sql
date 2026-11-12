-- Symbol index: which Maven Central artifacts publish which documented symbols.
-- Replaces the Valkey layout (one set per symbol + `_groupArtifacts` + `_gavCache`).

create extension if not exists pg_trgm;

-- One row per indexed groupId:artifactId. Only the newest indexed version's
-- symbols are kept; `indexed_version` replaces the old `_gavCache` hash.
create table artifact (
  id              integer     generated always as identity primary key,
  group_id        text        not null,
  artifact_id     text        not null,
  indexed_version text        not null,
  indexed_at      timestamptz not null default now(),
  unique (group_id, artifact_id)
);

-- One row per documented symbol of an artifact. `fqn` is as the javadoc /
-- scaladoc / dokka index names it (e.g. `com.example.Outer.Builder`); `name` is
-- its lower-cased last segment, for simple-name lookups.
create table symbol (
  artifact_id integer not null references artifact (id) on delete cascade,
  fqn         text    not null,
  name        text    generated always as (lower(substring(fqn from '[^.]*$'))) stored
);

-- Replacing an artifact's symbols deletes by artifact.
create index symbol_artifact_idx on symbol (artifact_id);
-- Exact simple name, any case ("objectmapper").
create index symbol_name_idx on symbol (name);
-- Exact full name and package prefix, any case ("com.fasterxml.jackson.databind.%").
create index symbol_fqn_lower_idx on symbol (lower(fqn) text_pattern_ops);
-- Substring, any case (fqn ILIKE '%pipelin%').
create index symbol_fqn_trgm_idx on symbol using gin (fqn gin_trgm_ops);
-- Close spellings of the simple name (name % 'objectmaper').
create index symbol_name_trgm_idx on symbol using gin (name gin_trgm_ops);
