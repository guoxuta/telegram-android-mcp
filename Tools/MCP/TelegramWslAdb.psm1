Set-StrictMode -Version Latest

function Invoke-TelegramWslProcess {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string] $Distribution,

    [Parameter(Mandatory = $true)]
    [string[]] $Arguments,

    [switch] $AllowFailure
  )

  $savedErrorActionPreference = $ErrorActionPreference
  $hadNativePreference = Test-Path Variable:PSNativeCommandUseErrorActionPreference
  if ($hadNativePreference) {
    $savedNativePreference = $PSNativeCommandUseErrorActionPreference
    $PSNativeCommandUseErrorActionPreference = $false
  }

  try {
    $ErrorActionPreference = "Continue"
    $wslArguments = @("-d", $Distribution, "--exec") + $Arguments
    $rawOutput = @(& wsl.exe @wslArguments 2>&1)
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $savedErrorActionPreference
    if ($hadNativePreference) {
      $PSNativeCommandUseErrorActionPreference = $savedNativePreference
    }
  }

  $output = @(
    foreach ($entry in $rawOutput) {
      # PowerShell materializes each line written by wsl.exe to stderr as an
      # ErrorRecord. This host emits a multi-line, UTF-16 localhost-proxy
      # warning on every invocation, so stderr cannot be mixed into stdout
      # without corrupting machine-readable adb/path output. Native exit codes
      # remain authoritative; adb also returns actionable failures on stdout.
      if ($entry -is [System.Management.Automation.ErrorRecord]) {
        continue
      }
      $text = ([string] $entry) -replace "`0", ""
      if ($text) {
        $text
      }
    }
  )

  $result = [pscustomobject] @{
    ExitCode = [int] $exitCode
    Output = $output
  }
  if ($result.ExitCode -ne 0 -and -not $AllowFailure) {
    $detail = ($result.Output -join [Environment]::NewLine).Trim()
    if (-not $detail) {
      $detail = "no diagnostic output"
    }
    throw "WSL command failed ($($result.ExitCode)): $($Arguments -join ' '): $detail"
  }
  return $result
}

function ConvertTo-TelegramWslPath {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string] $Distribution,

    [Parameter(Mandatory = $true)]
    [string] $WindowsPath
  )

  $resolved = (Resolve-Path -LiteralPath $WindowsPath).Path
  $result = Invoke-TelegramWslProcess `
    -Distribution $Distribution `
    -Arguments @("wslpath", "-a", "-u", $resolved)
  $path = ($result.Output -join "").Trim()
  if (-not $path.StartsWith("/")) {
    throw "Could not convert to a WSL path: $resolved"
  }
  return $path
}

