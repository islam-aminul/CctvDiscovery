@echo off
rem ---------------------------------------------------------------------------
rem  Runs inside the self-extracting archive, once IExpress has unpacked the
rem  payload into a temporary folder. Its job is to put the application
rem  somewhere permanent and start it.
rem
rem  The SFX invokes this as ".\sfx-bootstrap.cmd" rather than by bare name:
rem  a machine with NoDefaultCurrentDirectoryInExePath set does not search the
rem  working directory, and the bare form fails there with "not recognized".
rem
rem  The three placeholders in the block below are filled in by
rem  scripts/New-SfxPackage.ps1, which refuses to package the archive if any
rem  placeholder is left unreplaced.
rem ---------------------------------------------------------------------------
setlocal

set "APP_NAME=@APP_NAME@"
set "APP_VERSION=@APP_VERSION@"
set "APP_EXE=@APP_EXE@"
set "APP_HOME=%LOCALAPPDATA%\Programs\%APP_NAME%\%APP_VERSION%"
set "STAGE=%APP_HOME%.unpacking"

title %APP_NAME% %APP_VERSION%
echo.
echo   %APP_NAME% %APP_VERSION%
echo   Unpacking to %APP_HOME%
echo   This takes a moment the first time; the window closes by itself.
echo.

rem Unpack alongside the destination and swap it in at the end, so that an
rem interrupted run never leaves a half-written copy that looks complete.
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%" 2>nul
if not exist "%STAGE%" goto no_folder

call :unpack
if errorlevel 1 goto unpack_failed

if exist "%APP_HOME%" rmdir /s /q "%APP_HOME%"
move "%STAGE%" "%APP_HOME%" >nul
if errorlevel 1 goto move_failed
if not exist "%APP_HOME%\%APP_EXE%" goto missing_exe

rem /D sets the working directory: this script runs from the archive's own
rem unpack folder, and the application must not inherit that.
echo   Starting %APP_EXE%
start "" /D "%APP_HOME%" "%APP_HOME%\%APP_EXE%"
endlocal
exit /b 0

:unpack
rem Windows 10 1803 and later ship bsdtar, which reads zip archives and is
rem markedly faster than Expand-Archive on a payload this size. PowerShell is
rem the fallback for anything older or for a trimmed installation.
where tar >nul 2>&1
if errorlevel 1 goto unpack_powershell
tar -x -f "%~dp0payload.zip" -C "%STAGE%"
if not errorlevel 1 exit /b 0
echo   tar could not read the payload; trying PowerShell instead.
:unpack_powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('%~dp0payload.zip', '%STAGE%')"
exit /b %errorlevel%

:no_folder
echo   Could not create "%STAGE%".
echo   Check that you can write to %LOCALAPPDATA%.
goto failed

:unpack_failed
echo   Unpacking failed. The archive may be damaged or the disk full.
goto failed

:move_failed
echo   Could not replace "%APP_HOME%".
echo   Close any running copy of %APP_NAME% and run this again.
goto failed

:missing_exe
echo   %APP_EXE% is missing from the unpacked files.
goto failed

:failed
echo.
pause
endlocal
exit /b 1
