@echo off
setlocal
rem Starts Midas. Any arguments (files, folders, URLs) are passed on.
rem   run_midas.bat [file]        installed Midas, else the last packaged build, else build from source
rem   run_midas.bat --dev [file]  always build and run from source (gradlew)

set "ROOT=%~dp0"
if /i "%~1"=="--dev" (shift & goto dev)

set "EXE="
if exist "%LOCALAPPDATA%\Midas\Midas.exe" set "EXE=%LOCALAPPDATA%\Midas\Midas.exe"
if not defined EXE if exist "%ROOT%midasApp-desktop\build\compose\binaries\main\app\Midas\Midas.exe" set "EXE=%ROOT%midasApp-desktop\build\compose\binaries\main\app\Midas\Midas.exe"
if not defined EXE goto dev

start "Midas" "%EXE%" %*
exit /b 0

:dev
echo Building and running Midas from source...
pushd "%ROOT%"
if "%~1"=="" (call gradlew.bat :midasApp-desktop:run) else (call gradlew.bat :midasApp-desktop:run --args="%~1")
set "CODE=%ERRORLEVEL%"
popd
if not "%CODE%"=="0" pause
exit /b %CODE%
