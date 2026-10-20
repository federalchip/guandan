@echo off
setlocal EnableExtensions
set "GUANDAN_RUN_DIR=%~dp0.run"

if not exist "%GUANDAN_RUN_DIR%" (
  echo [INFO] No services started by start.cmd were found.
  exit /b 0
)
where powershell.exe >nul 2>&1 || (
  echo [ERROR] Windows PowerShell is required to verify process identity.
  pause
  exit /b 1
)

powershell.exe -NoProfile -Command "$failed=$false; foreach($name in @('backend','frontend')) { $file=Join-Path $env:GUANDAN_RUN_DIR ($name+'.pid'); if(-not (Test-Path -LiteralPath $file)) { Write-Host ('[INFO] '+$name+' is not recorded'); continue }; try { $parts=(Get-Content -LiteralPath $file -Raw).Trim().Split(','); if($parts.Count -ne 2 -or $parts[0] -notmatch '^\d+$' -or $parts[1] -notmatch '^\d+$') { throw 'invalid marker' }; $id=[int]$parts[0]; $ticks=[long]$parts[1]; $p=Get-Process -Id $id -ErrorAction SilentlyContinue; if($p -and $p.ProcessName -eq 'cmd' -and $p.StartTime.ToUniversalTime().Ticks -eq $ticks) { & taskkill.exe /PID $id /T /F | Out-Host; if($LASTEXITCODE -ne 0) { throw 'taskkill failed' }; Write-Host ('[OK] Stopped '+$name) } else { Write-Host ('[INFO] '+$name+' is already stopped') }; Remove-Item -LiteralPath $file -Force } catch { Write-Host ('[ERROR] Could not safely stop '+$name+': '+$_.Exception.Message); $failed=$true } }; if($failed) { exit 1 }"
if errorlevel 1 (
  echo [ERROR] Some services could not be stopped. Check the messages above.
  pause
  exit /b 1
)
echo [OK] Stop complete.
exit /b 0
