param([switch]$SkipGameTests)

$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
if (-not $java -or -not (Test-Path -LiteralPath $java)) {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1
    if (-not $jdk) { throw 'Set JAVA_HOME to a Java 17 JDK before building.' }
    $env:JAVA_HOME = $jdk.FullName
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
}
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
Push-Location -LiteralPath $PSScriptRoot
try {
    & $java 'tools\GenerateAssets.java'
    if ($LASTEXITCODE -ne 0) { throw 'Original asset generation failed.' }
    $tasks = @('build')
    if (-not $SkipGameTests) { $tasks += 'runGameTestServer' }
    $tasks += 'release'
    & '.\gradlew.bat' --no-daemon --console=plain @tasks
    if ($LASTEXITCODE -ne 0) { throw "Frontier Survival build failed ($LASTEXITCODE)." }
    Write-Output "Installable mod, source bundle and guide are in $PSScriptRoot\dist"
} finally {
    Pop-Location
}
