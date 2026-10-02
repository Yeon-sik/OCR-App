# Canonical revision owner RLS smoke check

Run this only against a disposable Supabase staging project with two test users. A successful request writes an append-only revision, so do not use production data. Apply the existing Evidence migrations through 20260916_canonical_revision_store.sql first.

This check exercises the deployed PostgREST/Auth/RLS boundary. The automated JVM tests use an HTTP transport fixture and do not claim live database coverage.

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
