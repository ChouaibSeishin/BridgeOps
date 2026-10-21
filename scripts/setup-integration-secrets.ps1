param([ValidatePattern('^[a-zA-Z0-9_-]{1,48}$')][string]$KeyId = 'local-v1')
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$secretDirectory = Join-Path $repositoryRoot 'secrets/integrations'
$keyPath = Join-Path $secretDirectory ($KeyId + '.key')
if (Test-Path -LiteralPath $keyPath) { throw 'Encryption key already exists. Refusing to replace existing material.' }
[IO.Directory]::CreateDirectory($secretDirectory) | Out-Null
if ($IsWindows) {
    $acl = Get-Acl -LiteralPath $secretDirectory
    $acl.SetAccessRuleProtection($true, $false)
    $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    foreach ($identity in @([Security.Principal.WindowsIdentity]::GetCurrent().User, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, [Security.AccessControl.FileSystemRights]::FullControl, $inheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $secretDirectory -AclObject $acl
} else {
    [IO.File]::SetUnixFileMode($secretDirectory, [IO.UnixFileMode]'UserRead,UserWrite,UserExecute')
}
$bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
try {
    $stream = [IO.File]::Open($keyPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes); $stream.Flush($true) } finally { $stream.Dispose() }
    if (-not $IsWindows) { [IO.File]::SetUnixFileMode($keyPath, [IO.UnixFileMode]'UserRead,UserWrite') }
} finally { [Array]::Clear($bytes) }
Write-Output 'Created a new ignored integration encryption key with restricted host access. No values printed. Existing environment, signing/CSRF keys and database were unchanged.'
