@echo off

REM Sets up tvm API headers
REM Ensure TVM_HOME environment variable points to TVM library root directory

SETLOCAL

SET script_dir=%~dp0
CALL javah -o "%script_dir%ml_dmlc_tvm_native_c_api.h" -cp "%TVM_HOME%\jvm\core\target\*" ml.dmlc.tvm.LibInfo || goto :error
XCOPY /q/y %TVM_HOME%"\jvm\native\src\main\native\ml_dmlc_tvm_native_c_api.cc" "%script_dir%">NUL || goto :error
XCOPY /q/y %TVM_HOME%"\jvm\native\src\main\native\jni_helper_func.h" "%script_dir%">NUL || goto :error

:error
exit /b %errorlevel%

ENDLOCAL
