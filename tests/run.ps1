param([string]$JsonJar = "$PSScriptRoot\..\.tools\json.jar", [string]$TargetBuild = '')
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..").Path
$jdkPath = (Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-21*' | Select-Object -First 1).FullName
if (!$TargetBuild) {
    $roots = @("$project\.tools\build", "$env:TEMP\adsb\build-scoreline")
    $TargetBuild = ($roots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object {
        Get-ChildItem -LiteralPath $_ -Directory -Filter 'run-*'
    } | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (!(Test-Path -LiteralPath $JsonJar)) { throw 'Supply -JsonJar with the host org.json runtime jar.' }
if (!(Test-Path -LiteralPath "$TargetBuild\gen\app\scoreline\R.java")) { throw 'Build the app resources first, or supply -TargetBuild.' }
$out = Join-Path $project ('.tools\host-tests-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
New-Item -ItemType Directory -Force $out | Out-Null
$source = @("$project\src\app\scoreline\core\Http.java", "$project\src\app\scoreline\core\Json.java",
    "$project\src\app\scoreline\data\Espn.java", "$project\src\app\scoreline\data\Browse.java", "$project\src\app\scoreline\data\Catalog.java",
    "$TargetBuild\gen\app\scoreline\R.java")
$source += @(Get-ChildItem "$project\src\app\scoreline\model" -Filter '*.java' | ForEach-Object FullName)
$source += @('WidgetSnapshot.java', 'WidgetSelection.java', 'WidgetSnapshotCodec.java') | ForEach-Object { "$project\src\app\scoreline\widgets\$_" }
$source += @(Get-ChildItem $PSScriptRoot -Filter '*Test.java' | ForEach-Object FullName)
& "$jdkPath\bin\javac.exe" -Xlint:all,-options,-serial -encoding UTF-8 -source 17 -target 17 -classpath $JsonJar -d $out $source
if ($LASTEXITCODE -ne 0) { throw 'Host test compilation failed.' }
foreach ($test in @('TimelineTest', 'StatusTest', 'HttpTest', 'app.scoreline.data.BrowseTest', 'SportsEventTest', 'app.scoreline.widgets.WidgetTest')) {
    & "$jdkPath\bin\java.exe" -cp "$out;$JsonJar" $test
    if ($LASTEXITCODE -ne 0) { throw "$test failed." }
}
