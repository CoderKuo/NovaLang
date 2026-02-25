@echo off
cd /d E:\novalang
if "%~1"=="" (
    echo Usage: run_test.bat ^<fully.qualified.TestClass^>
    echo Example: run_test.bat nova.runtime.interpreter.reflect.ReflectApiTest
    exit /b 1
)
call E:\novalang\gradlew.bat :nova-runtime:test --tests "%~1" --console=plain --no-daemon 2>&1
echo EXIT_CODE=%ERRORLEVEL%
