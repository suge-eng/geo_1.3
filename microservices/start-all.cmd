@echo off
REM ===============================
REM Start all 5 microservices (4 middleware containers running first)
REM Ports:
REM   8081 geo-task-service
REM   8082 geo-analysis-service
REM   8083 geo-file-service
REM   8084 geo-rpa-service
REM   8080 geo-gateway  (ENTRY POINT: http://localhost:8080)
REM ===============================
setlocal

REM ------ JAVA_HOME auto-detect ------
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :jdk_ok
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\Eclipse Adoptium\JDK" /s 2^>nul ^| findstr /I /C:"Path" /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\JavaSoft\JDK" /s 2^>nul ^| findstr /I /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\JavaSoft\Java Development Kit" /s 2^>nul ^| findstr /I /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\Microsoft\JDK" /s 2^>nul ^| findstr /I /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-17*" "C:\Program Files\Eclipse Adoptium\jdk-*" "C:\Program Files\Java\jdk-17*" "C:\Program Files\Java\jdk-*" "C:\Program Files\Microsoft\jdk-17*" "C:\Program Files\Microsoft\jdk-*" "C:\Program Files\Amazon Corretto\jdk-17*" "C:\Program Files\Amazon Corretto\jdk-*" "C:\Program Files\Zulu\zulu-17*" "C:\Program Files\Zulu\zulu-*") do (if exist "%%~D\bin\java.exe" (set "JAVA_HOME=%%~D" & goto :jdk_ok))
for /f "delims=" %%P in ('where java 2^>nul') do for %%I in ("%%~dpP..") do if exist "%%~fI\bin\java.exe" (set "JAVA_HOME=%%~fI" & goto :jdk_ok)
echo [ERROR] JDK 17+ not found. Please install or set JAVA_HOME.
pause
exit /b 1

:jdk_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [INFO] Using JAVA_HOME=%JAVA_HOME%

set "BD=%~dp0"
cd /d "%BD%"

echo.
echo === Build jars first ===
call build-jars.cmd
if errorlevel 1 (echo Build FAILED & pause & exit /b 1)

echo.
echo === Start 5 microservices (each in a MINIMIZED console window titled geo-*) ===
REM NOTE: We pushd into each service's target folder so the -jar argument is JUST the
REM simple filename with NO embedded spaces / Chinese chars - zero ambiguity.

echo [1/5] geo-task-service     port 8081  heap 1024m
pushd "%BD%geo-task-service\target"
start "geo-task-service"     /MIN "%JAVA_HOME%\bin\java.exe" -Xmx1024m -Dfile.encoding=UTF-8 -jar geo-task-service.jar
popd
timeout /t 3 /nobreak >nul

echo [2/5] geo-analysis-service port 8082  heap 1024m
pushd "%BD%geo-analysis-service\target"
start "geo-analysis-service" /MIN "%JAVA_HOME%\bin\java.exe" -Xmx1024m -Dfile.encoding=UTF-8 -jar geo-analysis-service.jar
popd
timeout /t 3 /nobreak >nul

echo [3/5] geo-file-service     port 8083  heap 256m
pushd "%BD%geo-file-service\target"
start "geo-file-service"     /MIN "%JAVA_HOME%\bin\java.exe" -Xmx256m  -Dfile.encoding=UTF-8 -jar geo-file-service.jar
popd
timeout /t 3 /nobreak >nul

echo [4/5] geo-rpa-service      port 8084  heap 512m
pushd "%BD%geo-rpa-service\target"
start "geo-rpa-service"      /MIN "%JAVA_HOME%\bin\java.exe" -Xmx512m  -Dfile.encoding=UTF-8 -jar geo-rpa-service.jar
popd
timeout /t 3 /nobreak >nul

echo [5/5] geo-gateway          port 8080  heap 512m   (ENTRY POINT)
pushd "%BD%geo-gateway\target"
start "geo-gateway"          /MIN "%JAVA_HOME%\bin\java.exe" -Xmx512m  -Dfile.encoding=UTF-8 -jar geo-gateway.jar
popd
timeout /t 10 /nobreak >nul

echo.
echo =========================================================
echo   Microservices started. Browser: http://localhost:8080
echo =========================================================
echo   Wait ~30-60 seconds for all Spring apps to finish booting,
echo   then refresh your browser if the page does not load yet.
echo.
echo   Verify listening ports:
echo     netstat -ano ^| findstr LISTENING ^| findstr :808
echo   (You should see 5 ports: 8080 8081 8082 8083 8084)
echo.
echo   Stop all services:  stop-all.cmd
echo.
pause
endlocal
exit /b 0
