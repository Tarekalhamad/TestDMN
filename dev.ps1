<#
.SYNOPSIS
    Build, test and run the discount-rules service.

.DESCRIPTION
    Finds a usable JDK itself, so a stale JAVA_HOME cannot stop the build, and runs Maven
    offline by default because everything this project needs is already cached and the
    corporate proxy intercepts TLS in a way Maven cannot authenticate through.

.EXAMPLE
    .\dev.ps1 build          # clean install with all tests
    .\dev.ps1 run            # start the service on http://localhost:8080
    .\dev.ps1 test rules     # run only the tests whose class name matches *rules*
    .\dev.ps1 doctor         # what is wrong with my environment
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('build', 'quick', 'test', 'run', 'jar', 'clean', 'rules', 'doctor', 'help')]
    [string]$Command = 'help',

    # For `test`: a substring of the test class to run, e.g. "RuleContract".
    [Parameter(Position = 1)]
    [string]$Filter,

    # Fetch from Maven Central instead of building offline. Needed only after adding a
    # dependency, and only works when the proxy lets Maven through.
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# --------------------------------------------------------------------------- JDK

function Find-Jdk {
    # A JAVA_HOME that actually exists wins.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return $env:JAVA_HOME
    }

    $roots = @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Amazon Corretto',
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )
    $found = @()
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        foreach ($dir in Get-ChildItem $root -Directory -ErrorAction SilentlyContinue) {
            if (Test-Path (Join-Path $dir.FullName 'bin\java.exe')) {
                $version = 0
                if ($dir.Name -match '(\d+)') { $version = [int]$Matches[1] }
                $found += [pscustomobject]@{ Path = $dir.FullName; Version = $version }
            }
        }
    }
    if ($found.Count -eq 0) { return $null }

    # The project targets Java 21, so prefer that; otherwise take the newest that can build it.
    $exact = $found | Where-Object { $_.Version -eq 21 } | Select-Object -First 1
    if ($exact) { return $exact.Path }
    $usable = $found | Where-Object { $_.Version -ge 21 } | Sort-Object Version | Select-Object -First 1
    if ($usable) { return $usable.Path }
    return ($found | Sort-Object Version -Descending | Select-Object -First 1).Path
}

function Use-Jdk {
    $jdk = Find-Jdk
    if (-not $jdk) {
        Write-Host "No JDK 21 or newer found." -ForegroundColor Red
        Write-Host "Install one (https://adoptium.net/temurin/releases/?version=21) and re-run."
        exit 1
    }
    if ($env:JAVA_HOME -ne $jdk) {
        Write-Host "JAVA_HOME -> $jdk" -ForegroundColor DarkGray
    }
    $env:JAVA_HOME = $jdk
    return $jdk
}

function Invoke-Maven([string[]]$Goals) {
    Use-Jdk | Out-Null
    $args = @()
    if (-not $Online) { $args += '-o' }
    $args += $Goals
    Write-Host "mvn $($args -join ' ')" -ForegroundColor DarkGray
    & .\mvnw.cmd @args
    if ($LASTEXITCODE -ne 0) {
        Write-Host ""
        if (-not $Online) {
            Write-Host "If this failed because an artifact is missing, the build needs to download it:" -ForegroundColor Yellow
            Write-Host "    .\dev.ps1 $Command -Online" -ForegroundColor Yellow
            Write-Host "That needs the proxy to let Maven through; see 'dev.ps1 doctor'." -ForegroundColor Yellow
        }
        exit $LASTEXITCODE
    }
}

# ---------------------------------------------------------------------- commands

