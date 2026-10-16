[CmdletBinding()]
param(
    [ValidateSet('CompileOnly', 'Redis', 'MqDuplicate', 'MqEarliest', 'MqSendOnly')]
    [string]$Mode = 'CompileOnly',
    [string]$EventId,
    [long]$RoomId = 0,
    [string]$ContainerId,
    [string]$BackendJar,
    [switch]$OneOff,
    [string]$JdkHome = 'C:\Program Files\Java\jdk-17',
    [string]$ComposeFile = 'docker-compose.upgrade.yml',
    [string]$EnvFile = '.env.upgrade'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runName = 'middleware-probe-' + [Guid]::NewGuid().ToString('N')
$runRoot = Join-Path $repoRoot ('logs/' + $runName)
$extractRoot = Join-Path $runRoot 'extracted'
$classRoot = Join-Path $runRoot 'classes'
$step = 'arguments'
$dockerEndpoint = $null

function Invoke-Native {
    param([string]$Executable, [string[]]$Arguments)
    $savedEnvironment = @{}
    if ($Executable -eq 'docker') {
        if (-not $script:dockerEndpoint) { throw 'Docker target has not been verified.' }
        $Arguments = @('--host', $script:dockerEndpoint) + $Arguments
        foreach ($key in @('DOCKER_CONTEXT', 'DOCKER_HOST', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')) {
            $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $null, 'Process')
        }
    }
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Native command failed: $([IO.Path]::GetFileName($Executable))" }
    } finally {
        foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process') }
    }
}

function Invoke-CheckedNative {
    param([string]$Executable, [string[]]$Arguments)
    Invoke-Native $Executable $Arguments
}

function Invoke-CapturedNative {
    param([string]$Executable, [string[]]$Arguments)
    $nativeOutput = @(Invoke-Native $Executable $Arguments)
    return ($nativeOutput -join "`n").Trim()
}

function Initialize-LocalDocker {
    # Resolve the actual CLI selection (DOCKER_CONTEXT, DOCKER_HOST, currentContext,
    # including "default") using local metadata only, before any daemon operation.
    $contextOutput = @(& docker context inspect)
    if ($LASTEXITCODE -ne 0) { throw 'Docker context inspection failed.' }
    $contexts = @(($contextOutput -join "`n") | ConvertFrom-Json)
    if ($contexts.Count -ne 1) { throw 'Expected one Docker context.' }
    $endpoint = [string]$contexts[0].Endpoints.docker.Host
    if ($endpoint -cnotmatch '^(?:npipe:////\./pipe/[a-zA-Z0-9_.-]+|unix:///[^/\\\s?#][^\\\s?#]*)$') {
        throw 'Only a local Docker named pipe or Unix socket is allowed.'
    }
    $script:dockerEndpoint = $endpoint
}

function Assert-BookingContainer {
    param([string]$Identifier)
    if ($Identifier -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]*$') { throw 'Invalid container identifier.' }
    $containerJson = Invoke-CapturedNative 'docker' @('inspect', '--format', '{{json .}}', $Identifier)
    $container = $containerJson | ConvertFrom-Json
    $labels = $container.Config.Labels
    if ($container.Id -cnotmatch '^[a-f0-9]{64}$' -or
        $labels.'com.docker.compose.project' -cne 'smartoffice-upgrade' -or
        $labels.'com.docker.compose.service' -cne 'booking-service' -or
        $labels.'com.docker.compose.oneoff' -cne 'False' -or
        (-not $OneOff -and $container.State.Running -ne $true)) { throw 'Container must be the intended smartoffice-upgrade/booking-service.' }
    return $container
}

