@echo off
REM Stop all microservices cleanly. Works because each service was started with:
REM   start "geo-*" /MIN java.exe  ...
REM which creates a console window with title geo-* and hosts the java.exe.
REM /T kills the entire process tree (both the console host and java.exe).
echo === Stopping geo-* microservice windows (kill tree /T) ===
taskkill /F /T /FI "WINDOWTITLE eq geo-task-service"     2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-analysis-service" 2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-file-service"     2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-rpa-service"      2>nul
taskkill /F /T /FI "WINDOWTITLE eq geo-gateway"          2>nul

REM Safety net: generic window title wildcard too
taskkill /F /T /FI "WINDOWTITLE eq geo-*" 2>nul

timeout /t 2 /nobreak >nul
echo.
echo === Remaining Java processes ===
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
