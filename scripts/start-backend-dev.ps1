param(
  [string]$Profile = 'test'
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = 'C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot'
$mavenWrapper = Join-Path $root 'backend\mvnw.cmd'

if (!(Test-Path $javaHome)) {
  throw "Java 21 not found at $javaHome"
}

if (!(Test-Path $mavenWrapper)) {
  throw "Maven wrapper not found at $mavenWrapper"
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

Push-Location (Join-Path $root 'backend')
try {
  & $mavenWrapper "spring-boot:run" "-Dspring-boot.run.profiles=$Profile"
}
finally {
  Pop-Location
}
