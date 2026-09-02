@echo off
chcp 65001 >nul
REM ---------------------------------------------------------------------
REM Stop all microservices.
REM How it works: start-all.cmd launches each service with:
REM   start "geo-*" /MIN java ...
REM which creates a console window titled geo-* hosting the java process.
REM This script uses taskkill with window title filter to kill the entire
REM process tree (/F force, /T include child processes), cleanly stopping
REM each service.
REM ---------------------------------------------------------------------
echo === Stopping geo-* microservice windows (kill tree /T) ===
REM Kill each service by exact window title match
taskkill /F /T /FI "WINDOWTITLE eq geo-task-service"     2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-analysis-service" 2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-file-service"     2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-rpa-service"      2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-gateway"          2>nul

REM Fallback: kill anything with window title starting with geo-*
taskkill /F /T /FI "WINDOWTITLE eq geo-*" 2>nul

timeout /t 2 /nobreak >nul
echo.
echo === Remaining Java processes ===
REM List remaining java processes so you can confirm everything stopped
tasklist /FI "IMAGENAME eq javaw.exe" 2>nul
tasklist /FI "IMAGENAME eq java.exe"  2>nul
echo.
echo If any remaining java.exe above are your microservices (and you have no other
echo Java apps running), they can be force-killed with:
echo     taskkill /F /IM java.exe
echo (Only run this if you are OK with terminating ALL Java processes on the machine.)
echo.
pause
exit /b 0