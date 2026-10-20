$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
if (Test-Path -LiteralPath '.env') {
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
        if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
        $parts = $line.Split(@('='), 2)
        if ($parts.Length -ne 2 -or $parts[0].Trim() -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid .env line' }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}
foreach ($required in @('DB_PASSWORD', 'SMTP_USERNAME', 'SMTP_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($required))) { throw "Set $required in .env" }
}
if (Get-Command mvn -ErrorAction SilentlyContinue) { & mvn spring-boot:run }
else { & (Join-Path $projectRoot 'mvnw.cmd') spring-boot:run }
exit $LASTEXITCODE
