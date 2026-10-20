# Run from PowerShell: .\start-local.ps1
# Starts both services in the background; output is saved under .run/.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$logs = Join-Path $root '.run'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

function Test-Endpoint([string]$Url) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Wait-Endpoint([string]$Url, $Process, [string]$LogName) {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if ($Process.HasExited) {
            throw "$LogName exited. See $logs/$LogName.log and $logs/$LogName.err.log."
        }
        if (Test-Endpoint $Url) { return }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $Url. See $logs/$LogName.log."
}

if (-not (Test-Endpoint 'http://localhost:5080/api/employees')) {
    # Prefer JAVA_HOME, then a locally installed JDK instead of a Java PATH shim.
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
        $jdk = Get-ChildItem "$env:ProgramFiles/Java/jdk-*" -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if (-not $jdk) { throw 'Install JDK 21 and set JAVA_HOME to its directory.' }
        $env:JAVA_HOME = $jdk.FullName
    }
    $env:Path = "$env:JAVA_HOME/bin;$env:Path"
    $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
    Push-Location (Join-Path $root 'backend')
    try {
        & $maven -B -Plocal -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }

    $api = Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList @(
        # Windows NIO sockets need a short, accessible temporary directory.
        "-Djdk.net.unixdomain.tmpdir=`"$logs`"",
        '-jar', 'target/leave-management-1.0.0.jar', '--spring.profiles.active=local'
    ) -WorkingDirectory "$root/backend" -WindowStyle Hidden -PassThru `
      -RedirectStandardOutput "$logs/backend.log" -RedirectStandardError "$logs/backend.err.log"
    Write-Host "Starting backend (PID $($api.Id))..."
    Wait-Endpoint 'http://localhost:5080/api/employees' $api 'backend'
}

$webUrl = 'http://localhost:4200'
$webRunning = Test-Endpoint $webUrl
if ($webRunning) {
    $page = Invoke-WebRequest -Uri $webUrl -UseBasicParsing -TimeoutSec 2
    if ($page.Content -notmatch '<title>Leave Management</title>') {
        throw 'Port 4200 is already used by another application.'
    }
}
if (-not $webRunning) {
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    if (-not (Test-Path "$root/frontend/node_modules/@angular/cli/bin/ng.js")) {
        Push-Location (Join-Path $root 'frontend')
        try {
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        } finally { Pop-Location }
    }
    $web = Start-Process -FilePath $node -ArgumentList @(
        'node_modules/@angular/cli/bin/ng.js', 'serve', '--host', 'localhost', '--port', '4200'
    ) -WorkingDirectory "$root/frontend" -WindowStyle Hidden -PassThru `
      -RedirectStandardOutput "$logs/frontend.log" -RedirectStandardError "$logs/frontend.err.log"
    Write-Host "Starting frontend (PID $($web.Id))..."
    Wait-Endpoint $webUrl $web 'frontend'
}

Write-Host "Application: $webUrl"
Write-Host 'Swagger:     http://localhost:5080/swagger-ui.html'
Write-Host "Logs:        $logs"