function Initialize-TelegramWslAdbBridge {
  [CmdletBinding()]
  param(
    [string] $Distribution = "Ubuntu-24.04",
    [string] $WslAdb = "/mnt/d/AndroidSdk/linux-platform-tools/platform-tools/adb",
    [int] $AdbServerPort = 15037,
    [int] $BridgePort = 15555,
    [int] $EmulatorAdbPort = 5555,
    [int] $ReadyTimeoutSeconds = 45
  )

  if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "wsl.exe is unavailable; install/enable WSL or select the Windows ADB backend."
  }

  $executable = Invoke-TelegramWslProcess `
    -Distribution $Distribution `
    -Arguments @("test", "-x", $WslAdb) `
    -AllowFailure
  if ($executable.ExitCode -ne 0) {
    throw "Linux adb is unavailable in ${Distribution}: $WslAdb"
  }

  $route = Invoke-TelegramWslProcess `
    -Distribution $Distribution `
    -Arguments @("ip", "-4", "route", "show", "default")
  $routeText = $route.Output -join " "
  if ($routeText -notmatch "\bvia\s+([0-9]+(?:\.[0-9]+){3})\b") {
    throw "Could not determine the Windows gateway from WSL: $routeText"
  }
  $gateway = $Matches[1]

  $addresses = Invoke-TelegramWslProcess `
    -Distribution $Distribution `
    -Arguments @("hostname", "-I")
  $wslAddress = (($addresses.Output -join " ") -split "\s+" | Where-Object {
      $_ -match "^[0-9]+(?:\.[0-9]+){3}$"
    } | Select-Object -First 1)
  if (-not $wslAddress) {
    throw "Could not determine the WSL IPv4 address."
  }

  $windowsGateway = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $gateway -ErrorAction SilentlyContinue
  if (-not $windowsGateway) {
    throw "The WSL gateway $gateway is not assigned to a Windows network interface."
  }

  $emulatorListener = Get-NetTCPConnection `
    -State Listen `
    -LocalAddress "127.0.0.1" `
    -LocalPort $EmulatorAdbPort `
    -ErrorAction SilentlyContinue
  if (-not $emulatorListener) {
    throw "The Android emulator is not listening on 127.0.0.1:$EmulatorAdbPort. Start the AVD first."
  }

  $portProxyText = (& netsh interface portproxy show v4tov4) -join "`n"
  $gatewayPattern = [regex]::Escape($gateway)
  $ownedEntryPattern = "(?m)^\s*$gatewayPattern\s+$BridgePort\s+127\.0\.0\.1\s+$EmulatorAdbPort\s*$"
  if ($portProxyText -notmatch $ownedEntryPattern) {
    $conflictPattern = "(?m)^\s*$gatewayPattern\s+$BridgePort\s+(.+)$"
    if ($portProxyText -match $conflictPattern) {
      throw "Refusing to overwrite an unrelated portproxy on ${gateway}:$BridgePort."
    }
    & netsh interface portproxy add v4tov4 `
      "listenaddress=$gateway" `
      "listenport=$BridgePort" `
      "connectaddress=127.0.0.1" `
      "connectport=$EmulatorAdbPort" `
      "protocol=tcp" | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "Could not create the scoped WSL-to-emulator portproxy. Run once from an elevated PowerShell."
    }
  }

  $firewallName = "Telegram MCP WSL ADB bridge $BridgePort"
  $firewallRule = Get-NetFirewallRule -DisplayName $firewallName -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if (-not $firewallRule) {
    try {
      $firewallRule = New-NetFirewallRule `
        -DisplayName $firewallName `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalAddress $gateway `
        -RemoteAddress $wslAddress `
        -LocalPort $BridgePort `
        -Profile Any
    } catch {
      throw "Could not create the scoped WSL firewall rule. Run once from an elevated PowerShell: $($_.Exception.Message)"
    }
  } else {
    $ruleNeedsUpdate = $firewallRule.Enabled -ne "True" -or
      $firewallRule.Direction -ne "Inbound" -or
      $firewallRule.Action -ne "Allow"
    if ($ruleNeedsUpdate) {
      Set-NetFirewallRule -InputObject $firewallRule -Enabled True -Direction Inbound -Action Allow -Profile Any | Out-Null
    }
    $addressFilter = $firewallRule | Get-NetFirewallAddressFilter
    if (($addressFilter.LocalAddress -join ",") -ne $gateway -or
        ($addressFilter.RemoteAddress -join ",") -ne $wslAddress) {
      Set-NetFirewallAddressFilter `
        -InputObject $addressFilter `
        -LocalAddress $gateway `
        -RemoteAddress $wslAddress | Out-Null
    }
    $portFilter = $firewallRule | Get-NetFirewallPortFilter
    if (($portFilter.Protocol -join ",") -ne "TCP" -or
        ($portFilter.LocalPort -join ",") -ne [string] $BridgePort) {
      Set-NetFirewallPortFilter `
        -InputObject $portFilter `
        -Protocol TCP `
        -LocalPort $BridgePort | Out-Null
    }
  }

  $keyDirectory = Join-Path $env:USERPROFILE ".android"
  $wslKeyDirectory = ConvertTo-TelegramWslPath `
    -Distribution $Distribution `
    -WindowsPath $keyDirectory
  Invoke-TelegramWslProcess `
    -Distribution $Distribution `
    -Arguments @(
      "env",
      "ADB_USB=0",
      "ADB_EMU=0",
      "ADB_MDNS=0",
      "ADB_VENDOR_KEYS=$wslKeyDirectory",
      $WslAdb,
      "-P", [string] $AdbServerPort,
      "start-server"
    ) | Out-Null

  $serial = "${gateway}:$BridgePort"
  Invoke-TelegramWslProcess `
    -Distribution $Distribution `
    -Arguments @($WslAdb, "-P", [string] $AdbServerPort, "connect", $serial) `
    -AllowFailure | Out-Null

  $deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
  do {
    $devices = Invoke-TelegramWslProcess `
      -Distribution $Distribution `
      -Arguments @($WslAdb, "-P", [string] $AdbServerPort, "devices", "-l") `
      -AllowFailure
    $deviceText = $devices.Output -join "`n"
    if ($deviceText -match "(?m)^$([regex]::Escape($serial))\s+device(?:\s|$)") {
      return [pscustomobject] @{
        Distribution = $Distribution
        WslAdb = $WslAdb
        AdbServerPort = $AdbServerPort
        Serial = $serial
        Gateway = $gateway
        WslAddress = $wslAddress
        BridgePort = $BridgePort
        EmulatorAdbPort = $EmulatorAdbPort
      }
    }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)

  throw "The emulator remained unavailable through $serial for $ReadyTimeoutSeconds seconds. Last devices output: $deviceText"
}

function Invoke-TelegramWslAdb {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [psobject] $Context,

    [Parameter(Mandatory = $true)]
    [string[]] $Arguments,

    [switch] $AllowFailure
  )

  return Invoke-TelegramWslProcess `
    -Distribution $Context.Distribution `
    -Arguments (@($Context.WslAdb, "-P", [string] $Context.AdbServerPort) + $Arguments) `
    -AllowFailure:$AllowFailure
}

Export-ModuleMember `
  -Function ConvertTo-TelegramWslPath, Initialize-TelegramWslAdbBridge, Invoke-TelegramWslAdb
