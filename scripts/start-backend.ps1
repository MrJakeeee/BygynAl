param(
    [switch]$SkipBootstrapAdmin
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
if (Test-Path -LiteralPath '.env') {
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
        if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { throw 'Invalid .env line' }
        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($name -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid .env line' }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}
if ($SkipBootstrapAdmin) {
    [Environment]::SetEnvironmentVariable('ADMIN_EMAIL', '', 'Process')
    [Environment]::SetEnvironmentVariable('ADMIN_PASSWORD', '', 'Process')
}
foreach ($required in @('DB_PASSWORD', 'SMTP_USERNAME', 'SMTP_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($required))) { throw "Set $required in .env" }
}
if (Get-Command mvn -ErrorAction SilentlyContinue) { & mvn spring-boot:run }
else { & (Join-Path $projectRoot 'mvnw.cmd') spring-boot:run }
exit $LASTEXITCODE
