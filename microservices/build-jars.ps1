# ======================================
# 一键打包 5 个微服务的可执行 jar (Windows PowerShell)
# 产物位置：每个服务目录下 target/geo-xxx-service.jar（固定名）
# ======================================
$ErrorActionPreference = "Stop"
$BaseDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $BaseDir

$Services = @("geo-task-service","geo-analysis-service","geo-file-service","geo-rpa-service","geo-gateway")

Write-Host "==> 第一步：安装 geo-common 到本地 Maven 仓库" -ForegroundColor Cyan
mvn -pl geo-common -am -DskipTests install -q
if ($LASTEXITCODE -ne 0) { Write-Host "geo-common install 失败" -ForegroundColor Red; exit 1 }

foreach ($svc in $Services) {
    Write-Host "==> 打包 $svc ..." -ForegroundColor Cyan
    mvn -pl $svc -am -DskipTests package -q
    if ($LASTEXITCODE -ne 0) { Write-Host "$svc package 失败" -ForegroundColor Red; exit 1 }
    $jar = Join-Path $BaseDir "$svc/target/$svc.jar"
    if (Test-Path $jar) {
        $size = [math]::Round((Get-Item $jar).Length / 1MB, 2)
        Write-Host "    OK -> $svc.jar ($size MB)" -ForegroundColor Green
    } else {
        Write-Host "    期望的 jar 不存在: $jar" -ForegroundColor Red
        Write-Host "    目录内容: " (Get-ChildItem (Split-Path $jar) -Filter "*.jar" | Select-Object -ExpandProperty Name)
        exit 1
    }
}

Pop-Location
Write-Host ""
Write-Host "=== 全部 jar 打包完成 ===" -ForegroundColor Green
