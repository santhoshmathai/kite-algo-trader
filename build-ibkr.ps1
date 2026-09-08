param([ValidateSet('build','test')][string]$Action='test')
$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
Push-Location $projectRoot
try {
    $jdkRoot=$env:IBKR_JAVA_HOME
    if (-not $jdkRoot) { $jdkRoot=$env:JAVA_HOME }
    if (-not $jdkRoot) {
        $settings=(& java -XshowSettings:properties -version 2>&1 | Out-String)
        $jdkRoot=[regex]::Match($settings,'(?m)^\s*java\.home\s*=\s*(.+)$').Groups[1].Value.Trim()
    }
    $apiRoot=$env:IBKR_API_HOME
    if (-not $apiRoot) { $apiRoot=Join-Path $projectRoot '.deps/ibkr/api-1045/IBJts' }
    $sdk=Join-Path $apiRoot 'source/JavaClient/TwsApi.jar'
    $protobuf=Join-Path $apiRoot 'source/JavaClient/jars/protobuf-java-4.29.5.jar'
    foreach($path in @($sdk,$protobuf)) { if (-not (Test-Path -LiteralPath $path)) { throw "Missing official IBKR API 10.45 dependency: $path. See docs/IBKR-INTEGRATION.md" } }
    # Rebuild the shared classes from this checkout without changing the normal India build's dependencies.
    & ./build.ps1 build
    $application=Join-Path $projectRoot 'build/kite-algo-trader.jar'
    $output=Join-Path $projectRoot ('build/ibkr-'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force $output | Out-Null
    $sources=@(Get-ChildItem integrations/ibkr/src -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
    if ($Action -eq 'test') { $sources+=@(Get-ChildItem integrations/ibkr/test -Recurse -Filter '*.java' | ForEach-Object {$_.FullName}) }
    $cp="$application;$sdk;$protobuf"
    & (Join-Path $jdkRoot 'bin/javac.exe') --release 17 -encoding UTF-8 -cp $cp -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw 'IBKR compilation failed' }
    $artifact=Join-Path $projectRoot 'build/ibkr-foundation.jar'
    & (Join-Path $jdkRoot 'bin/jar.exe') --create --file $artifact -C $output .
    if ($LASTEXITCODE -ne 0) { throw 'IBKR packaging failed' }
    $runtimeCp="$artifact;$cp"
    [IO.File]::WriteAllText((Join-Path $projectRoot 'build/ibkr-classpath.txt'),$runtimeCp)
    if ($Action -eq 'test') {
        & (Join-Path $jdkRoot 'bin/java.exe') -cp $runtimeCp com.example.trading.ibkr.IbkrChecks
        if ($LASTEXITCODE -ne 0) { throw 'IBKR checks failed' }
    }
    Write-Host "Built $artifact. Probe requires Java 21+; SDK files stay outside the distributable JAR."
} finally { Pop-Location }
