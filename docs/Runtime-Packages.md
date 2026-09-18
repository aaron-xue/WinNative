### txz、tzst、wcp

- txz对应tar.xz格式
  tzst对应tar.zst格式
  wcp文件为txz格式的压缩包
- **解压方式**
  重命名文件为对应格式的后缀
  使用解压软件如：7Z或360压缩或
  使用powershell或git bash命令解压
  tar.xz
  ```
  tar -xvf example.tar.xz -C ./example
  ```
  tar.zst
  ```
  tzst x example.tzst -o usr/
  ```
- **压缩方式**
  tar.xz
  ```
  tar -cJf dxvk-1.12-sarek-arm64ec-dyasync.tar.xz -v system32 syswow64 profile.json
  tar -czf example.tar.gz ./example
  tar -cjf example.tar.bz2 ./example
  ```
  tar.zst
  ```
  tar -I zstd -cf example.tar.zst ./usr
  //tzst工具
  tzst a 龙珠大冒险.tzst cover1.jpg manifests.json
  ```
- **tzst工具** Download standalone executables from the [Releases](https://github.com/aaron-xue/tzst/releases) section.

---

### 📦 Runtime Packages

| Type | 📝 |
|-|-|
| [**Visual C++ x64**](https://aka.ms/vs/17/release/vc_redist.x64.exe) | 2015–2022 Redistributable |
| [**Visual C++ x86**](https://aka.ms/vs/17/release/vc_redist.x86.exe) | 2015–2022 Redistributable |
| [**Visual C++ ARM64**](https://aka.ms/vs/17/release/vc_redist.arm64.exe) | 2015–2022 Redistributable |
| [**Wine-Mono**](https://github.com/wine-mono/wine-mono/releases) | .NET runtime for Wine (*.msi) |
| [**Wine-Gecko**](https://dl.winehq.org/wine/wine-gecko/) | HTML engine for Wine (*.msi) |
| [**XNA Framework**](https://download.microsoft.com/download/a/c/2/ac2c903b-e6e8-42c2-9fd7-bebac362a930/xnafx40_redist.msi) | Old indie games runtime |
| [**DirectX (June 2010)**](https://download.microsoft.com/download/8/4/a/84a35bf1-dafe-4ae8-82af-ad2ae20b6b14/directx_Jun2010_redist.exe) | Install ONLY if missing DLL (d3dx9_43.dll...) |
| [**PhysX Legacy**](https://www.nvidia.com/content/DriverDownload-March2009/confirmation.php?url=/Windows/9.13.0604/PhysX-9.13.0604-SystemSoftware-Legacy.msi&lang=us&type=Other) | Install ONLY if a old game requests PhysX DLL |

- **无法安装msi的情况** 新建run.bat,将msi程序放到bat脚本自身的所在目录，然后运行run.bat
    ```
    @echo off
    echo installing wine mono gecko.....
    cd /D "%~dp0"
    for /r "." %%a in (*.msi) do start /wait "" "%%~fa"
    echo done
    ```
