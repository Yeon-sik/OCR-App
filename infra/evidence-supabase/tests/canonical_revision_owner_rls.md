# Canonical revision owner RLS smoke check

Run this only against a disposable Supabase staging project with two test users. A successful request writes an append-only revision, so do not use production data. Apply the existing Evidence migrations through 20260916_canonical_revision_store.sql first.

This check exercises the deployed PostgREST/Auth/RLS boundary. The automated JVM tests use an HTTP transport fixture and do not claim live database coverage.

## Confirmed parent-policy defect

The deployed `canonical_revisions_owner_insert` expression from the 20260916 migration leaves the outer `parent_revision_id` and `canonical_artifact_id` unqualified inside a subquery whose `parent` relation has columns with those same names. PostgreSQL binds both names to the inner row. The stored expression therefore contains:

~~~sql
parent.id = parent.parent_revision_id
and parent.canonical_artifact_id = parent.canonical_artifact_id
~~~

Revision 1 with a null parent takes the `parent_revision_id is null` branch. A later revision with a valid parent normally points to revision 1, whose own `parent_revision_id` is null; the old subquery finds no row and RLS rejects the INSERT with 42501. The repair removes this redundant parent lookup from RLS. `canonical_revisions_parent_same_artifact_fk` still requires the referenced parent to exist with the same `canonical_artifact_id` and `owner_id`.

## Read-only diagnostics for the deployed database

Run these queries in the Supabase SQL Editor before or after the repair. They read revision metadata and PostgreSQL catalogs only; they do not expose `canonical_json` or edit payloads.

### Revision rows

~~~sql
select id, owner_id, canonical_artifact_id, revision_seq, parent_revision_id, created_at
from public.canonical_revisions
order by canonical_artifact_id, revision_seq;
~~~

### RLS policy and PostgreSQL's parsed expression

~~~sql
select schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
from pg_policies
where schemaname = 'public'
  and tablename = 'canonical_revisions'
order by policyname;

select p.polname,
       pg_get_expr(p.polwithcheck, p.polrelid) as parsed_with_check
from pg_policy p
where p.polrelid = 'public.canonical_revisions'::regclass
order by p.polname;
~~~

### Constraints, indexes, RLS state, and role privileges

~~~sql
select conname, contype, pg_get_constraintdef(oid) as definition
from pg_constraint
where conrelid = 'public.canonical_revisions'::regclass
order by conname;

select indexname, indexdef
from pg_indexes
where schemaname = 'public'
  and tablename = 'canonical_revisions'
order by indexname;

select c.relrowsecurity as rls_enabled,
       c.relforcerowsecurity as rls_forced,
       pg_get_userbyid(c.relowner) as table_owner
from pg_class c
where c.oid = 'public.canonical_revisions'::regclass;

select rolname, rolsuper, rolbypassrls,
       has_table_privilege(rolname, 'public.canonical_revisions', 'INSERT') as can_insert,
       has_table_privilege(rolname, 'public.canonical_revisions', 'SELECT') as can_select
from pg_roles
where rolname in ('anon', 'authenticated', 'service_role', 'postgres')
order by rolname;
~~~

After the repair, `pg_policies` should show one `PERMISSIVE` `INSERT` policy for `{authenticated}`. Its parsed `WITH CHECK` must keep `owner_id = auth.uid()` and the owner-scoped artifact `EXISTS`, and must no longer contain a parent-revision subquery. The composite parent FK must still appear in `pg_constraint`; RLS must remain enabled.

## Transaction-scoped PostgreSQL RLS fixture

`canonical_revision_rls_smoke.sql` reproduces the deployed expression in an isolated schema, checks the revision-1 and valid revision-2 cases, replaces the fixture policy with the repaired shape, and checks foreign artifacts, foreign owners, cross-artifact parents, cross-owner parents, and `anon`. It uses Supabase's `authenticated`, `anon`, and `auth.uid()` but does not write public Evidence tables or `auth.users`; the fixture schema and rows are rolled back.

Run it against a disposable project or in the SQL Editor after checking that it ends with `ROLLBACK`:

~~~powershell
npx supabase db query --linked --file infra\\evidence-supabase\\tests\\canonical_revision_rls_smoke.sql --output-format json
~~~

The existing PowerShell/PostgREST smoke steps below create append-only rows and remain restricted to a disposable staging project.

## 1. Create an artifact owned by test user A

