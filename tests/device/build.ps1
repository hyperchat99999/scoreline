param([string]$Sdk = '', [string]$TargetBuild = '')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$jdkPath = (Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
if (!$Sdk) { $Sdk = if ($env:SDK) { $env:SDK } elseif (Test-Path "$project\.tools\sdk\android-36\android.jar") { "$project\.tools\sdk" } else { "$env:TEMP\adsb\sdk" } }
$bt = "$sdk\android-16"
$jar = "$sdk\android-36\android.jar"
if (!$TargetBuild) {
    $TargetBuild = (@("$project\.tools\build", "$env:TEMP\adsb\build-scoreline") | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object {
        Get-ChildItem -LiteralPath $_ -Directory -Filter 'run-*'
    } | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (!(Test-Path -LiteralPath "$TargetBuild\classes\app\scoreline\App.class")) { throw 'Build the app first, or supply -TargetBuild.' }
$out = Join-Path $env:TEMP ('scoreline-device-tests-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
New-Item -ItemType Directory -Force "$out\classes","$out\dex" | Out-Null
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $LASTEXITCODE" } }
& "$bt\aapt2.exe" link -I $jar --manifest "$PSScriptRoot\AndroidManifest.xml" -o "$out\unsigned.apk"
Check-Exit
$source = @(Get-ChildItem "$PSScriptRoot\src" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$jdkPath\bin\javac.exe" -Xlint:-options -encoding UTF-8 -source 17 -target 17 -classpath "$jar;$targetBuild\classes" -d "$out\classes" $source
Check-Exit
$classes = @(Get-ChildItem "$out\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
& "$jdkPath\bin\java.exe" -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --min-api 36 --lib $jar --classpath "$targetBuild\classes" --output "$out\dex" $classes
Check-Exit
# Single quotes inside: Windows PowerShell 5.1 strips embedded double quotes from native arguments.
python -I -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],''a'',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],''classes.dex''); z.close()' "$out\unsigned.apk" "$out\dex\classes.dex"
Check-Exit
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
Check-Exit
# Reuse the existing project's signing configuration without duplicating credentials here.
$build = Get-Content "$project\build.sh" -Raw
$storeArg = [regex]::Match($build, '--ks-pass (\S+)').Groups[1].Value
$keyArg = [regex]::Match($build, '--key-pass (\S+)').Groups[1].Value
& "$jdkPath\bin\java.exe" -jar "$bt\lib\apksigner.jar" sign --ks "$project\scoreline-release.keystore" --ks-pass $storeArg --key-pass $keyArg --out "$out\SportsTests.apk" "$out\aligned.apk"
Check-Exit
& "$bt\apksigner.bat" verify "$out\SportsTests.apk"
if ($LASTEXITCODE -ne 0) {
    & "$jdkPath\bin\java.exe" -jar "$bt\lib\apksigner.jar" verify "$out\SportsTests.apk"
    Check-Exit
}
Write-Output "TEST_APK=$out\SportsTests.apk"
