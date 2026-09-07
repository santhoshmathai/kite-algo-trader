param([ValidateSet('test', 'demo', 'build')][string]$Action = 'test')
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Push-Location $projectRoot
try {
if ((Get-FileHash (Join-Path $projectRoot 'lib/kiteconnect.jar') -Algorithm SHA256).Hash -ne 'EDB5AAAC2E675964EF22D8990224C4B9EFE45BAAE3F85CB17FDAB733C64FDB22') { throw 'Bundled SDK checksum mismatch' }
$jdkRoot = $env:JAVA_HOME
if (-not $jdkRoot) {
    $javaSettings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    $homeMatch = [regex]::Match($javaSettings, '(?m)^\s*java\.home\s*=\s*(.+)$')
    if (-not $homeMatch.Success) { throw 'Install JDK 11+ or set JAVA_HOME to its directory' }
    $jdkRoot = $homeMatch.Groups[1].Value.Trim()
}
$compiler = Join-Path $jdkRoot 'bin/javac.exe'
$packager = Join-Path $jdkRoot 'bin/jar.exe'
$runtime = Join-Path $jdkRoot 'bin/java.exe'
foreach ($tool in @($compiler, $packager, $runtime)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing JDK tool: $tool" }
}
$buildRoot = Join-Path $projectRoot 'build'
$runRoot = Join-Path $buildRoot ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'tests'
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/com') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& $compiler --release 11 -encoding UTF-8 -cp (Join-Path $projectRoot 'lib/kiteconnect.jar') -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Production compilation failed' }
Copy-Item -Path (Join-Path $projectRoot 'src/main/resources/*') -Destination $classes
$artifact = Join-Path $buildRoot 'kite-algo-trader.jar'
@("Manifest-Version: 1.0", "Main-Class: com.example.trading.TradingSystemMain", "Class-Path: lib/kiteconnect.jar", "") | Set-Content (Join-Path $runRoot 'manifest.mf') -Encoding ascii
New-Item -ItemType Directory -Force (Join-Path $buildRoot 'lib') | Out-Null
Copy-Item (Join-Path $projectRoot 'lib/kiteconnect.jar') (Join-Path $buildRoot 'lib/kiteconnect.jar')
& $packager --create --file $artifact --manifest (Join-Path $runRoot 'manifest.mf') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
if ($Action -eq 'test') {
    $tests = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'test') -Recurse -Filter '*.java' | Where-Object { $_.Name -notin @('FoundationTest.java', 'ApplicationTest.java') } | ForEach-Object { $_.FullName })
    & $compiler --release 11 -encoding UTF-8 -cp (Join-Path $projectRoot 'lib/kiteconnect.jar') -d $testClasses @sources @tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    $testArtifact = Join-Path $runRoot 'foundation-tests.jar'
    & $packager --create --file $testArtifact -C $testClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Test packaging failed' }
    & $runtime -cp "$artifact$([IO.Path]::PathSeparator)$testArtifact" com.example.trading.FoundationChecks
    if ($LASTEXITCODE -ne 0) { throw 'Foundation checks failed' }
    & $runtime -cp "$artifact$([IO.Path]::PathSeparator)$testArtifact" com.example.trading.ApplicationChecks
    if ($LASTEXITCODE -ne 0) { throw 'Application checks failed' }
}
if ($Action -eq 'demo') {
    & $runtime -jar $artifact --demo
    if ($LASTEXITCODE -ne 0) { throw 'Demo failed' }
}
Write-Host "Built $artifact"



} finally { Pop-Location }
