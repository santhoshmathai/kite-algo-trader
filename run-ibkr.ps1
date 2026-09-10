param(
    [ValidateSet('demo','validate','schedule','probe','paper','status','stop','summary')][string]$Action='demo',
    [string]$Connection='config/ibkr/paper.local.properties',
    [string]$Profile='config/ibkr/us-paper.properties',
    [string]$SessionDate=(Get-Date -Format 'yyyy-MM-dd'),
    [string]$From,
    [string]$To,
    [string]$Output,
    [switch]$ArmPaper
)
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
    $jdkCandidates=@($env:IBKR_JAVA_HOME,$env:JAVA_HOME)
    $jdkCandidates+=@(Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | Where-Object Name -Like 'jdk-*' | Sort-Object Name -Descending | ForEach-Object FullName)
    $javaExecutable=$null
    foreach($candidate in $jdkCandidates){
        if(-not $candidate){continue}
        $binary=Join-Path $candidate 'bin/java.exe'
        if(-not (Test-Path -LiteralPath $binary)){continue}
        $description=(& $binary -version 2>&1 | Out-String)
        $match=[regex]::Match($description,'version "(\d+)')
        if($match.Success -and [int]$match.Groups[1].Value -ge 21){$javaExecutable=$binary;break}
    }
    if(-not $javaExecutable){throw 'Java 21+ not found. Set IBKR_JAVA_HOME to its installed directory.'}
    if(-not (Test-Path -LiteralPath 'build/ibkr-classpath.txt')){throw 'Run .\build-ibkr.ps1 test first.'}
    $ibkrClasspath=Get-Content -LiteralPath 'build/ibkr-classpath.txt' -Raw
    $javaArguments=@('-cp',$ibkrClasspath,'com.example.trading.ibkr.IbkrMain',$Action)
    switch($Action){
        'demo' {if(-not $Output){$Output='runs/ibkr-demo-'+(Get-Date -Format 'yyyyMMdd-HHmmss')};$javaArguments+=@($Profile,$Output)}
        'schedule' {$javaArguments+=@($Connection,$SessionDate)}
        'paper' {if(-not $ArmPaper){throw 'Paper orders require -ArmPaper and the exact DU account in the local connection file.'};$javaArguments+=@($Connection,$Profile,'--arm-paper')}
        'status' {$javaArguments+=@($Connection,$Profile)}
        'stop' {$javaArguments+=@($Connection,$Profile)}
        'summary' {if(-not $From -or -not $To){throw 'Supply -From yyyy-MM-dd -To yyyy-MM-dd'};$javaArguments+=@($Connection,$Profile,$From,$To)}
        default {$javaArguments+=$Connection}
    }
    Write-Host "Using $javaExecutable"
    & $javaExecutable @javaArguments
    if($LASTEXITCODE -ne 0){throw "IBKR command exited with code $LASTEXITCODE"}
} finally {Pop-Location}
