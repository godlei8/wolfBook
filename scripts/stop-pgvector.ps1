$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pgCtl = Join-Path $root '.local/postgresql-16.2/pgsql/bin/pg_ctl.exe'
$dataDir = Join-Path $root '.local/pgvector/data'

if (!(Test-Path $pgCtl) -or !(Test-Path $dataDir)) {
  Write-Host 'pgvector local instance is not installed.'
  exit 0
}

& $pgCtl -D $dataDir stop | Out-Null
Write-Host 'pgvector local instance stopped.'
