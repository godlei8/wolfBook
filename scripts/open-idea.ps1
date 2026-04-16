param(
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")

function Find-IdeaExecutable {
    if ($env:IDEA_EXE -and (Test-Path -LiteralPath $env:IDEA_EXE)) {
        return (Resolve-Path -LiteralPath $env:IDEA_EXE).Path
    }

    $commands = @("idea64.exe", "idea.exe", "idea")
    foreach ($command in $commands) {
        $resolved = Get-Command $command -ErrorAction SilentlyContinue
        if ($resolved -and $resolved.Source -and (Test-Path -LiteralPath $resolved.Source)) {
            return $resolved.Source
        }
    }

    $searchRoots = @(
        "C:\Program Files\JetBrains",
        "C:\Program Files (x86)\JetBrains",
        (Join-Path $env:LOCALAPPDATA "Programs"),
        (Join-Path $env:LOCALAPPDATA "JetBrains"),
        (Join-Path $env:APPDATA "JetBrains")
    )

    foreach ($root in $searchRoots) {
        if (-not (Test-Path -LiteralPath $root)) {
            continue
        }

        $match = Get-ChildItem -LiteralPath $root -Recurse -Filter "idea64.exe" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($match) {
            return $match.FullName
        }
    }

    throw "IntelliJ IDEA was not found. Set IDEA_EXE to the full path of idea64.exe, then run this script again."
}

$ideaExe = Find-IdeaExecutable
$projectPath = $projectRoot.Path

Write-Host "IDEA: $ideaExe"
Write-Host "Project: $projectPath"

if ($DryRun) {
    exit 0
}

Start-Process -FilePath $ideaExe -ArgumentList @($projectPath) -WorkingDirectory $projectPath
