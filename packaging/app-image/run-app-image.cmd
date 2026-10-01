@echo off
rem ---------------------------------------------------------------------------
rem GTA Casino Fingerprint Solver - Stage 9A application-image launcher.
rem
rem Runs the packaged solver from THIS directory with the private Java runtime
rem bundled in runtime\. Maven, Git and a separately installed Java are not
rem required, and no system Java is ever consulted.
rem
rem Usage: run-app-image.cmd [LiveSolverMain arguments]
rem
rem With no arguments the launcher runs --list-monitors, which reads the display
rem layout and sends no input. Live input stays strictly opt-in and still
rem requires --watch --enable-input --target-exe NAME --abort-key NAME.
rem ---------------------------------------------------------------------------
setlocal

rem The solver resolves its runtime data relative to the working directory, so
rem the launcher must start in the application-image root, not the caller's cwd.
cd /d "%~dp0"
set "IMAGE_ROOT=%~dp0"
set "BUNDLED_JAVA=%IMAGE_ROOT%runtime\bin\java.exe"

if not exist "%BUNDLED_JAVA%" (
    echo error: the bundled private runtime is missing: "%BUNDLED_JAVA%"
    echo error: this application image is incomplete; rebuild it with scripts\build-windows-app-image.ps1
    exit /b 3
)

if "%~1"=="" (
    echo No arguments given: running the input-free monitor listing.
    echo Live input stays opt-in: pass --watch --enable-input --target-exe NAME --abort-key NAME.
    echo.
    set "SOLVER_ARGS=--list-monitors"
) else (
    set "SOLVER_ARGS=%*"
)

"%BUNDLED_JAVA%" -cp "%IMAGE_ROOT%app\*;%IMAGE_ROOT%app\lib\*" io.github.bohdankordon.casinofingerprint.app.LiveSolverMain %SOLVER_ARGS%
exit /b %ERRORLEVEL%
