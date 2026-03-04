param(
    [Parameter(Mandatory = $true)]
    [string]$ProfileName
)

$root = Split-Path -Parent $PSScriptRoot
$profileFile = Join-Path $root "version-profiles/$ProfileName.properties"
$targetFile = Join-Path $root "gradle.properties"

if (-not (Test-Path $profileFile)) {
    Write-Error "Profile not found: $profileFile"
    exit 1
}

$profileLines = Get-Content $profileFile | Where-Object { $_ -match '=' -and -not $_.Trim().StartsWith('#') }
$profileMap = @{}
foreach ($line in $profileLines) {
    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { continue }
    $key = $line.Substring(0, $idx).Trim()
    $value = $line.Substring($idx + 1)
    $profileMap[$key] = $value
}

$target = Get-Content $targetFile
for ($i = 0; $i -lt $target.Count; $i++) {
    $line = $target[$i]
    if ($line.Trim().StartsWith('#') -or -not ($line -match '=')) { continue }
    $idx = $line.IndexOf('=')
    $key = $line.Substring(0, $idx).Trim()
    if ($profileMap.ContainsKey($key)) {
        $target[$i] = "$key=$($profileMap[$key])"
    }
}

Set-Content -Path $targetFile -Value $target -Encoding UTF8
Write-Host "Applied profile: $ProfileName"
