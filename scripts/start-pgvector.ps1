$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pgHome = Join-Path $root '.local/postgresql-16.2/pgsql'
$dataDir = Join-Path $root '.local/pgvector/data'
$logFile = Join-Path $root '.local/pgvector/postgresql.log'
$pgCtl = Join-Path $pgHome 'bin/pg_ctl.exe'
$psql = Join-Path $pgHome 'bin/psql.exe'
$createdb = Join-Path $pgHome 'bin/createdb.exe'

if (!(Test-Path $pgCtl)) {
  throw "PostgreSQL binaries not found at $pgCtl"
}

if (!(Test-Path $dataDir)) {
  throw "PostgreSQL data directory not found at $dataDir"
}

& $pgCtl -D $dataDir status *> $null
if ($LASTEXITCODE -ne 0) {
  & $pgCtl -D $dataDir -l $logFile start | Out-Null
  Start-Sleep -Seconds 2
}

$databaseExists = (& $psql -h 127.0.0.1 -p 5433 -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'wolfbook_ai'").Trim()
if ($databaseExists -ne '1') {
  & $createdb -h 127.0.0.1 -p 5433 -U postgres wolfbook_ai
}

& $psql -h 127.0.0.1 -p 5433 -U postgres -d wolfbook_ai -c "CREATE EXTENSION IF NOT EXISTS vector;" | Out-Null
Write-Host "pgvector is running on 127.0.0.1:5433 / database wolfbook_ai"
