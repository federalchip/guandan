@echo off
setlocal EnableExtensions
set "ROOT=%~dp0"
cd /d "%ROOT%" || exit /b 1

if not exist "%ROOT%backend\mvnw.cmd" (
  echo [ERROR] Missing backend\mvnw.cmd.
  goto :failed
)
if not exist "%ROOT%frontend\package-lock.json" (
  echo [ERROR] Missing frontend\package-lock.json.
  goto :failed
)

where java >nul 2>&1 || (
  echo [ERROR] JDK 17 is required. Add its bin directory to PATH.
  goto :failed
)
set "JAVA_VERSION="
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%~V"
if not "%JAVA_VERSION:~0,3%"=="17." (
  echo [ERROR] JDK 17 is required; found %JAVA_VERSION%.
  goto :failed
)
where node >nul 2>&1 || (
  echo [ERROR] Node.js 22.12 or newer is required.
  goto :failed
)
node -e "const v=process.versions.node.split('.').map(Number);process.exit(v[0]>22||(v[0]===22&&v[1]>=12)?0:1)"
if errorlevel 1 (
  echo [ERROR] Node.js 22.12 or newer is required.
  goto :failed
)
where npm.cmd >nul 2>&1 || (
  echo [ERROR] npm is required.
  goto :failed
)
where powershell.exe >nul 2>&1 || (
  echo [ERROR] Windows PowerShell is required.
  goto :failed
)
if not defined USERPROFILE (
  echo [ERROR] USERPROFILE is required for the Maven cache.
  goto :failed
)
set "MAVEN_OPTS="-Duser.home=%USERPROFILE%" %MAVEN_OPTS%"

if /i "%~1"=="--check" (
  echo [OK] JDK, Node.js, npm and project files are ready.
  exit /b 0
)

echo [INFO] Checking Maven Wrapper...
pushd "%ROOT%backend"
call mvnw.cmd -version
set "MAVEN_STATUS=%ERRORLEVEL%"
popd
if not "%MAVEN_STATUS%"=="0" (
  echo [ERROR] Maven Wrapper could not start.
  goto :failed
)

powershell.exe -NoProfile -Command "if (Get-NetTCPConnection -State Listen -LocalPort 8000,5173 -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
  echo [ERROR] Port 8000 or 5173 is already in use. Stop that service and retry.
  goto :failed
)

set "GUANDAN_ROOT=%ROOT%"
set "GUANDAN_RUN_DIR=%ROOT%.run"
powershell.exe -NoProfile -Command "foreach($name in @('backend','frontend')) { $file=Join-Path $env:GUANDAN_RUN_DIR ($name+'.pid'); if(-not (Test-Path -LiteralPath $file)) { continue }; try { $parts=(Get-Content -LiteralPath $file -Raw).Trim().Split(','); if($parts.Count -ne 2) { continue }; $p=Get-Process -Id ([int]$parts[0]) -ErrorAction SilentlyContinue; if($p -and $p.ProcessName -eq 'cmd' -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$parts[1]) { Write-Host ('[ERROR] '+$name+' is already starting or running. Use stop.cmd first.'); exit 1 } } catch { } }"
if errorlevel 1 goto :failed

if not exist "%ROOT%frontend\node_modules\vite\package.json" (
  echo [INFO] Installing frontend dependencies with npm ci...
  pushd "%ROOT%frontend"
  call npm.cmd ci
  if errorlevel 1 (
    popd
    echo [ERROR] npm ci failed.
    goto :failed
  )
  popd
)

if not exist "%GUANDAN_RUN_DIR%" mkdir "%GUANDAN_RUN_DIR%"
if errorlevel 1 (
  echo [ERROR] Could not create the process marker directory.
  goto :failed
)
if exist "%GUANDAN_RUN_DIR%\backend.pid" del /q "%GUANDAN_RUN_DIR%\backend.pid"
if exist "%GUANDAN_RUN_DIR%\frontend.pid" del /q "%GUANDAN_RUN_DIR%\frontend.pid"

echo [INFO] Starting backend and frontend in the background...
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; $p=Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c','call mvnw.cmd spring-boot:run') -WorkingDirectory (Join-Path $env:GUANDAN_ROOT 'backend') -RedirectStandardOutput (Join-Path $env:GUANDAN_RUN_DIR 'backend.out.log') -RedirectStandardError (Join-Path $env:GUANDAN_RUN_DIR 'backend.err.log') -WindowStyle Hidden -PassThru; ('{0},{1}' -f $p.Id,$p.StartTime.ToUniversalTime().Ticks) | Set-Content -LiteralPath (Join-Path $env:GUANDAN_RUN_DIR 'backend.pid') -Encoding ASCII"
if errorlevel 1 (
  echo [ERROR] Could not start the backend process.
  goto :failed
)
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop'; $p=Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c','npm.cmd run dev -- --strictPort') -WorkingDirectory (Join-Path $env:GUANDAN_ROOT 'frontend') -RedirectStandardOutput (Join-Path $env:GUANDAN_RUN_DIR 'frontend.out.log') -RedirectStandardError (Join-Path $env:GUANDAN_RUN_DIR 'frontend.err.log') -WindowStyle Hidden -PassThru; ('{0},{1}' -f $p.Id,$p.StartTime.ToUniversalTime().Ticks) | Set-Content -LiteralPath (Join-Path $env:GUANDAN_RUN_DIR 'frontend.pid') -Encoding ASCII"
if errorlevel 1 (
  echo [ERROR] Could not start the frontend process.
  goto :failed
)

echo [INFO] Waiting for backend and frontend to become ready...
powershell.exe -NoProfile -Command "$deadline=(Get-Date).AddMinutes(5); do { try { $health=Invoke-RestMethod 'http://localhost:8000/api/v1/health' -TimeoutSec 2; $page=Invoke-WebRequest 'http://localhost:5173/' -UseBasicParsing -TimeoutSec 2; if ($health.status -eq 'UP' -and $page.StatusCode -eq 200) { exit 0 } } catch { }; Start-Sleep -Seconds 1 } while ((Get-Date) -lt $deadline); exit 1"
if errorlevel 1 (
  echo [ERROR] Services did not become ready within 5 minutes. Check .run\*.log; run stop.cmd to close them.
  goto :failed
)
if not exist "%GUANDAN_RUN_DIR%\backend.pid" (
  echo [ERROR] Backend process marker is missing; check .run\*.log.
  goto :failed
)
if not exist "%GUANDAN_RUN_DIR%\frontend.pid" (
  echo [ERROR] Frontend process marker is missing; check .run\*.log.
  goto :failed
)

echo [OK] Guandan is ready at http://localhost:5173/
echo [INFO] To stop both services, double-click stop.cmd.
if /i "%~1"=="--no-browser" exit /b 0
start "" "http://localhost:5173/"
exit /b 0

:failed
echo.
pause
exit /b 1
