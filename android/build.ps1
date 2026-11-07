param(
    [string]$SdkDirectory = (Join-Path $PSScriptRoot '..\..\work\android-sdk'),
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '..\..\work\hud-build')
)

$ErrorActionPreference = 'Stop'
$SdkDirectory = [System.IO.Path]::GetFullPath($SdkDirectory)
$BuildDirectory = [System.IO.Path]::GetFullPath($BuildDirectory)
$buildTools = Join-Path $SdkDirectory 'android-15'
$androidJar = Join-Path $SdkDirectory 'android-35\android.jar'
$javaDirectory = Split-Path (Get-Command javac.exe).Source
$classesDirectory = Join-Path $BuildDirectory 'classes'
$dexDirectory = Join-Path $BuildDirectory 'dex'
$resourceSources = Join-Path $BuildDirectory 'resources'
$unsignedApk = Join-Path $BuildDirectory 'unsigned.apk'
$alignedApk = Join-Path $BuildDirectory 'aligned.apk'
$classesJar = Join-Path $BuildDirectory 'classes.jar'
$keyStore = Join-Path $SdkDirectory 'hud-development.keystore'
$signedApk = Join-Path $PSScriptRoot 'insight-hud.apk'

function Invoke-BuildTool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}

New-Item -ItemType Directory -Path $classesDirectory, $dexDirectory, $resourceSources -Force | Out-Null
Invoke-BuildTool (Join-Path $buildTools 'aapt.exe') @('package', '-f', '-M', (Join-Path $PSScriptRoot 'AndroidManifest.xml'), '-S', (Join-Path $PSScriptRoot 'res'), '-J', $resourceSources, '-I', $androidJar, '-F', $unsignedApk)
$sourceFiles = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src'), $resourceSources -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName
Invoke-BuildTool (Join-Path $javaDirectory 'javac.exe') (@('--release', '8', '-classpath', $androidJar, '-d', $classesDirectory) + $sourceFiles)
Invoke-BuildTool (Join-Path $javaDirectory 'jar.exe') @('cf', $classesJar, '-C', $classesDirectory, '.')
Invoke-BuildTool (Join-Path $javaDirectory 'java.exe') @('-cp', (Join-Path $buildTools 'lib\d8.jar'), 'com.android.tools.r8.D8', '--lib', $androidJar, '--min-api', '28', '--output', $dexDirectory, $classesJar)
Invoke-BuildTool (Join-Path $javaDirectory 'jar.exe') @('uf', $unsignedApk, '-C', $dexDirectory, 'classes.dex')
Invoke-BuildTool (Join-Path $buildTools 'zipalign.exe') @('-f', '4', $unsignedApk, $alignedApk)

# Keep this development key so subsequent local builds can update the installed app.
if (-not (Test-Path -LiteralPath $keyStore)) {
    Invoke-BuildTool (Join-Path $javaDirectory 'keytool.exe') @('-genkeypair', '-keystore', $keyStore, '-storepass', 'android', '-keypass', 'android', '-alias', 'insight-hud', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Insight HUD Development')
}
Invoke-BuildTool (Join-Path $javaDirectory 'java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'sign', '--ks', $keyStore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $signedApk, $alignedApk)
Invoke-BuildTool (Join-Path $javaDirectory 'java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'verify', '--verbose', $signedApk)
Write-Output "Built $signedApk"
