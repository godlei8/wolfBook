param(
  [string]$Profile = 'test'
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# 优先用环境里已有的 JAVA_HOME，没有再退回这台机器上的默认安装路径
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome) -or !(Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
  $javaHome = 'C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot'
}
$mavenWrapper = Join-Path $root 'backend\mvnw.cmd'

if (!(Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
  throw "Java 21 not found. Set JAVA_HOME or install it at $javaHome"
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
