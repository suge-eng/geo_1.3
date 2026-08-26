@echo off
REM Build all 5 microservice executable jars. Run from this folder.
REM This script auto-discovers JAVA_HOME so you don't need to set it manually.
setlocal

REM ================================
REM Auto-detect JAVA_HOME (fallback chain)
REM ================================
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :jdk_ok

REM Try 1: registry - Eclipse Temurin / Adoptium / Oracle JDK 11+
set "JDK_REG="
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\Eclipse Adoptium\JDK" /s 2^>nul ^| findstr /I /C:"Path" /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\JavaSoft\JDK" /s 2^>nul ^| findstr /I /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\JavaSoft\Java Development Kit" /s 2^>nul ^| findstr /I /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)
for /f "tokens=2*" %%A in ('reg query "HKLM\SOFTWARE\Microsoft\JDK" /s 2^>nul ^| findstr /I /C:"JavaHome"') do if not "%%~B"=="" if exist "%%~B\bin\java.exe" (set "JAVA_HOME=%%~B" & goto :jdk_ok)

REM Try 2: common install directories (latest JDK-17 preferred)
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-17*" "C:\Program Files\Eclipse Adoptium\jdk-*" "C:\Program Files\Java\jdk-17*" "C:\Program Files\Java\jdk-*" "C:\Program Files\Microsoft\jdk-17*" "C:\Program Files\Microsoft\jdk-*" "C:\Program Files\Amazon Corretto\jdk-17*" "C:\Program Files\Amazon Corretto\jdk-*" "C:\Program Files\Zulu\zulu-17*" "C:\Program Files\Zulu\zulu-*") do (
    if exist "%%~D\bin\java.exe" (set "JAVA_HOME=%%~D" & goto :jdk_ok)
)

REM Try 3: where java, strip bin\java.exe
for /f "delims=" %%P in ('where java 2^>nul') do (
    for %%I in ("%%~dpP..") do (
        if exist "%%~fI\bin\java.exe" (set "JAVA_HOME=%%~fI" & goto :jdk_ok)
    )
)

echo [ERROR] JAVA_HOME is not set, and no JDK installation could be found automatically.
echo         Please install a JDK 17+ (Eclipse Temurin recommended) or set JAVA_HOME manually.
pause
exit /b 1

:jdk_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [INFO] Using JAVA_HOME=%JAVA_HOME%
echo [INFO] java -version:
"%JAVA_HOME%\bin\java.exe" -version 2>&1

set "BD=%~dp0"
cd /d "%BD%"

echo.
echo === Step 1/2: Install geo-common to local Maven repo ===
call mvn -pl geo-common -am -DskipTests install -q
if errorlevel 1 (echo BUILD FAIL: geo-common install & pause & exit /b 1)

echo.
echo === Step 2/2: Package each service ===
for %%S in (geo-task-service geo-analysis-service geo-file-service geo-rpa-service geo-gateway) do (
    echo Packaging %%S ...
    call mvn -pl %%S -am -DskipTests package -q
    if errorlevel 1 (echo BUILD FAIL: %%S & pause & exit /b 1)
    for %%A in ("%%S\target\%%S.jar") do echo   OK  %%S.jar  [%%~zA bytes]
)
echo.
echo === All 5 jars built successfully ===
endlocal
exit /b 0
