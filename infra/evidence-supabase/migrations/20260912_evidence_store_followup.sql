begin;

-- Follow-up migration for Evidence DBs created before the immutable bundle schema.
-- Every operation is repeatable: it may be retried after a partial deployment.
create extension if not exists pgcrypto;

insert into storage.buckets (id, name, public, file_size_limit)
values ('yeonsik-evidence', 'yeonsik-evidence', false, 52428800)
on conflict (id) do update
set name = excluded.name,
    public = false,
    file_size_limit = excluded.file_size_limit;

-- These CREATE TABLE statements also make the follow-up safe for a partially
-- provisioned Evidence schema; existing tables are upgraded below.
create table if not exists public.canonical_artifacts (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    canonical_sha256 text not null check (canonical_sha256 ~ '^[a-f0-9]{64}$'),
    manifest_sha256 text not null check (manifest_sha256 ~ '^[a-f0-9]{64}$'),
    bundle_fingerprint text not null check (bundle_fingerprint ~ '^[a-f0-9]{64}$'),
    schema_version text not null,
    mode text not null,
    canonical_json jsonb not null,
    manifest_json jsonb not null,
    created_at timestamptz not null default now(),
    constraint canonical_artifacts_owner_bundle_fingerprint_key unique (owner_id, bundle_fingerprint)
);

create table if not exists public.evidence_objects (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
    mime_type text not null,
    byte_size bigint not null check (byte_size >= 0 and byte_size <= 52428800),
    bucket text not null check (bucket = 'yeonsik-evidence'),
    storage_path text not null,
    created_at timestamptz not null default now(),
    unique (owner_id, sha256),
    unique (bucket, storage_path),
    check (storage_path = owner_id::text || '/' || sha256 || '.' || split_part(storage_path, '.', 2))
);

create table if not exists public.evidence_bindings (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    canonical_artifact_id uuid not null references public.canonical_artifacts(id) on delete cascade,
    source_file_id text not null,
    source_type text not null,
    evidence_object_id uuid not null references public.evidence_objects(id) on delete restrict,
    metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
    created_at timestamptz not null default now(),
    unique (canonical_artifact_id, source_file_id)
);

create table if not exists public.verification_events (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    canonical_artifact_id uuid not null references public.canonical_artifacts(id) on delete cascade,
    basis text not null check (basis in ('SOURCE_EVIDENCE', 'MANUAL_CANONICAL_REVIEW')),
    result text not null,
    issues jsonb not null default '[]'::jsonb check (jsonb_typeof(issues) = 'array'),
    verified_at timestamptz not null default now()
);

alter table public.canonical_artifacts add column if not exists manifest_sha256 text;
alter table public.canonical_artifacts add column if not exists bundle_fingerprint text;

-- Old canonical rows have no manifest hash. Their stored JSON is the only
-- available evidence representation, so use its canonical jsonb text form.
update public.canonical_artifacts
set manifest_sha256 = encode(digest(convert_to(manifest_json::text, 'UTF8'), 'sha256'), 'hex')
where manifest_sha256 is null;

update public.canonical_artifacts
set bundle_fingerprint = encode(
    digest(
        'yeonsik-bundle.v1' || E'\n' ||
        'canonical_sha256=' || canonical_sha256 || E'\n' ||
        'manifest_sha256=' || manifest_sha256,
        'sha256'
    ),
    'hex'
)
where bundle_fingerprint is null;

alter table public.canonical_artifacts alter column manifest_sha256 set not null;
alter table public.canonical_artifacts alter column bundle_fingerprint set not null;
alter table public.canonical_artifacts drop constraint if exists canonical_artifacts_owner_id_canonical_sha256_key;
create unique index if not exists canonical_artifacts_owner_bundle_fingerprint_key
    on public.canonical_artifacts (owner_id, bundle_fingerprint);

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.canonical_artifacts'::regclass
          and conname = 'canonical_artifacts_manifest_sha256_check'
    ) then
        alter table public.canonical_artifacts
            add constraint canonical_artifacts_manifest_sha256_check
            check (manifest_sha256 ~ '^[a-f0-9]{64}$');
    end if;
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.canonical_artifacts'::regclass
          and conname = 'canonical_artifacts_bundle_fingerprint_check'
    ) then
        alter table public.canonical_artifacts
            add constraint canonical_artifacts_bundle_fingerprint_check
            check (bundle_fingerprint ~ '^[a-f0-9]{64}$');
    end if;
end $$;

