[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$PublicBaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$VersionName,

    [Parameter(Mandatory = $true)]
    [long]$VersionCode,

    [string]$ReleaseNotes = "",

    [string]$PackageName = "com.example.qingzhang",

    [string]$OutputDirectory = (Join-Path $PSScriptRoot "public")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK not found: $ApkPath"
}
$baseUri = $null
if (-not [Uri]::TryCreate($PublicBaseUrl, [UriKind]::Absolute, [ref]$baseUri) -or
    $baseUri.Scheme -ne "https" -or -not $baseUri.Host -or $baseUri.UserInfo -or $baseUri.Query -or $baseUri.Fragment) {
    throw "PublicBaseUrl must be an HTTPS base URL with a host and without credentials, query or fragment."
}
if ($VersionCode -le 0) {
    throw "VersionCode must be greater than zero."
}
if ($VersionName -notmatch '^[0-9A-Za-z._-]{1,32}$') {
    throw "VersionName must be 1 to 32 letters, digits, dots, underscores or hyphens."
}

$apk = Get-Item -LiteralPath $ApkPath
if ($apk.Length -le 0 -or $apk.Length -gt 104857600) {
    throw "APK size must be between 1 byte and 100 MiB."
}

$aapt2 = Get-Command aapt2 -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty Source
if (-not $aapt2) {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $PSScriptRoot "../.android-sdk"))) {
        if (-not $sdk) { continue }
        $aapt2 = Get-ChildItem -Path (Join-Path $sdk "build-tools/*/aapt2.exe") -File -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
        if ($aapt2) { break }
    }
}
if (-not $aapt2) { throw "Install Android SDK Build Tools and put aapt2 on PATH." }

$badging = & $aapt2 dump badging $apk.FullName
if ($LASTEXITCODE -ne 0) { throw "Could not read APK metadata." }
$packageLine = [string]($badging | Where-Object { $_ -like "package:*" } | Select-Object -First 1)
if ($packageLine -notmatch "^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'" -or
    $Matches[1] -cne $PackageName -or [long]$Matches[2] -ne $VersionCode -or $Matches[3] -cne $VersionName) {
    throw "APK package name, version code or version name does not match the publishing arguments."
}

$fileName = "qingzhang-$VersionName.apk"
$baseUrl = $baseUri.AbsoluteUri.TrimEnd('/')
$apkUrl = "$baseUrl/" + [Uri]::EscapeDataString($fileName)
if ($apkUrl.Length -gt 2000) { throw "APK URL must be at most 2000 characters." }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$destination = Join-Path $OutputDirectory $fileName
Copy-Item -LiteralPath $apk.FullName -Destination $destination -Force

$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $destination).Length

$manifest = [ordered]@{
    packageName  = $PackageName
    versionCode  = $VersionCode
    versionName  = $VersionName
    apkUrl       = $apkUrl
    sha256       = $hash
    size         = $size
    releaseNotes = $ReleaseNotes
}

$json = $manifest | ConvertTo-Json -Depth 3
$manifestPath = Join-Path $OutputDirectory "latest.json"
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($manifestPath, $json, $utf8WithoutBom)

Write-Host "Generated manifest: $manifestPath"
Write-Host "Generated APK:      $destination"
Write-Host "SHA-256:            $hash"
Write-Host "Next: upload the contents of '$OutputDirectory' to your HTTPS server."
Write-Host "Configure the app update URL as: $baseUrl/latest.json"
