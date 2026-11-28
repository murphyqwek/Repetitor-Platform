#Requires -Version 7.0

$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath $PSScriptRoot
try {
    docker compose version --short | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is required.' }

    if (!(Test-Path -LiteralPath '.env')) {
        $secretNames = @(
            'POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'EDUCATION_DB_PASSWORD', 'MINIO_ROOT_PASSWORD',
            'EDUCATION_S3_SECRET_KEY', 'EDUCATION_CURSOR_KEY', 'ASSIGNMENT_DB_PASSWORD',
            'ASSIGNMENT_S3_SECRET_KEY', 'ASSIGNMENT_CURSOR_KEY', 'ASSIGNMENT_IDENTITY_SIGNING_KEY',
            'ASSIGNMENT_EDUCATION_SIGNING_KEY', 'STUDENT_CREDENTIALS_KEY'
        )
        $dotenv = Get-Content -LiteralPath '.env.example' -Raw
        foreach ($name in $secretNames) {
            $secret = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
            $dotenv = $dotenv -replace "(?m)^$name=(?=`r?$)", "$name=$secret"
        }
        New-Item -ItemType File -Path '.env' -Value $dotenv | Out-Null
        if (!$IsWindows) {
            chmod 600 .env
            if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict permissions on .env.' }
        }
    }

    $keyDir = Join-Path $PSScriptRoot '.local-docker'
    $privateKey = Join-Path $keyDir 'jwt_private.pem'
    $publicKey = Join-Path $keyDir 'jwt_public.pem'
    $hasPrivateKey = Test-Path -LiteralPath $privateKey -PathType Leaf
    $hasPublicKey = Test-Path -LiteralPath $publicKey -PathType Leaf
    if ($hasPrivateKey -xor $hasPublicKey) { throw 'The JWT key pair in .local-docker is incomplete.' }
    if (!$hasPrivateKey) {
        New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
        if (!$IsWindows) {
            chmod 700 $keyDir
            if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict permissions on .local-docker.' }
        }
        $rsa = [Security.Cryptography.RSA]::Create(2048)
        try {
            [IO.File]::WriteAllText($privateKey, $rsa.ExportPkcs8PrivateKeyPem())
            [IO.File]::WriteAllText($publicKey, $rsa.ExportSubjectPublicKeyInfoPem())
        }
        finally { $rsa.Dispose() }
        if (!$IsWindows) {
            # The directory protects the host files; the containers mount the individual files.
            chmod 644 $privateKey $publicKey
            if ($LASTEXITCODE -ne 0) { throw 'Unable to set permissions on the local JWT keys.' }
        }
    }

    docker compose up -d --build --wait --wait-timeout 240
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed to start the local stack.' }
    Write-Host 'Platform: http://localhost:8090'
}
finally { Pop-Location }
