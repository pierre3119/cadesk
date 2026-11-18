@echo off
rem Lance CaDesk sous Windows avec le JDK embarque dans le projet.
rem Au premier lancement, l'application est construite automatiquement (build.bat pour reconstruire ensuite).
rem   start.bat             lance l'application (sans fenetre de console)
rem   start.bat --console   la lance dans cette console, pour voir les messages d'erreur eventuels
setlocal EnableExtensions

set "ROOT=%~dp0"
call "%ROOT%scripts\jdk.bat" || exit /b 1

set "APP=%ROOT%target\app"
if not exist "%APP%\cadesk.jar" (
  echo Premiere utilisation : construction de l'application...
  call "%ROOT%build.bat" -DskipTests || exit /b 1
)

set JAVA_ARGS=--module-path "%APP%\cadesk.jar;%APP%\lib;%APP%\lib\windows" --add-modules copilot.sdk.runtime --enable-native-access=javafx.graphics,org.xerial.sqlitejdbc "-Dcadesk.home=%ROOT%." -m cadesk/io.github.pierre3119.cadesk.ui.Main

if /i "%~1"=="--console" (
  "%JAVA_HOME%\bin\java.exe" %JAVA_ARGS%
  exit /b %ERRORLEVEL%
)
start "CaDesk" "%JAVA_HOME%\bin\javaw.exe" %JAVA_ARGS%
endlocal
