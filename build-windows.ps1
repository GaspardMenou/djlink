$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
python build.py
if ($LASTEXITCODE -ne 0) { throw 'La compilation Java a échoué.' }
$taskJavaBin = Split-Path (Get-Command javac).Source
$taskRuntime = Join-Path $PSScriptRoot 'target/windows-runtime'
if (-not (Test-Path $taskRuntime)) {
    & (Join-Path $taskJavaBin 'jlink.exe') --add-modules java.base,java.desktop,java.logging,java.sql,java.naming,java.net.http,jdk.httpserver,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output $taskRuntime
    if ($LASTEXITCODE -ne 0) { throw 'La création du runtime a échoué.' }
}
$taskInput = Join-Path $PSScriptRoot 'target/windows-input'
New-Item -ItemType Directory -Force $taskInput | Out-Null
Copy-Item 'target/djlink-1.0.0.jar' $taskInput -Force
Copy-Item 'README.md','LICENSE','THIRD_PARTY.md' $taskInput -Force
$taskDestination = Join-Path $PSScriptRoot 'target/windows'
# jpackage refuses to overwrite an existing image. Rename it to retain a recoverable previous build.
$taskApp = Join-Path $taskDestination 'DJLink'
if (Test-Path $taskApp) { Move-Item $taskApp ($taskApp + '-previous-' + (Get-Date -Format 'yyyyMMddHHmmss')) }
& (Join-Path $taskJavaBin 'jpackage.exe') --type app-image --name DJLink --input $taskInput --main-jar djlink-1.0.0.jar --main-class local.djlink.WindowsLauncher --runtime-image $taskRuntime --dest $taskDestination --app-version 1.0.0 --vendor DJLink
if ($LASTEXITCODE -ne 0) { throw 'La création de l’application a échoué.' }
Compress-Archive -Path $taskApp -DestinationPath 'target/DJLink-Windows.zip' -Force
Write-Host 'Application : target/windows/DJLink/DJLink.exe ; archive : target/DJLink-Windows.zip'