switch ($Command) {

    'build' {
        # Everything: compile, validate the DMN, run all tests, package, install.
        Invoke-Maven @('clean', 'install')
        Write-Host ""
        Write-Host "Built target\discount-rules-1.0.0-SNAPSHOT.jar" -ForegroundColor Green
    }

    'quick' {
        Invoke-Maven @('clean', 'install', '-DskipTests')
        Write-Host ""
        Write-Host "Built without tests." -ForegroundColor Green
    }

    'test' {
        if ($Filter) {
            Invoke-Maven @('test', "-Dtest=*$Filter*")
        } else {
            Invoke-Maven @('test')
        }
    }

    'run' {
        Write-Host ""
        Write-Host "  Rule editor  http://localhost:8080/editor.html" -ForegroundColor Cyan
        Write-Host "  API docs     http://localhost:8080/swagger-ui.html" -ForegroundColor Cyan
        Write-Host "  Live rules   http://localhost:8080/api/rules" -ForegroundColor Cyan
        Write-Host "  Health       http://localhost:8080/actuator/health" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "  Ctrl+C to stop." -ForegroundColor DarkGray
        Write-Host ""
        Invoke-Maven @('spring-boot:run')
    }

    'jar' {
        $jar = 'target\discount-rules-1.0.0-SNAPSHOT.jar'
        if (-not (Test-Path $jar)) {
            Write-Host "No jar yet. Run '.\dev.ps1 build' first." -ForegroundColor Yellow
            exit 1
        }
        $jdk = Use-Jdk
        Write-Host "Running $jar" -ForegroundColor DarkGray
        & "$jdk\bin\java.exe" -jar $jar
    }

    'clean' {
        Invoke-Maven @('clean')
    }

    'rules' {
        # What the running service currently considers the rules to be.
        try {
            $r = Invoke-RestMethod -Uri 'http://localhost:8080/api/rules' -TimeoutSec 5
        } catch {
            Write-Host "The service is not answering on http://localhost:8080." -ForegroundColor Yellow
            Write-Host "Start it with '.\dev.ps1 run'."
            exit 1
        }
        Write-Host "rule set $($r.ruleSetVersion)" -ForegroundColor DarkGray
        Write-Host ""
        foreach ($table in 'CategoryIncompatibility', 'ItemIncompatibility', 'CategoryCardinality', 'Exceptions') {
            Write-Host $table -ForegroundColor Cyan
            $r.$table | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
        }
    }

    'doctor' {
        Write-Host "Environment" -ForegroundColor Cyan
        $jdk = Find-Jdk
        Write-Host ("  JAVA_HOME (this shell) : " + $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { '(not set)' }))
        Write-Host ("  JAVA_HOME (persisted)  : " + $([Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')))
        if ($env:JAVA_HOME -and -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
            Write-Host "  -> that path does not exist; this script ignores it and uses the one below." -ForegroundColor Yellow
        }
        Write-Host ("  JDK this script uses   : " + $(if ($jdk) { $jdk } else { 'NONE FOUND' })) -ForegroundColor $(if ($jdk) { 'Green' } else { 'Red' })
        # Read the version from the JDK's release file: `java -version` writes to stderr, and
        # PowerShell 5.1 turns that into an error record even on success.
        if ($jdk -and (Test-Path "$jdk\release")) {
            $line = Select-String -Path "$jdk\release" -Pattern '^JAVA_VERSION="?([^"]+)"?' -ErrorAction SilentlyContinue
            if ($line) { Write-Host ("      java " + $line.Matches[0].Groups[1].Value) -ForegroundColor DarkGray }
        }

        Write-Host ""
        Write-Host "Project" -ForegroundColor Cyan
        $jar = 'target\discount-rules-1.0.0-SNAPSHOT.jar'
        Write-Host ("  jar built              : " + $(if (Test-Path $jar) { "yes ($([math]::Round((Get-Item $jar).Length / 1MB, 1)) MB)" } else { 'no - run .\dev.ps1 build' }))
        Write-Host ("  rule model             : src\main\resources\PromotionCompatibility.dmn")
        Write-Host ("  live rule storage      : " + $(if (Test-Path 'dmn-storage') { 'dmn-storage\ (created; git-ignored)' } else { 'not yet created - seeded on first run' }))

        Write-Host ""
        Write-Host "Network" -ForegroundColor Cyan
        try {
            $null = Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/' -TimeoutSec 10 -UseBasicParsing -UseDefaultCredentials
            Write-Host "  Maven Central          : reachable from PowerShell" -ForegroundColor Green
        } catch {
            Write-Host "  Maven Central          : NOT reachable ($($_.Exception.Message))" -ForegroundColor Yellow
        }
        Write-Host "  Note: the corporate proxy intercepts TLS with a CA the JDK does not trust, so"
        Write-Host "  Maven itself may fail with 'PKIX path building failed' even when the line above"
        Write-Host "  says reachable. Everything needed is cached, which is why builds run offline."

        Write-Host ""
        Write-Host "Service" -ForegroundColor Cyan
        try {
            $h = Invoke-RestMethod -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3
            Write-Host "  localhost:8080         : $($h.status)" -ForegroundColor Green
        } catch {
            Write-Host "  localhost:8080         : not running" -ForegroundColor DarkGray
        }
    }

    default {
        Write-Host ""
        Write-Host "  discount-rules" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "  .\dev.ps1 build           compile, run all 42 tests, package the jar"
        Write-Host "  .\dev.ps1 quick           build without tests"
        Write-Host "  .\dev.ps1 test            run the tests"
        Write-Host "  .\dev.ps1 test RuleContract   run one test class (substring match)"
        Write-Host "  .\dev.ps1 run             start the service on http://localhost:8080"
        Write-Host "  .\dev.ps1 jar             run the already-built jar"
        Write-Host "  .\dev.ps1 rules           print the rules the running service is using"
        Write-Host "  .\dev.ps1 clean           delete target\"
        Write-Host "  .\dev.ps1 doctor          check JDK, build, network and service"
        Write-Host ""
        Write-Host "  Add -Online to any of them to allow downloading new dependencies." -ForegroundColor DarkGray
        Write-Host ""
        Write-Host "  Once running:" -ForegroundColor DarkGray
        Write-Host "    http://localhost:8080/editor.html     edit the rules, no redeploy" -ForegroundColor DarkGray
        Write-Host "    http://localhost:8080/swagger-ui.html the API" -ForegroundColor DarkGray
        Write-Host ""
    }
}