try {
    if ($OneOff -and $Mode -ne 'MqSendOnly') { throw 'OneOff is supported only for MqSendOnly.' }
    if ($Mode -like 'Mq*') {
        $parsedEventId = [Guid]::Empty
        if (-not [Guid]::TryParse($EventId, [ref]$parsedEventId)) { throw 'A real current-run Outbox EventId is required.' }
        $EventId = $parsedEventId.ToString()
    }
    if ($RoomId -ne 0 -and $RoomId -lt 8000000000000000) { throw 'RoomId must be a dedicated ID >= 8000000000000000.' }
    $javac = Join-Path $JdkHome 'bin/javac.exe'
    $jarTool = Join-Path $JdkHome 'bin/jar.exe'
    if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $jarTool)) { throw 'Existing Java 17 JDK not found.' }
    New-Item -ItemType Directory -Path $extractRoot, $classRoot -Force | Out-Null
    $step = 'artifact-selection'
    $sourceRecord = [ordered]@{ mode = $Mode; oneOff = [bool]$OneOff; source = $null; path = $null;
        containerId = $null; composeProject = $null; jarSha256 = $null; runtimeImageId = $null; runtimeImageName = $null }
    $composeArguments = @()
    $oneOffImageId = $null
    if ($Mode -eq 'CompileOnly') {
        if (-not $BackendJar) { $BackendJar = Join-Path $repoRoot 'backend/target/smartoffice-booking-service-1.0.0.jar' }
        $BackendJar = (Resolve-Path -LiteralPath $BackendJar).Path
        $sourceRecord.source = 'host-jar-compile-only'
        $sourceRecord.path = $BackendJar
    } else {
        $step = 'local-docker-target'
        Initialize-LocalDocker
        $sourceRecord.dockerEndpoint = $dockerEndpoint
        $composePath = [IO.Path]::GetFullPath((Join-Path $repoRoot $ComposeFile))
        $envPath = [IO.Path]::GetFullPath((Join-Path $repoRoot $EnvFile))
        $composeArguments = @('compose', '--project-name', 'smartoffice-upgrade', '--env-file', $envPath, '-f', $composePath)
        # This installed Compose exposes project name in JSON. Keep variables unresolved and never print the config.
        $identityJson = Invoke-CapturedNative 'docker' ($composeArguments + @('config', '--format', 'json',
            '--no-interpolate', '--no-env-resolution'))
        $composeIdentity = $identityJson | ConvertFrom-Json
        $composeProject = [string]$composeIdentity.name
        if ($composeProject -cne 'smartoffice-upgrade' -or -not $composeIdentity.services.PSObject.Properties['booking-service']) {
            throw 'Expected the smartoffice-upgrade/booking-service Compose identity.'
        }
        $sourceRecord.composeProject = $composeProject
        if ($ContainerId) {
            $bookingContainer = Assert-BookingContainer $ContainerId
            $ContainerId = $bookingContainer.Id
            $sourceRecord.containerId = $ContainerId
            $sourceRecord.runtimeImageId = $bookingContainer.Image
        }
        $copyDeployedJar = $false
        if ($OneOff -and $BackendJar) {
            # An explicit host artifact is allowed for a stopped/not-yet-created service; provenance is recorded.
            $BackendJar = (Resolve-Path -LiteralPath $BackendJar).Path
            $sourceRecord.source = 'explicit-host-jar'
            $sourceRecord.path = $BackendJar
        } else {
            # docker cp also reads a stopped existing container without starting it.
            if (-not $ContainerId) {
                $psArguments = @('ps')
                if ($OneOff) { $psArguments += '--all' }
                $psArguments += @('--quiet', 'booking-service')
                $containerText = Invoke-CapturedNative 'docker' ($composeArguments + $psArguments)
                $containerResults = @($containerText -split '\s+' | Where-Object { $_ })
                if ($containerResults.Count -ne 1) { throw 'One existing booking-service container is required.' }
                $ContainerId = $containerResults[0]
                $bookingContainer = Assert-BookingContainer $ContainerId
                $ContainerId = $bookingContainer.Id
            }
            $BackendJar = Join-Path $runRoot 'smartoffice.jar'
            $copyDeployedJar = $true
            $sourceRecord.source = 'deployed-container-jar'
            $sourceRecord.path = '/app/smartoffice.jar'
            $sourceRecord.containerId = $ContainerId
            $sourceRecord.runtimeImageId = $bookingContainer.Image
        }
        if ($OneOff) {
            # config --images includes dependencies in this Compose version. Select the booking image explicitly.
            $bookingServiceIdentity = $composeIdentity.services.PSObject.Properties['booking-service'].Value
            $explicitImage = $bookingServiceIdentity.PSObject.Properties['image']
            $imageName = if ($explicitImage) { [string]$explicitImage.Value } else { $composeProject + '-booking-service' }
            $configuredImages = (Invoke-CapturedNative 'docker' ($composeArguments + @('config', '--images'))) -split '\s+'
            if (-not $imageName -or $imageName -match '\s' -or $configuredImages -notcontains $imageName) {
                throw 'Configured booking-service image identity could not be resolved.'
            }
            # Inspect local image availability before run; --build=false and --pull never preserve it.
            $oneOffImageId = Invoke-CapturedNative 'docker' @('image', 'inspect', '--format', '{{.Id}}', $imageName)
            if ($sourceRecord.runtimeImageId -and $sourceRecord.runtimeImageId -ne $oneOffImageId) {
                throw 'Configured image differs from deployed source container image.'
            }
            $sourceRecord.runtimeImageId = $oneOffImageId
            $sourceRecord.runtimeImageName = $imageName
        }
        # Complete endpoint, project, container and (for OneOff) image checks before cp.
        if ($copyDeployedJar) { Invoke-CheckedNative 'docker' @('cp', ($ContainerId + ':/app/smartoffice.jar'), $BackendJar) }
    }
    # Use the runtime API: legacy PowerShell launched by Node can inherit a PS7 module path
    # where the Get-FileHash module function is unavailable, despite native commands working.
    $jarStream = [IO.File]::OpenRead($BackendJar)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { $sourceRecord.jarSha256 = [BitConverter]::ToString($sha256.ComputeHash($jarStream)).Replace('-', '').ToLowerInvariant() }
    finally { $sha256.Dispose(); $jarStream.Dispose() }
    $sourceRecord | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'artifact-source.json') -Encoding utf8
    Write-Output ('SOURCE ' + $sourceRecord.source + ' sha256=' + $sourceRecord.jarSha256)
    $step = 'extract-existing-dependencies'
    Push-Location $extractRoot
    try { Invoke-CheckedNative $jarTool @('xf', $BackendJar, 'BOOT-INF/classes', 'BOOT-INF/lib') }
    finally { Pop-Location }
    $classpath = (Join-Path $extractRoot 'BOOT-INF/classes') + ';' + (Join-Path $extractRoot 'BOOT-INF/lib/*')
    $step = 'java17-compilation'
    Invoke-CheckedNative $javac @('--release', '17', '-encoding', 'UTF-8', '-proc:none', '-cp', $classpath,
        '-d', $classRoot, (Join-Path $PSScriptRoot 'RealMiddlewareProbe.java'))
    Write-Output 'PASS java17-compilation existingApplicationArtifact=true'
    Write-Output ('ARTIFACT ' + $runRoot)
    if ($Mode -ne 'CompileOnly') {
        $javaMode = switch ($Mode) { 'Redis' { 'redis' } 'MqDuplicate' { 'mq-duplicate' }
            'MqEarliest' { 'mq-earliest' } 'MqSendOnly' { 'mq-send-only' } }
        if ($OneOff) {
            $containerClassRoot = '/tmp/probe-classes'
            $step = 'oneoff-image-recheck'
            $launchImageId = Invoke-CapturedNative 'docker' @('image', 'inspect', '--format', '{{.Id}}', $sourceRecord.runtimeImageName)
            if ($launchImageId -ne $oneOffImageId) { throw 'Booking image changed during probe compilation.' }
            $launchArguments = $composeArguments + @('run', '--rm', '--no-deps', '--build=false', '--pull', 'never',
                '--entrypoint', 'java', '--volume', ($classRoot + ':/tmp/probe-classes:ro'), 'booking-service')
        } else {
            $step = 'copy-test-only-classes'
            $containerClassRoot = '/tmp/' + $runName
            Invoke-CheckedNative 'docker' @('cp', $classRoot, ($ContainerId + ':' + $containerClassRoot))
            $launchArguments = @('exec', $ContainerId, 'java')
        }
        $javaArguments = $launchArguments + @('-Xms32m', '-Xmx160m', '-XX:ActiveProcessorCount=2',
            '-Dloader.main=RealMiddlewareProbe', ('-Dloader.path=' + $containerClassRoot),
            '-cp', '/app/smartoffice.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher', $javaMode)
        if ($Mode -like 'Mq*') { $javaArguments += $EventId }
        elseif ($RoomId -ne 0) { $javaArguments += [string]$RoomId }
        $step = 'real-middleware-execution'
        # Docker exec inherits existing container credentials; none are copied to arguments or stdout.
        Invoke-CheckedNative 'docker' $javaArguments
    }
    Write-Output ('PASS middleware-probe-script mode=' + $Mode)
    exit 0
} catch {
    $missingCommand = if ($_.Exception -is [System.Management.Automation.CommandNotFoundException]) {
        ' command=' + $_.Exception.CommandName
    } else { '' }
    Write-Error -Message ('FAIL ' + $step + ' type=' + $_.Exception.GetType().Name + $missingCommand) -ErrorAction Continue
    exit 1
}
