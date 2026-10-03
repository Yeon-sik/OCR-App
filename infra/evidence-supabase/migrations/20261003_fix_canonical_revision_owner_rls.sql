begin;

-- Authorization belongs in RLS. The composite parent FK already enforces
-- that a non-null parent has the same artifact and owner as the new revision.
drop policy if exists canonical_revisions_owner_insert on public.canonical_revisions;

create policy canonical_revisions_owner_insert
    on public.canonical_revisions
    as permissive
    for insert
    to authenticated
    with check (
        canonical_revisions.owner_id = auth.uid()
        and exists (
            select 1
            from public.canonical_artifacts as artifact
            where artifact.id = canonical_revisions.canonical_artifact_id
              and artifact.owner_id = auth.uid()
        )
    );

commit;
