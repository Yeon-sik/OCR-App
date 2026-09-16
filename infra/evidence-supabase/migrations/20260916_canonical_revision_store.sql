begin;

create extension if not exists pgcrypto;

-- User edits are immutable snapshots alongside (and never in place of) canonical_artifacts.
-- canonical_artifacts remains the producer import; revision_seq starts at 1 for the first edit.
create table if not exists public.canonical_revisions (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    canonical_artifact_id uuid not null references public.canonical_artifacts(id) on delete cascade,
    revision_seq bigint not null check (revision_seq >= 1),
    parent_revision_id uuid,
    canonical_sha256 text not null check (canonical_sha256 ~ '^[a-f0-9]{64}$'),
    canonical_json jsonb not null,
    schema_version text not null,
    mode text not null,
    validation_status text not null,
    validation_issues jsonb not null default '[]'::jsonb check (jsonb_typeof(validation_issues) = 'array'),
    created_at timestamptz not null default now(),
    constraint canonical_revisions_artifact_seq_key unique (canonical_artifact_id, revision_seq),
    constraint canonical_revisions_artifact_id_owner_key unique (id, canonical_artifact_id, owner_id),
    constraint canonical_revisions_parent_same_artifact_fk
        foreign key (parent_revision_id, canonical_artifact_id, owner_id)
        references public.canonical_revisions(id, canonical_artifact_id, owner_id)
        on delete restrict
);

create table if not exists public.canonical_edit_events (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    canonical_artifact_id uuid not null references public.canonical_artifacts(id) on delete cascade,
    canonical_revision_id uuid not null,
    field_path text not null check (length(trim(field_path)) > 0),
    previous_value jsonb,
    new_value jsonb,
    provenance jsonb not null check (jsonb_typeof(provenance) = 'object'),
    edited_at timestamptz not null default now(),
    constraint canonical_edit_events_revision_owner_fk
        foreign key (canonical_revision_id, canonical_artifact_id, owner_id)
        references public.canonical_revisions(id, canonical_artifact_id, owner_id)
        on delete restrict
);

create index if not exists canonical_revisions_owner_artifact_seq_idx
    on public.canonical_revisions (owner_id, canonical_artifact_id, revision_seq desc);
create index if not exists canonical_edit_events_owner_revision_time_idx
    on public.canonical_edit_events (owner_id, canonical_revision_id, edited_at asc);

alter table public.canonical_revisions enable row level security;
alter table public.canonical_edit_events enable row level security;

grant select, insert on public.canonical_revisions to authenticated;
grant select, insert on public.canonical_edit_events to authenticated;
revoke update, delete, truncate on public.canonical_revisions, public.canonical_edit_events from authenticated;

drop policy if exists canonical_revisions_owner_select on public.canonical_revisions;
drop policy if exists canonical_revisions_owner_insert on public.canonical_revisions;
drop policy if exists canonical_edit_events_owner_select on public.canonical_edit_events;
drop policy if exists canonical_edit_events_owner_insert on public.canonical_edit_events;

create policy canonical_revisions_owner_select on public.canonical_revisions
for select to authenticated using (owner_id = auth.uid());

create policy canonical_revisions_owner_insert on public.canonical_revisions
for insert to authenticated with check (
    owner_id = auth.uid()
    and exists (
        select 1
        from public.canonical_artifacts a
        where a.id = canonical_artifact_id
          and a.owner_id = auth.uid()
    )
    and (
        parent_revision_id is null
        or exists (
            select 1
            from public.canonical_revisions parent
            where parent.id = parent_revision_id
              and parent.canonical_artifact_id = canonical_artifact_id
              and parent.owner_id = auth.uid()
        )
    )
);

create policy canonical_edit_events_owner_select on public.canonical_edit_events
for select to authenticated using (
    owner_id = auth.uid()
    and exists (
        select 1
        from public.canonical_revisions r
        where r.id = canonical_revision_id
          and r.canonical_artifact_id = canonical_edit_events.canonical_artifact_id
          and r.owner_id = auth.uid()
    )
);

create policy canonical_edit_events_owner_insert on public.canonical_edit_events
for insert to authenticated with check (
    owner_id = auth.uid()
    and exists (
        select 1
        from public.canonical_revisions r
        where r.id = canonical_revision_id
          and r.canonical_artifact_id = canonical_edit_events.canonical_artifact_id
          and r.owner_id = auth.uid()
    )
);

commit;
