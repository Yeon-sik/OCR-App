begin;

create extension if not exists pgcrypto;

insert into storage.buckets (id, name, public, file_size_limit)
values ('yeonsik-evidence', 'yeonsik-evidence', false, 52428800)
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit;

create table if not exists public.canonical_artifacts (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    canonical_sha256 text not null check (canonical_sha256 ~ '^[a-f0-9]{64}$'),
    schema_version text not null,
    mode text not null,
    canonical_json jsonb not null,
    manifest_json jsonb not null,
    created_at timestamptz not null default now(),
    unique (owner_id, canonical_sha256)
);

create table if not exists public.evidence_objects (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
    mime_type text not null,
    byte_size bigint not null check (byte_size >= 0 and byte_size <= 52428800),
    original_filename text not null,
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

alter table public.canonical_artifacts enable row level security;
alter table public.evidence_objects enable row level security;
alter table public.evidence_bindings enable row level security;
alter table public.verification_events enable row level security;

grant select, insert, update on public.canonical_artifacts to authenticated;
grant select, insert, update on public.evidence_objects to authenticated;
grant select, insert, update on public.evidence_bindings to authenticated;
grant select, insert on public.verification_events to authenticated;

create policy canonical_artifacts_owner_all on public.canonical_artifacts
for all using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy evidence_objects_owner_all on public.evidence_objects
for all using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy evidence_bindings_owner_all on public.evidence_bindings
for all using (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
    and exists (select 1 from public.evidence_objects o where o.id = evidence_object_id and o.owner_id = auth.uid())
) with check (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
    and exists (select 1 from public.evidence_objects o where o.id = evidence_object_id and o.owner_id = auth.uid())
);
create policy verification_events_owner_all on public.verification_events
for all using (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
) with check (
    owner_id = auth.uid()
    and exists (select 1 from public.canonical_artifacts a where a.id = canonical_artifact_id and a.owner_id = auth.uid())
);

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
