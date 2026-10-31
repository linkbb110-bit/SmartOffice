[CmdletBinding()]
param(
    [string]$EnvFile = 'deploy/.env.host-dev',
    [ValidatePattern('^\.env\.[A-Za-z0-9-]+$')]
    [string]$OutputPrefix = '.env.host-dev'
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskPrivateEnv = Join-Path $taskProjectRoot $EnvFile
if (-not (Test-Path -LiteralPath $taskPrivateEnv)) {
    throw 'Missing private environment file. Follow runbook first; existing credentials must not be regenerated.'
}
$taskValues = @{}
foreach ($taskLine in Get-Content -LiteralPath $taskPrivateEnv) {
    if ($taskLine -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
        $taskValues[$Matches[1]] = $Matches[2]
    }
}
foreach ($taskKey in @('BOOKING_DB_USERNAME','BOOKING_DB_PASSWORD','USER_DB_USERNAME','USER_DB_PASSWORD','REDIS_PASSWORD','JWT_SECRET')) {
    if ([string]::IsNullOrWhiteSpace($taskValues[$taskKey]) -or $taskValues[$taskKey].StartsWith('change-me')) {
        throw "Missing or placeholder value for $taskKey; private configuration was not changed."
    }
}
function ConvertTo-PropertyValue([string]$Value) {
    $taskEscaped = $Value.Replace('\','\\').Replace("`r",'\r').Replace("`n",'\n')
    if ($taskEscaped.StartsWith(' ')) { $taskEscaped = '\' + $taskEscaped }
    return $taskEscaped
}

foreach ($taskService in @('user','booking','gateway')) {
    $taskDestination = Join-Path $taskProjectRoot "backend/$OutputPrefix-$taskService.properties"
    if (Test-Path -LiteralPath $taskDestination) {
        Write-Output "Preserved existing backend/$OutputPrefix-$taskService.properties (not overwritten)."
        continue
    }
    $taskProperties = [ordered]@{
        'spring.profiles.active' = 'cloud'
        'spring.cloud.discovery.enabled' = 'true'
        'spring.cloud.nacos.discovery.enabled' = 'true'
        'spring.cloud.nacos.discovery.server-addr' = '127.0.0.1:18848'
        'spring.cloud.nacos.discovery.ip' = '127.0.0.1'
        'server.address' = '127.0.0.1'
        'logging.file.name' = "./logs/host-dev-$taskService.log"
    }
    if ($taskService -ne 'gateway') {
        $taskProperties['smartoffice.jwt.secret'] = $taskValues['JWT_SECRET']
        $taskDbPort = if ($taskService -eq 'user') { '13307' } else { '13306' }
        $taskDbName = "smartoffice_$taskService"
        $taskProperties['spring.datasource.url'] = "jdbc:mysql://127.0.0.1:$taskDbPort/${taskDbName}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
        $taskPrefix = $taskService.ToUpperInvariant()
        $taskProperties['spring.datasource.username'] = $taskValues["${taskPrefix}_DB_USERNAME"]
        $taskProperties['spring.datasource.password'] = $taskValues["${taskPrefix}_DB_PASSWORD"]
        $taskProperties['spring.sql.init.mode'] = 'never'
    }
    if ($taskService -eq 'booking') {
        $taskProperties['spring.data.redis.host'] = '127.0.0.1'
        $taskProperties['spring.data.redis.port'] = '16379'
        $taskProperties['spring.data.redis.password'] = $taskValues['REDIS_PASSWORD']
        $taskProperties['smartoffice.user-service.url'] = ''
        $taskProperties['smartoffice.mq.enabled'] = 'true'
        $taskProperties['smartoffice.mq.namesrv-addr'] = '127.0.0.1:19876'
        # Opt-in only in this ignored loopback development configuration.
        $taskProperties['smartoffice.docs.public'] = 'true'
    }
    $taskText = "# PRIVATE LOCAL DEVELOPMENT CONFIGURATION. Do not commit.`n" + (
        ($taskProperties.GetEnumerator() | ForEach-Object { $_.Key + '=' + (ConvertTo-PropertyValue ([string]$_.Value)) }) -join "`n"
    ) + "`n"
    # CreateNew protects a file created by another process after the existence check.
    $taskStream = [IO.File]::Open($taskDestination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $taskBytes = [Text.UTF8Encoding]::new($false).GetBytes($taskText)
        $taskStream.Write($taskBytes, 0, $taskBytes.Length)
    } finally { $taskStream.Dispose() }
    Write-Output "Created ignored backend/$OutputPrefix-$taskService.properties; no credential values printed."
}
