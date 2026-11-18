@echo off
rem Construit l'application avec le JDK embarque : compilation, tests, puis distribution dans target\app.
rem   build.bat               construction complete (tests compris)
rem   build.bat -DskipTests   tout argument est transmis a Maven
setlocal EnableExtensions

set "ROOT=%~dp0"
call "%ROOT%scripts\jdk.bat" || exit /b 1

rem pushd attribue une lettre de lecteur quand le projet est sur un partage reseau (\\wsl.localhost\...),
rem car cmd.exe n'accepte pas un chemin UNC comme dossier courant.
pushd "%ROOT%" || exit /b 1
call mvnw.cmd --batch-mode --no-transfer-progress clean package %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
