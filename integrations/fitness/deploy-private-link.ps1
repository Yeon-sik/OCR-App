param([Parameter(Mandatory = $true)][string]$SupabaseCli, [switch]$Apply)
$ErrorActionPreference = 'Stop'
$deploymentPath = Join-Path $env:TEMP ('fitness-private-link-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $deploymentPath 'supabase/migrations') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'supabase/config.toml') -Destination (Join-Path $deploymentPath 'supabase/config.toml')

# This is the verified Nutrition project, NOT the OCR Evidence project.
& $SupabaseCli migration fetch --linked --project-ref eytwkufdesbarxusifzt --workdir $deploymentPath --yes
if ($LASTEXITCODE -ne 0) { throw 'Remote migration history fetch failed.' }
$migrationFile = '20261013100505_private_ocr_purchase_nutrition_link.sql'
$sourcePath = Join-Path $PSScriptRoot ('supabase/migrations/' + $migrationFile)
$targetPath = Join-Path $deploymentPath ('supabase/migrations/' + $migrationFile)
if (!(Test-Path -LiteralPath $targetPath)) {
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath
    if ((Get-FileHash -LiteralPath $sourcePath).Hash -ne (Get-FileHash -LiteralPath $targetPath).Hash) { throw 'Deployment copy mismatch.' }
} else { Write-Output ('Already recorded: ' + $migrationFile) }
$pushArguments = @('db', 'push', '--linked', '--project-ref', 'eytwkufdesbarxusifzt', '--workdir', $deploymentPath, '--include-all', '--skip-vault')
if ($Apply) { $pushArguments += '--yes' } else { $pushArguments += '--dry-run' }
& $SupabaseCli @pushArguments
if ($LASTEXITCODE -ne 0) { throw 'Migration push/dry-run failed.' }
