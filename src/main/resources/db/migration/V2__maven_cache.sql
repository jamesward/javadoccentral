-- Caches of Maven Central lookups (MavenCache.scala), so most requests don't
-- reach Maven Central and repo1 sees at most a conditional revalidation per
-- artifact per hour. Every row can be rebuilt from Maven Central, so rows can
-- be deleted at any time; a daily prune drops those not read in 30 days.

-- An artifact's versions from its maven-metadata.xml, newest first. `etag` /
-- `last_modified` revalidate with a 304. `from_fallback`: served by the GCS
-- mirror while repo1 couldn't be asked, so kept for less time.
create table artifact_metadata (
  group_id      text        not null,
  artifact_id   text        not null,
  versions      text[]      not null,
  etag          text,
  last_modified timestamptz,
  from_fallback boolean     not null,
  fetched_at    timestamptz not null,
  accessed_at   timestamptz not null default now(),
  primary key (group_id, artifact_id)
);

-- A groupId's artifactIds, from its directory listing.
create table group_listing (
  group_id      text        primary key,
  artifact_ids  text[]      not null,
  from_fallback boolean     not null,
  fetched_at    timestamptz not null,
  accessed_at   timestamptz not null default now()
);

-- Things repo1 (the authoritative mirror) said don't exist, until `expires_at`:
-- kind 'artifact' (key groupId:artifactId), 'group' (groupId), 'javadoc' /
-- 'sources' (groupId:artifactId:version, a version without that jar).
create table not_found (
  kind       text        not null,
  key        text        not null,
  expires_at timestamptz not null,
  primary key (kind, key)
);

create index artifact_metadata_accessed_idx on artifact_metadata (accessed_at);
create index group_listing_accessed_idx on group_listing (accessed_at);
create index not_found_expires_idx on not_found (expires_at);
