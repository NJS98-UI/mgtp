# ClusterCast build script (pure ASCII, relative paths to avoid CJK issues in native tools)
$ErrorActionPreference = "Stop"
$JDK = "C:\jdk-17.0.2"
$BT  = "C:\Users\Administrator\AndroidSDK\build-tools\34.0.0"
$AJ  = "C:\Users\Administrator\AndroidSDK\platforms\android-34\android.jar"
$P   = $PSScriptRoot

New-Item -ItemType Directory -Force -Path (Join-Path $P "gen") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $P "classes") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $P "out\dex") | Out-Null
Push-Location $P
try {
    Write-Host "[1/6] aapt2 compile"
    & (Join-Path $BT "aapt2.exe") compile --dir res -o out\res.zip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

    Write-Host "[2/6] aapt2 link"
    # targetSdk 必须停在 28：Android 10 起系统禁止 targetSdk>=29 的应用往
    # 「无系统装饰」的虚拟屏启动 Activity，28 是第三方 app 能用虚拟屏投应用的最后门槛
    $linkArgs = @("link", "-o", "out\app.base.apk", "-I", $AJ,
        "--manifest", "AndroidManifest.xml", "--java", "gen",
        "--min-sdk-version", "27", "--target-sdk-version", "28",
        "--version-code", "14", "--version-name", "14.0")
    if (Test-Path assets) { $linkArgs += @("-A", "assets") }
    $linkArgs += "out\res.zip"
    & (Join-Path $BT "aapt2.exe") @linkArgs
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

    Write-Host "[3/6] javac"
    # 先清空 classes/：上次构建留下的 orphan .class（如已删除的源文件）会被 d8 一起打进 dex
    if (Test-Path classes) { Remove-Item -Recurse -Force classes }
    New-Item -ItemType Directory -Force -Path classes | Out-Null
    $srcs = @(Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName.Substring($P.Length + 1) })
    $srcs += @(Get-ChildItem -Recurse -Filter *.java gen | ForEach-Object { $_.FullName.Substring($P.Length + 1) })
    & (Join-Path $JDK "bin\javac.exe") -encoding UTF-8 -source 11 -target 11 -classpath $AJ -d classes $srcs
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }

    Write-Host "[4/6] d8"
    $cls = @(Get-ChildItem -Recurse -Filter *.class classes | ForEach-Object { $_.FullName.Substring($P.Length + 1) })
    & (Join-Path $BT "d8.bat") --min-api 27 --lib $AJ --output out\dex $cls
    if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

    Write-Host "[5/6] package dex + zipalign"
    Push-Location out\dex
    & (Join-Path $BT "aapt.exe") add ..\app.base.apk "classes.dex"
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "aapt add failed" }
    Pop-Location
    & (Join-Path $BT "zipalign.exe") -f 4 out\app.base.apk out\app.aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

    Write-Host "[6/6] apksigner"
    $ks = Join-Path $P "out\debug.keystore"
    if (-not (Test-Path $ks)) {
        & (Join-Path $JDK "bin\keytool.exe") -genkeypair -keystore out\debug.keystore -storepass android -keypass android `
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=Android Debug,O=Android,C=US"
        if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    }
    & (Join-Path $BT "apksigner.bat") sign --ks out\debug.keystore --ks-pass pass:android --key-pass pass:android --out out\ClusterCast.apk out\app.aligned.apk
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

    Write-Host "DONE: out\ClusterCast.apk"
}
finally {
    Pop-Location
}
