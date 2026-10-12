param(
    [string[]]$Tasks = @(':core:domain:test', ':app:assembleDebug', ':app:lintDebug'),
    [string]$SdkPath = '',
    [string]$JavaPath = ''
)
$ErrorActionPreference = 'Stop'
$projectPath = [IO.Path]::GetFullPath($PSScriptRoot)
$toolPath = Join-Path (Split-Path $projectPath -Parent) '.tools'
if (!$SdkPath) { $SdkPath = Join-Path $toolPath 'android-sdk' }
if (!$JavaPath) { $JavaPath = $env:JAVA_HOME }
if (!$JavaPath) { $JavaPath = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot' }
if (!(Test-Path -LiteralPath (Join-Path $SdkPath 'platform-tools'))) { throw '请通过 -SdkPath 指定已安装的 Android SDK。' }
if (!(Test-Path -LiteralPath (Join-Path $JavaPath 'bin\java.exe'))) { throw '请通过 -JavaPath 指定 Java 21。' }
$hash = [Security.Cryptography.SHA256]::Create()
try { $key = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectPath)))).Replace('-', '').Substring(0, 12).ToLowerInvariant() }
finally { $hash.Dispose() }
function Get-AsciiAlias([string]$Name, [string]$Target) {
    $resolvedTarget = (Resolve-Path -LiteralPath $Target).Path
    $aliasPath = Join-Path ([IO.Path]::GetTempPath()) "van-$key-$Name"
    if (Test-Path -LiteralPath $aliasPath) {
        $existing = Get-Item -LiteralPath $aliasPath
        $existingTarget = @($existing.Target)[0]
        if (!$existingTarget -or [IO.Path]::GetFullPath($existingTarget) -ne $resolvedTarget) { throw "路径别名已被其他文件占用：$aliasPath" }
    } else { New-Item -ItemType Junction -Path $aliasPath -Target $resolvedTarget | Out-Null }
    return $aliasPath
}
New-Item -ItemType Directory -Path (Join-Path $toolPath 'gradle-home') -Force | Out-Null
$projectAlias = Get-AsciiAlias 'project' $projectPath
$sdkAlias = Get-AsciiAlias 'sdk' $SdkPath
$cacheAlias = Get-AsciiAlias 'gradle' (Join-Path $toolPath 'gradle-home')
$propertyFile = Join-Path $projectPath 'local.properties'
$properties = if (Test-Path -LiteralPath $propertyFile) { [IO.File]::ReadAllText($propertyFile) } else { '' }
$sdkProperty = 'sdk.dir=' + $sdkAlias.Replace('\', '/').Replace(':', '\:')
if ($properties -match '(?m)^sdk\.dir=.*$') { $properties = [regex]::Replace($properties, '(?m)^sdk\.dir=.*$', { $sdkProperty }) }
else { $properties = $properties.TrimEnd() + [Environment]::NewLine + $sdkProperty + [Environment]::NewLine }
[IO.File]::WriteAllText($propertyFile, $properties)
$env:JAVA_HOME = $JavaPath
$gradleCommand = Join-Path $toolPath 'gradle-9.3.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradleCommand)) { $gradleCommand = Join-Path $projectPath 'gradlew.bat' }
& $gradleCommand "--project-dir=$projectAlias" "--gradle-user-home=$cacheAlias" @Tasks --no-daemon
if ($LASTEXITCODE -ne 0) { throw "构建失败，退出码 $LASTEXITCODE" }