Set the project URL, publishable key, and test credentials in a local PowerShell session. Do not put a service-role or secret key in these requests.

~~~powershell
$baseUrl = "https://<staging-project>.supabase.co"
$publishableKey = "<publishable-key>"

$sessionA = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/v1/token?grant_type=password" -Headers @{ apikey = $publishableKey } -ContentType "application/json" -Body (@{ email = "<user-a-email>"; password = "<user-a-password>" } | ConvertTo-Json)
$sessionB = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/v1/token?grant_type=password" -Headers @{ apikey = $publishableKey } -ContentType "application/json" -Body (@{ email = "<user-b-email>"; password = "<user-b-password>" } | ConvertTo-Json)

function Get-Sha256Hex([string] $value) {
  $sha = [Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [Text.Encoding]::UTF8.GetBytes($value)
    return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

$nonce = [Guid]::NewGuid().ToString("N")
$canonicalJson = '{"schema_version":"yeonsik-ocr.v4"}'
$artifact = @{
  owner_id = $sessionA.user.id
  canonical_sha256 = (Get-Sha256Hex $canonicalJson)
  manifest_sha256 = (Get-Sha256Hex "manifest-$nonce")
  bundle_fingerprint = (Get-Sha256Hex "bundle-$nonce")
  schema_version = "yeonsik-ocr.v4"
  mode = "purchase"
  canonical_json = ($canonicalJson | ConvertFrom-Json)
  manifest_json = @{}
}

$headersA = @{
  apikey = $publishableKey
  Authorization = "Bearer $($sessionA.access_token)"
  Prefer = "return=representation"
}
$artifactRow = Invoke-RestMethod -Method Post -Uri "$baseUrl/rest/v1/canonical_artifacts?select=id" -Headers $headersA -ContentType "application/json" -Body ($artifact | ConvertTo-Json -Depth 20 -Compress)
$artifactId = @($artifactRow)[0].id
~~~

## 2. Confirm both ownership violations are denied

Use the same artifact ID and canonical revision payload for these requests. The first request makes owner_id disagree with the Bearer token subject. The second uses user B consistently but points at user A's artifact. Both must return HTTP 403 with Postgres code 42501, and neither may create a revision.

~~~powershell
$revision = @{
  canonical_artifact_id = $artifactId
  revision_seq = 1
  parent_revision_id = $null
  canonical_sha256 = (Get-Sha256Hex $canonicalJson)
  canonical_json = ($canonicalJson | ConvertFrom-Json)
  schema_version = "yeonsik-ocr.v4"
  mode = "purchase"
  validation_status = "valid"
  validation_issues = @()
}

$headersB = @{
  apikey = $publishableKey
  Authorization = "Bearer $($sessionB.access_token)"
  Prefer = "return=representation"
}

$ownerMismatch = $revision.Clone()
$ownerMismatch.owner_id = $sessionA.user.id
$artifactMismatch = $revision.Clone()
$artifactMismatch.owner_id = $sessionB.user.id

foreach ($body in @($ownerMismatch, $artifactMismatch)) {
  $denied = $false
  try {
    Invoke-RestMethod -Method Post -Uri "$baseUrl/rest/v1/canonical_revisions?select=id" -Headers $headersB -ContentType "application/json" -Body ($body | ConvertTo-Json -Depth 20 -Compress) | Out-Null
  } catch {
    $denied = $true
    if ([int]$_.Exception.Response.StatusCode -ne 403) { throw }
    # Inspect the response body and confirm code=42501.
  }
  if (-not $denied) { throw "Expected the owner mismatch to be rejected by RLS." }
}
~~~

## 3. Confirm the positive control

A matching user A token, owner ID, and artifact must insert one revision. This is append-only; use a fresh disposable staging project for another full run.

~~~powershell
$revision.owner_id = $sessionA.user.id
$headersA.Prefer = "return=representation"
$created = Invoke-RestMethod -Method Post -Uri "$baseUrl/rest/v1/canonical_revisions?select=id,owner_id,canonical_artifact_id,revision_seq" -Headers $headersA -ContentType "application/json" -Body ($revision | ConvertTo-Json -Depth 20 -Compress)

$createdRow = @($created)[0]
if ($createdRow.owner_id -ne $sessionA.user.id -or
    $createdRow.canonical_artifact_id -ne $artifactId -or
    $createdRow.revision_seq -ne 1) {
  throw "Positive control returned a different owner, artifact, or revision sequence."
}
~~~