alter table public.evidence_bindings add column if not exists metadata jsonb;
update public.evidence_bindings set metadata = '{}'::jsonb where metadata is null;
alter table public.evidence_bindings alter column metadata set default '{}'::jsonb;
alter table public.evidence_bindings alter column metadata set not null;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.evidence_bindings'::regclass
          and conname = 'evidence_bindings_metadata_check'
    ) then
        alter table public.evidence_bindings
            add constraint evidence_bindings_metadata_check
            check (jsonb_typeof(metadata) = 'object');
    end if;
end $$;

-- Preserve the old name at the binding level before removing it from the
-- content-addressed evidence object. Existing non-empty binding metadata wins.
do $$
begin
    if exists (
        select 1 from information_schema.columns
        where table_schema = 'public'
          and table_name = 'evidence_objects'
          and column_name = 'original_filename'
    ) then
        execute $sql$
            update public.evidence_bindings b
            set metadata = jsonb_set(
                coalesce(b.metadata, '{}'::jsonb),
                '{original_filename}',
                to_jsonb(o.original_filename),
                true
            )
            from public.evidence_objects o
            where b.evidence_object_id = o.id
              and not (coalesce(b.metadata, '{}'::jsonb) ? 'original_filename')
        $sql$;
        execute 'alter table public.evidence_objects drop column original_filename';
    end if;
end $$;

alter table public.canonical_artifacts enable row level security;
alter table public.evidence_objects enable row level security;
alter table public.evidence_bindings enable row level security;
alter table public.verification_events enable row level security;

grant select, insert on public.canonical_artifacts to authenticated;
grant select, insert on public.evidence_objects to authenticated;
grant select, insert on public.evidence_bindings to authenticated;
grant select, insert on public.verification_events to authenticated;
revoke update, delete, truncate on public.canonical_artifacts, public.evidence_objects,
    public.evidence_bindings, public.verification_events from authenticated;

drop policy if exists canonical_artifacts_owner_all on public.canonical_artifacts;
drop policy if exists evidence_objects_owner_all on public.evidence_objects;
drop policy if exists evidence_bindings_owner_all on public.evidence_bindings;
drop policy if exists verification_events_owner_all on public.verification_events;
drop policy if exists canonical_artifacts_owner_select on public.canonical_artifacts;
drop policy if exists canonical_artifacts_owner_insert on public.canonical_artifacts;
drop policy if exists evidence_objects_owner_select on public.evidence_objects;
drop policy if exists evidence_objects_owner_insert on public.evidence_objects;
drop policy if exists evidence_bindings_owner_select on public.evidence_bindings;
drop policy if exists evidence_bindings_owner_insert on public.evidence_bindings;
drop policy if exists verification_events_owner_select on public.verification_events;
drop policy if exists verification_events_owner_insert on public.verification_events;

create policy canonical_artifacts_owner_select on public.canonical_artifacts
for select using (owner_id = auth.uid());
create policy canonical_artifacts_owner_insert on public.canonical_artifacts
for insert with check (owner_id = auth.uid());
create policy evidence_objects_owner_select on public.evidence_objects
for select using (owner_id = auth.uid());
create policy evidence_objects_owner_insert on public.evidence_objects
for insert with check (owner_id = auth.uid());
create policy evidence_bindings_owner_select on public.evidence_bindings
for select using (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
    and exists (select 1 from public.evidence_objects o where o.id = evidence_object_id and o.owner_id = auth.uid())
);
create policy evidence_bindings_owner_insert on public.evidence_bindings
for insert with check (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
    and exists (select 1 from public.evidence_objects o where o.id = evidence_object_id and o.owner_id = auth.uid())
);
create policy verification_events_owner_select on public.verification_events
for select using (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
);
create policy verification_events_owner_insert on public.verification_events
for insert with check (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
);

-- Supabase does not support CREATE POLICY IF NOT EXISTS. Drop/recreate keeps
-- these pre-existing policy names safe on both old and partially upgraded DBs.
drop policy if exists yeonsik_evidence_insert_own on storage.objects;
drop policy if exists yeonsik_evidence_select_own on storage.objects;

create policy yeonsik_evidence_insert_own on storage.objects
for insert to authenticated
with check (
    bucket_id = 'yeonsik-evidence'
    and (storage.foldername(name))[1] = auth.uid()::text
    and owner_id = auth.uid()::text
);
create policy yeonsik_evidence_select_own on storage.objects
for select to authenticated
using (
    bucket_id = 'yeonsik-evidence'
    and (storage.foldername(name))[1] = auth.uid()::text
    and owner_id = auth.uid()::text
);

commit;
