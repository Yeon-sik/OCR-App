-- Run with Supabase's existing authenticated/anon roles and auth.uid() on a
-- disposable project or through the CLI's transaction-scoped SQL query path.
-- This fixture creates only a dedicated schema, uses synthetic UUIDs, and
-- rolls every schema/table/policy/row change back at the end. It never writes
-- public.canonical_artifacts, public.canonical_revisions, or auth.users.
begin;

create schema evidence_canonical_revision_rls_smoke_20261003;
grant usage on schema evidence_canonical_revision_rls_smoke_20261003 to authenticated, anon;

create table evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts (
    id uuid primary key,
    owner_id uuid not null
);

create table evidence_canonical_revision_rls_smoke_20261003.canonical_revisions (
    id uuid primary key,
    owner_id uuid not null,
    canonical_artifact_id uuid not null references evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts(id),
    revision_seq bigint not null check (revision_seq >= 1),
    parent_revision_id uuid,
    constraint canonical_revisions_artifact_seq_key unique (canonical_artifact_id, revision_seq),
    constraint canonical_revisions_artifact_id_owner_key unique (id, canonical_artifact_id, owner_id),
    constraint canonical_revisions_parent_same_artifact_fk
        foreign key (parent_revision_id, canonical_artifact_id, owner_id)
        references evidence_canonical_revision_rls_smoke_20261003.canonical_revisions(id, canonical_artifact_id, owner_id)
        on delete restrict
);

alter table evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts enable row level security;
alter table evidence_canonical_revision_rls_smoke_20261003.canonical_revisions enable row level security;

grant select on evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts to authenticated;
grant select, insert on evidence_canonical_revision_rls_smoke_20261003.canonical_revisions to authenticated;

create policy canonical_artifacts_owner_select
    on evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts
    for select to authenticated
    using (owner_id = auth.uid());

-- Reproduce the deployed 20260916 expression exactly. PostgreSQL resolves the
-- unqualified parent_revision_id and canonical_artifact_id to the inner row.
create policy canonical_revisions_owner_insert
    on evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
    as permissive
    for insert to authenticated
    with check (
        owner_id = auth.uid()
        and exists (
            select 1
            from evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts a
            where a.id = canonical_artifact_id
              and a.owner_id = auth.uid()
        )
        and (
            parent_revision_id is null
            or exists (
                select 1
                from evidence_canonical_revision_rls_smoke_20261003.canonical_revisions parent
                where parent.id = parent_revision_id
                  and parent.canonical_artifact_id = canonical_artifact_id
                  and parent.owner_id = auth.uid()
            )
        )
    );

insert into evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts (id, owner_id)
values
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222');

-- Seed an own parent, another same-owner artifact parent, and a foreign-owner
-- parent as the database owner. All of these rows are isolated test fixtures.
insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
    (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
values
    ('b1000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 1, null),
    ('c1000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'cccccccc-cccc-cccc-cccc-cccccccccccc', 1, null);

select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
set local role authenticated;

-- Case 1: revision 1 with a null parent is accepted by the old policy.
insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
    (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
values
    ('a1000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 1, null);

-- Case 2: a valid own parent is rejected by the old expression because the
-- inner parent row has parent_revision_id=NULL. Assert the exact RLS SQLSTATE.
do $$
declare
    rejected boolean := false;
    actual_state text;
begin
    begin
        insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
            (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
        values
            ('a1000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 2, 'a1000000-0000-0000-0000-000000000001');
    exception when insufficient_privilege then
        get stacked diagnostics actual_state = returned_sqlstate;
        rejected := actual_state = '42501';
    end;
    if not rejected then
        raise exception 'Expected the old policy to reject a valid same-owner parent with SQLSTATE 42501';
    end if;
end;
$$;

reset role;
drop policy canonical_revisions_owner_insert
    on evidence_canonical_revision_rls_smoke_20261003.canonical_revisions;

-- This is the repaired production policy shape. The composite FK below remains
-- responsible for enforcing parent existence, artifact identity, and owner.
create policy canonical_revisions_owner_insert
    on evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
    as permissive
    for insert to authenticated
    with check (
        canonical_revisions.owner_id = auth.uid()
        and exists (
            select 1
            from evidence_canonical_revision_rls_smoke_20261003.canonical_artifacts artifact
            where artifact.id = canonical_revisions.canonical_artifact_id
              and artifact.owner_id = auth.uid()
        )
    );

set local role authenticated;

-- Case 2: a valid same-owner/same-artifact parent is accepted after repair.
insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
    (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
values
    ('a1000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 2, 'a1000000-0000-0000-0000-000000000001');

-- Cases 3-4: RLS continues to reject foreign artifacts and foreign owners.
do $$
declare
    actual_state text;
begin
    begin
        insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
            (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
        values
            ('a1000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'cccccccc-cccc-cccc-cccc-cccccccccccc', 4, null);
        raise exception 'Expected foreign artifact insert to be rejected';
    exception when insufficient_privilege then
        get stacked diagnostics actual_state = returned_sqlstate;
        if actual_state <> '42501' then raise; end if;
    end;

    begin
        insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
            (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
        values
            ('a1000000-0000-0000-0000-000000000005', '22222222-2222-2222-2222-222222222222', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 5, null);
        raise exception 'Expected foreign owner insert to be rejected';
    exception when insufficient_privilege then
        get stacked diagnostics actual_state = returned_sqlstate;
        if actual_state <> '42501' then raise; end if;
    end;
end;
$$;

-- Cases 5-6: RLS allows this caller-owned revision, but the composite FK rejects
-- a parent from another artifact or another owner with SQLSTATE 23503.
do $$
declare
    actual_state text;
begin
    begin
        insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
            (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
        values
            ('a1000000-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 6, 'b1000000-0000-0000-0000-000000000001');
        raise exception 'Expected other-artifact parent to fail the composite FK';
    exception when foreign_key_violation then
        get stacked diagnostics actual_state = returned_sqlstate;
        if actual_state <> '23503' then raise; end if;
    end;

    begin
        insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
            (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
        values
            ('a1000000-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 7, 'c1000000-0000-0000-0000-000000000001');
        raise exception 'Expected other-owner parent to fail the composite FK';
    exception when foreign_key_violation then
        get stacked diagnostics actual_state = returned_sqlstate;
        if actual_state <> '23503' then raise; end if;
    end;
end;
$$;

reset role;
set local role anon;

-- Case 7: unauthenticated callers have no INSERT grant and cannot create rows.
do $$
declare
    actual_state text;
begin
    begin
        insert into evidence_canonical_revision_rls_smoke_20261003.canonical_revisions
            (id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id)
        values
            ('a1000000-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 8, null);
        raise exception 'Expected anon INSERT to be rejected';
    exception when insufficient_privilege then
        get stacked diagnostics actual_state = returned_sqlstate;
        if actual_state <> '42501' then raise; end if;
    end;
end;
$$;

reset role;
rollback;

select 'PASS: old policy reproduced the valid-parent denial; repaired policy passed ownership, FK, and anon checks; fixture transaction rolled back.' as result;
