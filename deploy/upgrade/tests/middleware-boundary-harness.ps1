param([string]$ScenarioPath)
$ErrorActionPreference = 'Stop'
$global:fixture = Get-Content -LiteralPath $ScenarioPath -Raw | ConvertFrom-Json
foreach ($property in @('contextFailure', 'current', 'resolvedEndpoint', 'missingEndpoint', 'multipleContainers', 'inspectFailure',
    'changeEnvironment', 'projectLabel', 'serviceLabel', 'oneoffLabel', 'containerId', 'running',
    'imageMismatch', 'explicitContainer', 'oneOff', 'hostJar')) {
    if (-not $global:fixture.PSObject.Properties[$property]) { $global:fixture | Add-Member -NotePropertyName $property -NotePropertyValue $null }
}
$global:fixtureId = 'a' * 64
$global:LASTEXITCODE = 0

function global:docker {
    $commandArgs = @($args)
    @{ args = $commandArgs; env = @{ DOCKER_HOST = $env:DOCKER_HOST; DOCKER_CONTEXT = $env:DOCKER_CONTEXT } } |
        ConvertTo-Json -Depth 8 -Compress | Add-Content -LiteralPath $global:fixture.callsPath -Encoding utf8
    $global:LASTEXITCODE = 0
    $command = if ($commandArgs[0] -eq '--host') { $commandArgs[2] } else { $commandArgs[0] }
    if ($command -eq 'context') {
        if ($global:fixture.contextFailure) { $global:LASTEXITCODE = 1; return }
        $endpoint = if ($global:fixture.resolvedEndpoint) { $global:fixture.resolvedEndpoint }
            elseif ($global:fixture.current) { $global:fixture.current }
            else { 'npipe:////./pipe/docker_engine' }
        if ($global:fixture.missingEndpoint) { $endpoint = $null }
        return (ConvertTo-Json -InputObject @(@{ Name = 'fixture'; Endpoints = @{ docker = @{ Host = $endpoint } } }) -Depth 6 -Compress)
    }
    if ($command -eq 'compose' -and $commandArgs -contains 'config') {
        if ($commandArgs -contains '--images') { return 'smartoffice-upgrade-booking-service' }
        $project = if ($commandArgs -contains '--project-name') { $commandArgs[$commandArgs.IndexOf('--project-name') + 1] }
            elseif ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME } else { 'smartoffice-upgrade' }
        return (@{ name = $project; services = @{ 'booking-service' = @{} } } | ConvertTo-Json -Depth 6 -Compress)
    }
    if ($command -eq 'compose' -and $commandArgs -contains 'ps') {
        if ($global:fixture.multipleContainers) { return ($global:fixtureId + "`n" + ('b' * 64)) }
        return $global:fixtureId
    }
    if ($command -eq 'inspect') {
        if ($global:fixture.inspectFailure) { $global:LASTEXITCODE = 1; return }
        if ($global:fixture.changeEnvironment) { $env:DOCKER_HOST = 'tcp://192.0.2.1:2375'; $env:DOCKER_CONTEXT = 'remote' }
        if ($commandArgs -contains '{{.Image}}') { return 'sha256:fixture' }
        $labels = @{
            'com.docker.compose.project' = $(if ($global:fixture.projectLabel) { $global:fixture.projectLabel } else { 'smartoffice-upgrade' })
            'com.docker.compose.service' = $(if ($global:fixture.serviceLabel) { $global:fixture.serviceLabel } else { 'booking-service' })
            'com.docker.compose.oneoff' = $(if ($global:fixture.oneoffLabel) { $global:fixture.oneoffLabel } else { 'False' })
        }
        if ($commandArgs -contains '{{json .Config.Labels}}') { return ($labels | ConvertTo-Json -Compress) }
        return (@{ Id = $(if ($global:fixture.containerId) { $global:fixture.containerId } else { $global:fixtureId });
            Image = 'sha256:fixture'; Config = @{ Labels = $labels }; State = @{ Running = $global:fixture.running -ne $false } } | ConvertTo-Json -Depth 6 -Compress)
    }
    if ($command -eq 'image') { return $(if ($global:fixture.imageMismatch) { 'sha256:other' } else { 'sha256:fixture' }) }
    if ($command -eq 'cp') {
        $sourceIndex = $commandArgs.IndexOf('cp') + 1
        if ($commandArgs[$sourceIndex] -like '*:/app/smartoffice.jar') {
            [IO.File]::WriteAllText($commandArgs[$sourceIndex + 1], 'isolated fixture jar')
        }
        return
    }
    if ($command -eq 'exec' -or ($command -eq 'compose' -and $commandArgs -contains 'run')) { return }
    throw ('Unexpected fixture command: ' + ($commandArgs -join ' '))
}

# Functions named by absolute paths intercept Java tools before executable lookup.
# The files exist only to satisfy the production JDK presence check.
$jdk = Join-Path $global:fixture.testRoot 'jdk'
foreach ($tool in @('javac.exe', 'jar.exe')) {
    Set-Item -Path ('Function:\global:' + (Join-Path $jdk ('bin/' + $tool))) -Value { $global:LASTEXITCODE = 0 }
}
$invokeArguments = @{ Mode = 'Redis'; JdkHome = $jdk }
if ($global:fixture.explicitContainer) { $invokeArguments.ContainerId = $global:fixture.explicitContainer }
if ($global:fixture.oneOff) {
    $invokeArguments.Mode = 'MqSendOnly'; $invokeArguments.OneOff = $true
    $invokeArguments.EventId = '10000000-0000-4000-8000-000000000001'
}
if ($global:fixture.hostJar) { $invokeArguments.BackendJar = Join-Path $global:fixture.testRoot 'host.jar' }
& (Join-Path $global:fixture.repoRoot 'deploy/upgrade/run-middleware-probe.ps1') @invokeArguments
exit $LASTEXITCODE
