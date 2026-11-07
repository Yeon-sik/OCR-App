param(
    [Parameter(Mandatory = $true)][string]$LinkedRepository,
    [Parameter(Mandatory = $true)][string]$MigrationFile,
    [Parameter(Mandatory = $true)][string]$ExpectedProjectRef,
    [Parameter(Mandatory = $true)][string]$SupabaseCli,
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
$linkedRoot = (Resolve-Path -LiteralPath $LinkedRepository).Path
$migration = (Resolve-Path -LiteralPath $MigrationFile).Path
$cli = (Resolve-Path -LiteralPath $SupabaseCli).Path
$projectRefFile = Join-Path $linkedRoot 'supabase/.temp/project-ref'
$actualRef = (Get-Content -LiteralPath $projectRefFile -Raw).Trim()
if ($actualRef -ne $ExpectedProjectRef) { throw 'Linked project differs from the expected project. Nothing was changed.' }
$migrationName = Split-Path -Leaf $migration
if ($migrationName -notmatch '^\d{14}_[a-z0-9_]+\.sql$') { throw 'Expected one CLI-generated timestamp migration.' }

# Preserve real hosted migration history. Never bulk-push a drifted checkout or fabricate baseline records.
$stagingRoot = Join-Path $env:TEMP ('information-migration-' + [guid]::NewGuid().ToString('N'))
$stagedMigrations = Join-Path $stagingRoot 'supabase/migrations'
New-Item -ItemType Directory -Path $stagedMigrations -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stagingRoot 'supabase/.temp') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $linkedRoot 'supabase/config.toml') -Destination (Join-Path $stagingRoot 'supabase/config.toml')
Copy-Item -LiteralPath $projectRefFile -Destination (Join-Path $stagingRoot 'supabase/.temp/project-ref')
foreach ($cacheName in @('linked-project.json', 'pooler-url', 'postgres-version')) {
    $cacheSource = Join-Path $linkedRoot ('supabase/.temp/' + $cacheName)
    if (Test-Path -LiteralPath $cacheSource) { Copy-Item -LiteralPath $cacheSource -Destination (Join-Path $stagingRoot ('supabase/.temp/' + $cacheName)) }
}
$fetchLog = Join-Path $stagingRoot 'migration-fetch.log'
& $cli migration fetch --linked --workdir $stagingRoot --yes *> $fetchLog
if ($LASTEXITCODE -ne 0) { throw 'Could not fetch real migration history. Nothing was applied.' }
$version = $migrationName.Substring(0, 14)
$alreadyRecorded = Get-ChildItem -LiteralPath $stagedMigrations -Filter ($version + '_*.sql')
if ($alreadyRecorded) { throw 'This migration version is already recorded remotely. Verify the live schema instead of applying again.' }
$stagedFile = Join-Path $stagedMigrations $migrationName
Copy-Item -LiteralPath $migration -Destination $stagedFile
if ((Get-FileHash -LiteralPath $migration -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $stagedFile -Algorithm SHA256).Hash) { throw 'Migration copy hash mismatch.' }
Write-Output ('Deployment staging: ' + $stagingRoot)
Write-Output ('Only new migration: ' + $migrationName)
& $cli db push --linked --workdir $stagingRoot --dry-run
if ($LASTEXITCODE -ne 0) { throw 'Targeted migration dry run failed. Nothing was applied.' }
if ($Apply) {
    & $cli db push --linked --workdir $stagingRoot --yes
    if ($LASTEXITCODE -ne 0) { throw 'Migration apply failed. Inspect the hosted migration history before retrying.' }
}
