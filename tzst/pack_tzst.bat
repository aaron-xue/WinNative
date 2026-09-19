@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM ============================================
REM  获取脚本所在目录名称作为 .tzst 归档文件名
REM ============================================
for %%I in ("%~dp0.") do set "ARCHIVE_NAME=%%~nxI"

REM ============================================
REM  收集当前目录下的所有文件和子目录排除脚本自身
REM ============================================
set "ITEMS="
for %%F in (*) do (
    if /I not "%%F"=="%~nx0" (
        set "ITEMS=!ITEMS! "%%F""
    )
)

REM 收集子目录
for /D %%D in (*) do (
    set "ITEMS=!ITEMS! "%%D""
)

REM ============================================
REM  执行 tzst 打包命令
REM ============================================
if defined ITEMS (
    echo [INFO] 归档文件: %ARCHIVE_NAME%.tzst
    echo [INFO] 打包内容:!ITEMS!
    echo.
    tzst a "%ARCHIVE_NAME%.tzst" !ITEMS! -l 15

    if exist "%ARCHIVE_NAME%.tzst" (
        echo.
        echo ============================================
        echo  压缩完成，请选择输出文件后缀:
        echo    1. .wcp
        echo    2. .game
        echo    0. .tzst ^(默认，不变更^)
        echo ============================================
        set /p "CHOICE=请输入数字 (0/1/2): "

        if "!CHOICE!"=="1" (
            ren "%ARCHIVE_NAME%.tzst" "%ARCHIVE_NAME%.wcp"
            echo [INFO] 已变更为: %ARCHIVE_NAME%.wcp
        ) else if "!CHOICE!"=="2" (
            ren "%ARCHIVE_NAME%.tzst" "%ARCHIVE_NAME%.game"
            echo [INFO] 已变更为: %ARCHIVE_NAME%.game
        ) else (
            echo [INFO] 保持原后缀: %ARCHIVE_NAME%.tzst
        )
    ) else (
        echo [ERROR] 打包失败，未生成归档文件。
        pause
        exit /b 1
    )
) else (
    echo [ERROR] 当前目录下没有找到任何文件或子目录。
    pause
    exit /b 1
)

endlocal