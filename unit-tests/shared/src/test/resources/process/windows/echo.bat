@echo off
setlocal DisableDelayedExpansion

for /F "tokens=*" %%a in ('findstr /n $') do (
  set "line=%%a"
  setlocal EnableDelayedExpansion
  set "line=!line:*:=!"
  if /i "!line:~0,4!" equ "quit" (
    exit /B 0
  )
  echo(!line!
  endlocal
)


