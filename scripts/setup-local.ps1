$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$target = Join-Path $repoRoot '.env'
if (Test-Path -LiteralPath $target) { throw '.env already exists. Refusing to replace existing credentials.' }
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
$content = "# Generated local-only credentials. Do not commit.`nPOSTGRES_PASSWORD=$(New-LocalSecret)`nDATABASE_PASSWORD=$(New-LocalSecret)`nMIGRATION_PASSWORD=$(New-LocalSecret)`nBRIDGEOPS_PORT=8088`n"
# Exclusive creation prevents a concurrent setup process from overwriting credentials.
$stream = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $bytes = [Text.Encoding]::UTF8.GetBytes($content)
    $stream.Write($bytes, 0, $bytes.Length)
} finally { $stream.Dispose() }
Write-Output 'Created .env with unique local credentials. Values were not printed.'
