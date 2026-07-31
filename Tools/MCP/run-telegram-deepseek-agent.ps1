[CmdletBinding(PositionalBinding = $false)]
param(
  [switch] $PromptForApiKey,

  [ValidateSet("Auto", "Wsl", "Windows")]
  [string] $Backend = "Auto",

  [string] $Python = "C:\Python313\python.exe",

  [string] $Adb = "D:\AndroidSdk\platform-tools\adb.exe",

  [string] $WslDistribution = "Ubuntu-24.04",

  [string] $WslPython = "python3",

  [string] $WslAdb = "/mnt/d/AndroidSdk/linux-platform-tools/platform-tools/adb",

  [int] $WslAdbServerPort = 15037,

  [int] $EmulatorBridgePort = 15555,

  [string] $Serial,

  [switch] $InstallApk,

  [string] $Apk = "D:\TelegramBuild\gradle\_TMessagesProj_App\outputs\apk\afat\debug\app.apk",

  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]] $AgentArguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$package = "org.telegram.messenger.beta"
$mcpPort = 19876
$runner = Join-Path $PSScriptRoot "telegram_deepseek_agent.py"
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$wslModule = Join-Path $PSScriptRoot "TelegramWslAdb.psm1"

if (-not (Test-Path -LiteralPath $runner)) {
  throw "Agent runner not found: $runner"
}

if ($Backend -eq "Auto") {
  if ((Get-Command wsl.exe -ErrorAction SilentlyContinue) -and
      (Test-Path -LiteralPath $wslModule)) {
    $Backend = "Wsl"
  } else {
    $Backend = "Windows"
  }
}

$wslContext = $null
if ($Backend -eq "Wsl") {
  Import-Module -Name $wslModule -Force
  $wslContext = Initialize-TelegramWslAdbBridge `
    -Distribution $WslDistribution `
    -WslAdb $WslAdb `
    -AdbServerPort $WslAdbServerPort `
    -BridgePort $EmulatorBridgePort
  if (-not $Serial) {
    $Serial = $wslContext.Serial
  }
} else {
  if (-not (Test-Path -LiteralPath $Python)) {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonCommand) {
      throw "Python not found: $Python"
    }
    $Python = $pythonCommand.Source
  }
  if (-not (Test-Path -LiteralPath $Adb)) {
    $fallbackAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $fallbackAdb)) {
      throw "adb not found: $Adb"
    }
    $Adb = $fallbackAdb
  }
  & $Adb start-server | Out-Null
}

function Invoke-SelectedAdb {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [string[]] $Arguments,

    [switch] $AllowFailure
  )

  if ($Backend -eq "Wsl") {
    return Invoke-TelegramWslAdb `
      -Context $wslContext `
      -Arguments $Arguments `
      -AllowFailure:$AllowFailure
  }

  $savedErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $rawOutput = @(& $Adb @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $savedErrorActionPreference
  }
  $result = [pscustomobject] @{
    ExitCode = [int] $exitCode
    Output = @($rawOutput | ForEach-Object { [string] $_ })
  }
  if ($result.ExitCode -ne 0 -and -not $AllowFailure) {
    throw "adb failed ($($result.ExitCode)): $($Arguments -join ' '): $($result.Output -join ' ')"
  }
  return $result
}

if (-not $Serial) {
  $deviceResult = Invoke-SelectedAdb -Arguments @("devices")
  $devices = @(
    $deviceResult.Output |
      Select-Object -Skip 1 |
      ForEach-Object {
        if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
      }
  )
  if ($devices.Count -eq 0) {
    throw "No authorized Android device/emulator is connected."
  }
  if ($devices.Count -gt 1) {
    throw "Multiple Android devices are connected; pass -Serial. Found: $($devices -join ', ')"
  }
  $Serial = $devices[0]
}

$installedResult = Invoke-SelectedAdb `
  -Arguments @("-s", $Serial, "shell", "pm", "path", $package) `
  -AllowFailure
$installed = ($installedResult.Output -join "").Trim()
$needsInstall = $InstallApk -or -not $installed.StartsWith("package:")
if ($needsInstall) {
  if (-not (Test-Path -LiteralPath $Apk)) {
    throw "Telegram MCP APK not found: $Apk"
  }
  $installPath = $Apk
  if ($Backend -eq "Wsl") {
    $installPath = ConvertTo-TelegramWslPath `
      -Distribution $WslDistribution `
      -WindowsPath $Apk
  }
  Write-Host "Installing Telegram MCP debug APK without clearing app data..." -ForegroundColor Cyan
  $installResult = Invoke-SelectedAdb `
    -Arguments @("-s", $Serial, "install", "--no-streaming", "-r", "-t", $installPath)
  $installText = ($installResult.Output -join "`n").Trim()
  if ($installText -notmatch "(?m)^Success\s*$") {
    throw "APK installation did not report success: $installText"
  }
}

