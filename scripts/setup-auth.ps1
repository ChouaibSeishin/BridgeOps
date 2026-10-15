$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $repoRoot 'secrets/auth'
$paths = @('access-private.pem','access-public.pem','csrf.key') | ForEach-Object { Join-Path $directory $_ }
foreach ($path in $paths) { if (Test-Path -LiteralPath $path) { throw 'Auth material already exists. Refusing to replace signing keys.' } }
[IO.Directory]::CreateDirectory($directory) | Out-Null
$rsa = [Security.Cryptography.RSA]::Create(3072)
try {
    $privatePem = $rsa.ExportPkcs8PrivateKeyPem()
    $publicPem = $rsa.ExportSubjectPublicKeyInfoPem()
    $csrfBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($csrfBytes) } finally { $rng.Dispose() }
    $contents = @([Text.Encoding]::ASCII.GetBytes($privatePem),[Text.Encoding]::ASCII.GetBytes($publicPem),$csrfBytes)
    for ($index = 0; $index -lt $paths.Count; $index++) {
        $stream = [IO.File]::Open($paths[$index],[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $stream.Write($contents[$index],0,$contents[$index].Length) } finally { $stream.Dispose() }
    }
} finally { $rsa.Dispose() }
Write-Output 'Created ignored local auth signing material. No key values were printed. Existing database credentials were unchanged.'