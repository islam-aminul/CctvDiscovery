@echo off
rem ---------------------------------------------------------------------------
rem  CCTV Discovery, portable: for computers where unknown programs may not run.
rem
rem  Starts the bundled Java runtime directly, so no program of ours runs: the
rem  runtime's javaw.exe is signed by its publisher (Eclipse Adoptium), and this
rem  script is all that starts it. Extract the whole zip first, keeping its
rem  folders; this script expects runtime and CctvDiscovery.jar beside it.
rem
rem  CctvDiscovery.cmd           starts the program and closes this window
rem  CctvDiscovery.cmd console   keeps a console open with the program's output,
rem                              for when it does not start
rem
rem  Labels and goto rather than ( ) blocks: a block breaks on a folder whose
rem  path holds a bracket, such as "Program Files (x86)".
rem ---------------------------------------------------------------------------
setlocal
set "HERE=%~dp0"
set "BIN=%HERE%runtime\bin"
set "JAR=%HERE%CctvDiscovery.jar"
set "MAIN=com.cctv.discovery.Main"
set "OPTS=--enable-preview --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8"

if not exist "%BIN%\javaw.exe" goto missing
if not exist "%JAR%" goto missing

rem The program finds its files relative to its own folder.
cd /d "%HERE%"

if /i "%~1"=="console" goto console
start "" "%BIN%\javaw.exe" %OPTS% -cp "%JAR%" %MAIN%
exit /b 0

:console
"%BIN%\java.exe" %OPTS% -cp "%JAR%" %MAIN%
echo.
echo CCTV Discovery exited with code %ERRORLEVEL%.
pause
exit /b %ERRORLEVEL%

:missing
echo.
echo   CCTV Discovery cannot start: the runtime folder or CctvDiscovery.jar
echo   is missing from "%HERE%"
echo.
echo   Extract the whole zip to a folder, keeping its folders, then run
echo   CctvDiscovery.cmd from there. Running it from inside the zip does not work.
echo.
pause
exit /b 1
