@echo off
rem Stop all dev services started by start_dev.bat.
setlocal
set "ROOT=%~dp0"

echo Stopping backend/frontend...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /C:":8090 " ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /C:":5174 " ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

echo Stopping PostgreSQL...
"%ROOT%.runtime\pgsql\bin\pg_ctl.exe" -D "%ROOT%.runtime\pgdata" stop -m fast

echo Done.
endlocal
