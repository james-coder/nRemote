# Creates a Desktop shortcut ("nRemote") that launches nRemote.jar with the
# TI-bundled Java 7 JRE and the nRemote icon.
#
# Run it AFTER copying nRemote.jar (and nremote.ico) into the TI-Nspire
# software's Java/lib folder, as the README describes:
#     powershell -ExecutionPolicy Bypass -File .\Create-Desktop-Shortcut.ps1
#
# It auto-detects the TI install; override with -TiRoot / -Jar if needed.

param(
    [string]$TiRoot = "",
    [string]$Jar    = ""
)

$ErrorActionPreference = "Stop"

function Find-TiRoot {
    foreach ($base in @("${env:ProgramFiles(x86)}\TI Education", "$env:ProgramFiles\TI Education")) {
        if (Test-Path $base) {
            $hit = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                   Where-Object { Test-Path (Join-Path $_.FullName "jre\bin\javaw.exe") } |
                   Select-Object -First 1
            if ($hit) { return $hit.FullName }
        }
    }
    return $null
}

if (-not $TiRoot) { $TiRoot = Find-TiRoot }
if (-not $TiRoot) { throw "Could not find a TI-Nspire install. Pass -TiRoot 'C:\Path\To\TI-Nspire ...'." }

$javaw = Join-Path $TiRoot "jre\bin\javaw.exe"
if (-not (Test-Path $javaw)) { throw "TI JRE not found at $javaw" }

$lib = Join-Path $TiRoot "lib"
if (-not $Jar) {
    foreach ($cand in @((Join-Path $lib "nRemote.jar"), (Join-Path $TiRoot "nRemote.jar"))) {
        if (Test-Path $cand) { $Jar = $cand; break }
    }
    if (-not $Jar) {
        $found = Get-ChildItem $TiRoot -Recurse -Filter "nRemote.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { $Jar = $found.FullName }
    }
}
if (-not $Jar -or -not (Test-Path $Jar)) {
    throw "nRemote.jar not found. Copy it into '$lib' first, or pass -Jar '<path>\nRemote.jar'."
}

$jarDir = Split-Path $Jar -Parent
$icon   = Join-Path $PSScriptRoot "nremote.ico"
if (-not (Test-Path $icon)) { $icon = Join-Path $jarDir "nremote.ico" }

$desktop  = [Environment]::GetFolderPath("Desktop")
$linkPath = Join-Path $desktop "nRemote.lnk"

# Explicit classpath (works for both Computer Software and Computer Link).
$cpJars = @("commproxy.jar","navnet.jar","navnetcommproxy.jar","upgrade.jar") |
          ForEach-Object { Join-Path $jarDir $_ }
$cp = (@($Jar) + $cpJars) -join ";"

$shell = New-Object -ComObject WScript.Shell
$sc = $shell.CreateShortcut($linkPath)
$sc.TargetPath       = $javaw
$sc.Arguments        = "-Djava.library.path=`"$jarDir`" -cp `"$cp`" nRemote"
$sc.WorkingDirectory = $jarDir
if (Test-Path $icon) { $sc.IconLocation = $icon }
$sc.Description      = "nRemote - TI-Nspire remote control"
$sc.Save()

Write-Host "Created shortcut: $linkPath"
Write-Host "  runs: `"$javaw`" $($sc.Arguments)"
Write-Host "Remember to launch the TI-Nspire software first, then use the shortcut."
