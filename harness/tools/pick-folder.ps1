# Drives the native Windows "select folder" dialog opened by showDirectoryPicker(), then accepts
# the browser's "Edit files" permission bubble. Locale independent where possible (uses the common-dialog
# automation ids); falls back to button-name matching for the permission bubble.
#   powershell -File pick-folder.ps1 -Path C:\fx\1k -ProcessName chrome [-TimeoutSec 30] [-Probe]
# Must run in Windows PowerShell 5.1 (UIAutomationClient). Exit codes: 0 ok, 2 dialog not found, 3 bubble not found.
param(
  [Parameter(Mandatory)][string]$Path,
  [Parameter(Mandatory)][ValidateSet('chrome', 'msedge')][string]$ProcessName,
  [int]$TimeoutSec = 30,
  [switch]$Probe
)
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$AE = [System.Windows.Automation.AutomationElement]
$TS = [System.Windows.Automation.TreeScope]
$prop = [System.Windows.Automation.AutomationElement]
$procIds = @(Get-Process -Name $ProcessName -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })

function Find-Dialog {
  $cond = New-Object System.Windows.Automation.PropertyCondition($AE::ClassNameProperty, '#32770')
  foreach ($w in $AE::RootElement.FindAll($TS::Children, $cond)) {
    if ($procIds -contains $w.Current.ProcessId) { return $w }
  }
}

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$dlg = $null
while (-not $dlg -and (Get-Date) -lt $deadline) { $dlg = Find-Dialog; if (-not $dlg) { Start-Sleep -Milliseconds 250 } }
if (-not $dlg) { Write-Error 'folder dialog not found'; exit 2 }

if ($Probe) {
  foreach ($e in $dlg.FindAll($TS::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) {
    '{0} | id={1} | name={2} | class={3}' -f $e.Current.ControlType.ProgrammaticName, $e.Current.AutomationId, $e.Current.Name, $e.Current.ClassName
  }
  exit 0
}

# The folder picker exposes no file-name box through UIA, so navigate with the address bar (Ctrl+L), then press the
# 'select folder' button (a Pane of class Button with automation id 1), which picks the folder currently shown.
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class W32 {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  public static void Click(int x, int y) { SetCursorPos(x, y); mouse_event(2, 0, 0, 0, UIntPtr.Zero); mouse_event(4, 0, 0, 0, UIntPtr.Zero); }
}
'@
[W32]::SetProcessDPIAware() | Out-Null
[W32]::SetForegroundWindow([IntPtr]$dlg.Current.NativeWindowHandle) | Out-Null
$okCond = New-Object System.Windows.Automation.AndCondition(
  (New-Object System.Windows.Automation.PropertyCondition($AE::AutomationIdProperty, '1')),
  (New-Object System.Windows.Automation.PropertyCondition($AE::ClassNameProperty, 'Button')))
$ok = $dlg.FindFirst($TS::Descendants, $okCond)
if (-not $ok) { Write-Error 'select-folder button not found (run with -Probe)'; exit 2 }
$r = $ok.Current.BoundingRectangle
$bx = [int]($r.X + $r.Width / 2); $by = [int]($r.Y + $r.Height / 2)
# Click into the 'Ordner:' box (same row band, ~30 px above the button, left of it), type the path, accept.
[W32]::Click($bx - 400, $by - [int]($r.Height * 1.2))
Start-Sleep -Milliseconds 300
$escaped = [regex]::Replace($Path, '[+^%~(){}\[\]]', { param($m) '{' + $m.Value + '}' })
[System.Windows.Forms.SendKeys]::SendWait($escaped)
Start-Sleep -Milliseconds 300
[W32]::Click($bx, $by)

# Permission bubble: a browser-drawn button; match by name in English/German.
$names = 'Edit files', 'Save changes', 'Dateien bearbeiten', 'Änderungen speichern', 'Bearbeiten'
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
  foreach ($w in $AE::RootElement.FindAll($TS::Children, [System.Windows.Automation.Condition]::TrueCondition)) {
    if ($procIds -notcontains $w.Current.ProcessId) { continue }
    $btns = $w.FindAll($TS::Descendants, (New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)))
    foreach ($b in $btns) {
      if ($names -contains $b.Current.Name) { $b.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke(); exit 0 }
    }
  }
  Start-Sleep -Milliseconds 300
}
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap([System.Windows.Forms.SystemInformation]::VirtualScreen.Width, [System.Windows.Forms.SystemInformation]::VirtualScreen.Height)
[System.Drawing.Graphics]::FromImage($bmp).CopyFromScreen([System.Windows.Forms.SystemInformation]::VirtualScreen.Location, [System.Drawing.Point]::Empty, $bmp.Size)
$shot = Join-Path $env:TEMP 'pick-folder-failure.png'
$bmp.Save($shot); Write-Host "screenshot: $shot"
Write-Host 'visible browser buttons:'
foreach ($w in $AE::RootElement.FindAll($TS::Children, [System.Windows.Automation.Condition]::TrueCondition)) {
  if ($procIds -notcontains $w.Current.ProcessId) { continue }
  foreach ($b in $w.FindAll($TS::Descendants, (New-Object System.Windows.Automation.PropertyCondition($AE::ControlTypeProperty, [System.Windows.Automation.ControlType]::Button)))) {
    '  [{0}] {1}' -f $w.Current.ClassName, $b.Current.Name
  }
}
Write-Error 'permission bubble button not found'
exit 3
