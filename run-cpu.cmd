@echo off
REM Runs the CPU solver from the repository root (pieces.csv is read by relative path).
REM
REM Prerequisites, once:
REM   mvn clean package
REM   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt

cd /d "%~dp0"

if not exist "cp.txt" (
    echo ERROR: cp.txt not found. Run: mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
    exit /b 1
)
if not exist "target\classes" (
    echo ERROR: target\classes not found. Run: mvn clean package
    exit /b 1
)

for /f "usebackq delims=" %%A in ("cp.txt") do set CPFILE=%%A
java -cp "target\classes;%CPFILE%" dk.puzzle.blackwood.BlackwoodSolver %*
