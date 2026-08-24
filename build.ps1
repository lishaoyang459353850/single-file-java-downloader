# PyDownload build script - compile single-file Java source into executable JAR
param([switch]$Run, [switch]$Clean)
$ErrorActionPreference = 'Stop'
$out = 'build'; $jar = 'PyDownload.jar'; $src = 'PyDownload.java'
if ($Clean) { if (Test-Path $out) { Remove-Item $out -Recurse -Force }; if (Test-Path $jar) { Remove-Item $jar -Force }; Write-Host 'Cleaned.'; exit }
$jc = Get-Command javac -ErrorAction SilentlyContinue
if (-not $jc) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $jc = (Join-Path $env:JAVA_HOME 'bin\javac.exe') }
    else { Write-Host 'ERROR: 未找到 javac。请安装 JDK 8+ 或将 JAVA_HOME/bin 加入 PATH。' -ForegroundColor Red; exit 1 }
}
if (-not (Test-Path $src)) { Write-Host "ERROR: 找不到 $src" -ForegroundColor Red; exit 1 }
New-Item $out -ItemType Directory -Force | Out-Null
Write-Host '==> 编译 PyDownload.java (UTF-8) ...'
& $jc -encoding UTF-8 -d $out $src
if ($LASTEXITCODE -ne 0) { Write-Host '编译失败。' -ForegroundColor Red; exit 1 }
Write-Host '==> 生成 MANIFEST.MF (Main-Class: PyDownload) ...'
$mf = Join-Path $out 'MANIFEST.MF'
Set-Content -Path $mf -Value "Manifest-Version: 1.0`nMain-Class: PyDownload`n`n" -NoNewline -Encoding ASCII
Write-Host "==> 打包可执行 JAR: $jar ..."
& jar cfm $jar (Join-Path $out 'MANIFEST.MF') -C $out .
if ($LASTEXITCODE -ne 0) { Write-Host 'JAR 打包失败(需 JDK 自带 jar)。' -ForegroundColor Red; exit 1 }
Write-Host "SUCCESS: $jar 已生成。" -ForegroundColor Green
if ($Run) { Write-Host '==> 运行 ...'; & java -jar $jar }