$installedResult = Invoke-SelectedAdb `
  -Arguments @("-s", $Serial, "shell", "pm", "path", $package)
$installed = ($installedResult.Output -join "").Trim()
if (-not $installed.StartsWith("package:")) {
  throw "Telegram MCP debug package is not installed on ${Serial}: $package"
}

# Starting the package initializes ApplicationLoader and the loopback MCP server.
Invoke-SelectedAdb `
  -Arguments @("-s", $Serial, "shell", "monkey", "-p", $package, "-c", "android.intent.category.LAUNCHER", "1") `
  -AllowFailure | Out-Null

$token = ""
for ($attempt = 1; $attempt -le 30; $attempt++) {
  $tokenResult = Invoke-SelectedAdb `
    -Arguments @("-s", $Serial, "shell", "run-as", $package, "cat", "files/mcp/token") `
    -AllowFailure
  $candidate = ($tokenResult.Output -join "").Trim()
  if ($candidate -match '^[0-9a-f]{64}$') {
    $token = $candidate
    break
  }
  Start-Sleep -Seconds 1
}
if (-not $token) {
  throw "Could not read the MCP token. Confirm this is the debuggable afatDebug build and launch it once."
}

Invoke-SelectedAdb `
  -Arguments @("-s", $Serial, "forward", "--remove", "tcp:$mcpPort") `
  -AllowFailure | Out-Null
Invoke-SelectedAdb `
  -Arguments @("-s", $Serial, "forward", "tcp:$mcpPort", "tcp:$mcpPort") | Out-Null

if (-not $AgentArguments -or $AgentArguments.Count -eq 0) {
  $AgentArguments = @("doctor")
}

$saved = @{
  HadApiKey = Test-Path Env:DEEPSEEK_API_KEY
  ApiKey = $env:DEEPSEEK_API_KEY
  HadToken = Test-Path Env:TELEGRAM_MCP_TOKEN
  Token = $env:TELEGRAM_MCP_TOKEN
  HadUrl = Test-Path Env:TELEGRAM_MCP_URL
  Url = $env:TELEGRAM_MCP_URL
  HadWslEnv = Test-Path Env:WSLENV
  WslEnv = $env:WSLENV
}
$temporaryApiKey = $false

if ($PromptForApiKey) {
  $secureKey = Read-Host "DeepSeek API Key" -AsSecureString
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
  try {
    $env:DEEPSEEK_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $temporaryApiKey = $true
  } finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
  }
}

$env:TELEGRAM_MCP_TOKEN = $token
$env:TELEGRAM_MCP_URL = "http://127.0.0.1:$mcpPort/mcp"

try {
  if ($Backend -eq "Wsl") {
    $wslRepoRoot = ConvertTo-TelegramWslPath `
      -Distribution $WslDistribution `
      -WindowsPath $repoRoot
    $wslEnvEntries = @("TELEGRAM_MCP_TOKEN", "TELEGRAM_MCP_URL", "DEEPSEEK_API_KEY")
    if ($saved.HadWslEnv -and $saved.WslEnv) {
      $wslEnvEntries += ($saved.WslEnv -split ":")
    }
    $env:WSLENV = ($wslEnvEntries | Select-Object -Unique) -join ":"
    $wslArguments = @(
      "-d", $WslDistribution,
      "--cd", $wslRepoRoot,
      "--exec", $WslPython,
      "Tools/MCP/telegram_deepseek_agent.py",
      "--transport", "http"
    ) + $AgentArguments
    $savedErrorActionPreference = $ErrorActionPreference
    try {
      $ErrorActionPreference = "Continue"
      & wsl.exe @wslArguments 2>$null
      $result = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $savedErrorActionPreference
    }
  } else {
    & $Python $runner --transport http @AgentArguments
    $result = $LASTEXITCODE
  }
} finally {
  $token = $null
  if ($temporaryApiKey -and -not $saved.HadApiKey) {
    Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
  } elseif ($temporaryApiKey) {
    $env:DEEPSEEK_API_KEY = $saved.ApiKey
  }
  if ($saved.HadToken) { $env:TELEGRAM_MCP_TOKEN = $saved.Token } else { Remove-Item Env:TELEGRAM_MCP_TOKEN -ErrorAction SilentlyContinue }
  if ($saved.HadUrl) { $env:TELEGRAM_MCP_URL = $saved.Url } else { Remove-Item Env:TELEGRAM_MCP_URL -ErrorAction SilentlyContinue }
  if ($saved.HadWslEnv) { $env:WSLENV = $saved.WslEnv } else { Remove-Item Env:WSLENV -ErrorAction SilentlyContinue }
}

exit $result
