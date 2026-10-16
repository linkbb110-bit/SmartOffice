param(
    [Parameter(Mandatory=$true)][string]$H2Jar,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$WithFrontend,
    [string]$BrowserScript
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $JavaHome) { throw '先设置 JAVA_HOME 为 Java17 路径。' }
$taskJava = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $taskJava) -or -not (Test-Path -LiteralPath $H2Jar)) {
    throw 'Java 或 H2 JAR 不存在；H2 必须是 Maven test scope 已下载的驱动。'
}
$taskLogs = Join-Path $taskRoot 'logs/local-smoke'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskProcesses = [Collections.Generic.List[Diagnostics.Process]]::new()
$taskOriginalGateway = $env:VITE_GATEWAY_URL
$taskChecks = [Collections.Generic.List[string]]::new()
$taskReport = @{passed=$false;status='running';startedAt=[DateTime]::UtcNow.ToString('o');checks=@();database='H2 memory';redis='unavailable fallback';mq='disabled';nacos='disabled';browser='not executed'}
$taskReport | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $taskLogs 'result.json')
$taskFrontendUrl = $null
$taskCorsOrigin = 'http://localhost:5173'
if ($WithFrontend) {
    $taskPortReservation = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $taskPortReservation.Start()
    $taskFrontendPort = $taskPortReservation.LocalEndpoint.Port
    $taskPortReservation.Stop()
    $taskFrontendUrl = "http://127.0.0.1:$taskFrontendPort"
    $taskCorsOrigin = $taskFrontendUrl
}
function Start-LocalJava([string]$name, [string]$relativeJar, [string[]]$properties) {
    $taskJar = Join-Path $taskRoot $relativeJar
    if (-not (Test-Path -LiteralPath $taskJar)) { throw "请先 mvn clean verify：$relativeJar 不存在" }
    $taskArgs = @('-cp', "$taskJar;$H2Jar", 'org.springframework.boot.loader.launch.JarLauncher',
        '--server.address=127.0.0.1', '--server.port=0', '--spring.cloud.nacos.discovery.enabled=false',
        '--spring.cloud.discovery.enabled=false', "--smartoffice.cors.allowed-origins=$taskCorsOrigin",
        '--smartoffice.jwt.secret=test-only-smoke-secret-key-with-at-least-32-bytes') + $properties
    $taskQuoted = $taskArgs | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }
    $taskProcess = Start-Process -FilePath $taskJava -ArgumentList $taskQuoted -WorkingDirectory $taskRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogs "$name.out.log") `
        -RedirectStandardError (Join-Path $taskLogs "$name.err.log")
    $taskProcesses.Add($taskProcess)
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $taskDeadline) {
        $taskProcess.Refresh()
        if ($taskProcess.HasExited) { throw "$name 启动退出，请检查 logs/local-smoke/$name.err.log 与 out.log" }
        $taskText = Get-Content -LiteralPath (Join-Path $taskLogs "$name.out.log") -Raw -ErrorAction SilentlyContinue
        if ($taskText -match '(?:Tomcat|Netty) started on port (\d+)') {
            $taskUrl = "http://127.0.0.1:$($Matches[1])"
            try {
                $taskHealth = Invoke-RestMethod "$taskUrl/actuator/health" -TimeoutSec 2
                if ($taskHealth.status -eq 'UP') { Write-Host "$name 健康检查通过"; return $taskUrl }
            } catch { }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "$name 在60秒内未健康，请检查本地日志。"
}
function Assert-LocalApi([bool]$passed, [string]$description) {
    if (-not $passed) { throw "检查失败：$description" }
    $taskChecks.Add($description)
}
try {
    $taskUser = Start-LocalJava 'user' 'user-service/target/smartoffice-user-service-1.0.0.jar' @(
        '--spring.profiles.active=test', '--spring.datasource.url=jdbc:h2:mem:smoke_user;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE',
        '--spring.sql.init.mode=always', "--spring.sql.init.schema-locations=file:$taskRoot/user-service/src/test/resources/user-test.sql",
        "--spring.sql.init.data-locations=file:$taskRoot/user-service/src/main/resources/data.sql")
    $taskBooking = Start-LocalJava 'booking' 'backend/target/smartoffice-booking-service-1.0.0.jar' @(
        '--spring.profiles.active=test', '--spring.datasource.url=jdbc:h2:mem:smoke_booking;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE',
        '--spring.sql.init.mode=always', "--spring.sql.init.schema-locations=file:$taskRoot/backend/src/test/resources/db-test.sql,file:$taskRoot/backend/src/test/resources/message-test.sql",
        "--spring.sql.init.data-locations=file:$taskRoot/deploy/upgrade/smoke-booking-data.sql",
        "--smartoffice.user-service.url=$taskUser", '--smartoffice.mq.enabled=false',
        '--spring.data.redis.host=127.0.0.1', '--spring.data.redis.port=1', '--management.health.redis.enabled=false')
    $taskGateway = Start-LocalJava 'gateway' 'gateway/target/smartoffice-gateway-1.0.0.jar' @(
        '--spring.profiles.active=local', "--smartoffice.routes.user-uri=$taskUser", "--smartoffice.routes.booking-uri=$taskBooking")
    $taskApi = "$taskGateway/api"
    if ($WithFrontend) {
        $env:VITE_GATEWAY_URL = $taskGateway
        $taskNode = (Get-Command node -ErrorAction Stop).Source
        $taskVite = Join-Path $taskRoot 'node_modules/vite/bin/vite.js'
        $taskViteArgs = @($taskVite,'--host','127.0.0.1','--port',"$taskFrontendPort",'--strictPort','--config','vite.config.mjs','--configLoader','native') |
            ForEach-Object { '"' + $_.Replace('"','\"') + '"' }
        $taskFrontend = Start-Process -FilePath $taskNode -ArgumentList $taskViteArgs -WorkingDirectory $taskRoot `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskLogs 'frontend.out.log') `
            -RedirectStandardError (Join-Path $taskLogs 'frontend.err.log')
        $taskProcesses.Add($taskFrontend)
        $taskFrontendReady = $false
        $taskFrontendDeadline = [DateTime]::UtcNow.AddSeconds(30)
        while ([DateTime]::UtcNow -lt $taskFrontendDeadline) {
            $taskFrontend.Refresh()
            if ($taskFrontend.HasExited) { throw 'Vite退出，请检查frontend日志。' }
            $taskFrontendText = Get-Content (Join-Path $taskLogs 'frontend.out.log') -Raw -ErrorAction SilentlyContinue
            if ($taskFrontendText -match 'http://127\.0\.0\.1:(\d+)') {
                $taskFrontendUrl = "http://127.0.0.1:$($Matches[1])"
                $taskHtml = Invoke-WebRequest $taskFrontendUrl -TimeoutSec 5
                Assert-LocalApi ($taskHtml.StatusCode -eq 200) 'Vite页面HTTP可达'
                $taskApi = "$taskFrontendUrl/api"
                $taskFrontendReady = $true
                break
            }
            Start-Sleep -Milliseconds 250
        }
        if (-not $taskFrontendReady) { throw '30秒内没有发现Vite分配端口。' }
    }
    $taskLogin = Invoke-RestMethod "$taskApi/users/login" -Method Post -ContentType 'application/json' `
        -Body '{"username":"user","password":"123456"}'
    Assert-LocalApi ($taskLogin.code -eq 200 -and [bool]$taskLogin.data) '通过Gateway登录'
    $taskHeaders = @{Authorization="Bearer $($taskLogin.data)"}
    $taskMe = Invoke-RestMethod "$taskApi/users/me" -Headers $taskHeaders
    Assert-LocalApi ($taskMe.data.username -eq 'user') '有效数据库身份'
    $taskRooms = Invoke-RestMethod "$taskApi/rooms/list" -Headers $taskHeaders
    Assert-LocalApi ($taskRooms.data.Count -eq 1) '会议室查询'
    $taskPayload = @{roomId=1;topic='本地HTTP预约';startTime='2099-01-01T10:00:00';endTime='2099-01-01T11:00:00';requestId='smoke-http-retry'} | ConvertTo-Json
    $taskFirst = Invoke-RestMethod "$taskApi/orders/book" -Method Post -Headers $taskHeaders -ContentType 'application/json' -Body $taskPayload
    $taskReplay = Invoke-RestMethod "$taskApi/orders/book" -Method Post -Headers $taskHeaders -ContentType 'application/json' -Body $taskPayload
    Assert-LocalApi ($taskFirst.code -eq 200 -and $taskFirst.data -eq $taskReplay.data) '真实Feign与预约幂等重放'
    $taskOrders = Invoke-RestMethod "$taskApi/orders/my" -Headers $taskHeaders
    Assert-LocalApi ($taskOrders.data.Count -eq 1) '仅一条预约'
    Invoke-RestMethod "$taskApi/orders/$($taskFirst.data)/cancel" -Method Post -Headers $taskHeaders | Out-Null
    Invoke-RestMethod "$taskApi/orders/$($taskFirst.data)/cancel" -Method Post -Headers $taskHeaders | Out-Null
    $taskCancelled = Invoke-RestMethod "$taskApi/orders/my" -Headers $taskHeaders
    Assert-LocalApi ($taskCancelled.data[0].status -eq 2) '重复取消成功'
    $taskAdmin = Invoke-RestMethod "$taskApi/users/login" -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"123456"}'
    $taskOutbox = Invoke-RestMethod "$taskApi/admin/messages/outbox" -Headers @{Authorization="Bearer $($taskAdmin.data)"}
    Assert-LocalApi ($taskOutbox.data.Count -eq 1 -and $taskOutbox.data[0].status -eq 'PENDING') 'MQ关闭保留唯一待发Outbox'
    if ($BrowserScript) {
        if (-not $WithFrontend) { throw 'BrowserScript必须同时指定WithFrontend。' }
        & (Get-Command node -ErrorAction Stop).Source $BrowserScript $taskFrontendUrl
        if ($LASTEXITCODE -ne 0) { throw '浏览器验收失败，请检查独立浏览器报告。' }
        Assert-LocalApi $true '浏览器完整交互验收'
    }
    $taskReport.passed = $true
    $taskReport.status = 'passed'
    if ($BrowserScript) { $taskReport.browser = 'passed' }
    Write-Output "本地冒烟通过：$($taskChecks.Count)项。H2/Redis故障降级/MQ与Nacos关闭；不代表真实中间件测试。"
} finally {
    foreach ($taskProcess in $taskProcesses) {
        $taskProcess.Refresh()
        if (-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id -ErrorAction SilentlyContinue }
    }
    $env:VITE_GATEWAY_URL = $taskOriginalGateway
    if (-not $taskReport.passed) { $taskReport.status = 'failed' }
    $taskReport.checks = $taskChecks.ToArray()
    $taskReport.completedAt = [DateTime]::UtcNow.ToString('o')
    $taskReport | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $taskLogs 'result.json')
}
