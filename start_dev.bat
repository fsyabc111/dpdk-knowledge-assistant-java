@echo off
rem One-command dev start for Windows (portable PostgreSQL + backend + frontend).
setlocal
set "ROOT=%~dp0"
if not exist "%ROOT%.logs" mkdir "%ROOT%.logs"
if not exist "%ROOT%.runtime\logs" mkdir "%ROOT%.runtime\logs"

echo [1/3] Starting PostgreSQL...
"%ROOT%.runtime\pgsql\bin\pg_ctl.exe" -D "%ROOT%.runtime\pgdata" -l "%ROOT%.runtime\logs\pg.log" -o "-p 5432 -c listen_addresses=127.0.0.1" -w start

echo [2/3] Starting backend on http://127.0.0.1:8090 ...
start "assistant-backend" /MIN /D "%ROOT%backend" cmd /c ""%ROOT%backend\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8090 >> "%ROOT%.logs\backend.log" 2>&1"

echo [3/3] Starting frontend on http://127.0.0.1:5174 ...
start "assistant-frontend" /MIN /D "%ROOT%frontend" cmd /c "npm run dev >> "%ROOT%.logs\frontend.log" 2>&1"

echo.
echo Backend:  http://127.0.0.1:8090
echo Frontend: http://127.0.0.1:5174
echo Logs:     %ROOT%.logs
echo Run stop_dev.bat to stop all services.
endlocal
