[CmdletBinding()]
param(
    [switch]$Offline,
    [switch]$RefreshDependencies,
    [int]$MaxAttempts = 3
)

$ErrorActionPreference = "Stop"

if ($Offline -and $RefreshDependencies) {
    throw "-Offline and -RefreshDependencies cannot be used together."
}
if ($MaxAttempts -lt 1) {
    throw "-MaxAttempts must be at least 1."
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\")).Path
$gradle = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "Gradle wrapper not found: $gradle"
}

$gradleArgs = @(
    ":app:packageQa",
    "--no-configuration-cache",
    "--project-prop", "kotlin.compiler.execution.strategy=in-process",
    "--no-daemon",
    "--console=plain"
)
if ($Offline) {
    $gradleArgs += "--offline"
}
if ($RefreshDependencies) {
    $gradleArgs += "--refresh-dependencies"
}

$networkFailurePattern = "plugin .* was not found|could not resolve|could not get resource|could not download|connection reset|connection timed out|unknownhost|temporary failure|failed to connect|received fatal alert"
$logRoot = Join-Path ([System.IO.Path]::GetTempPath()) "rikkahub-qa-build"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
    $logPath = Join-Path $logRoot ("attempt-{0}.log" -f $attempt)
    Write-Host "QA build attempt $attempt/$MaxAttempts"

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $gradle @gradleArgs 2>&1 | Tee-Object -FilePath $logPath
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($exitCode -eq 0) {
        Write-Host "QA build succeeded. Logs: $logRoot"
        exit 0
    }

    $logText = Get-Content -Raw -LiteralPath $logPath
    $isNetworkFailure = $logText -match $networkFailurePattern
    if (-not $isNetworkFailure -or $attempt -eq $MaxAttempts) {
        Write-Error "QA build failed (exit code $exitCode). Full log: $logPath"
        exit $exitCode
    }

    $delaySeconds = 15 * $attempt
    Write-Warning "Dependency/network resolution failed; retrying in $delaySeconds seconds."
    Start-Sleep -Seconds $delaySeconds
}
