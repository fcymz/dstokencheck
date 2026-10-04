# 打包 Windows 安装包

一个双击就能装的 `dstokencheck-<版本>-setup.exe`，装完即可使用，**目标机器不需要预装 Java**
（安装包里自带 Temurin 8 的 JRE）。全部工具都来自 Windows 自身，不需要 NSIS、Inno Setup 之类。

## 生成

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\installer\build-installer.ps1 -Version 1.1.5
```

产物：`dist\dstokencheck-1.1.5-setup.exe`（约 47 MB），并打印 SHA256。

脚本做的事：

1. 用 JDK 8 编译并打包 `target\dstokencheck.jar`（沿用仓库一直用的手工打包方式）；
2. 组装 `payload`：jar + `build\runtime8`（Temurin 8 JRE）+ 图标 + 卸载脚本，压成一个 zip；
3. 用 Windows 自带的 C# 编译器 `csc.exe` 把 `setup.cs` 编译成 `winexe`，把上面那个 zip
   **作为资源内嵌**进去 —— 这就是最终的安装包本体，没有控制台窗口，并且带应用图标。

JRE 换版本只需替换 `build\runtime8`（`-Runtime` 参数可指定别的目录）。应用在 Temurin 17 上
自测同样全绿，但 Java 8 与 17 的字体渲染并不完全一致，所以默认跟随应用原本的目标版本 8。

## 安装包做了什么

- **界面**：一个安装窗口，默认装到 `%ProgramFiles%\dstokencheck`，可以自己选目录；
  两个勾选项：创建桌面快捷方式、装完立即启动。
- **权限**：装到 Program Files 会请求管理员（UAC）；装到用户能写的位置则完全不提权。
- **注册**：按是否提权自动决定写 HKLM 还是 HKCU 的卸载项，所以「设置 → 应用」里能卸载；
  开始菜单/桌面快捷方式同样按这个规则选择公共位置或当前用户位置。
- **快捷方式**：指向包内自带的 `runtime\bin\javaw.exe`，参数是安装目录里的 jar，使用自带图标。
- **卸载**：删除程序、快捷方式、卸载项，以及可能存在的开机自启注册表项；
  `%USERPROFILE%\.dstokencheck`（设置、背景图、预设）默认保留，卸载时会问一次。
- **日志**：静默安装时写 `%TEMP%\dstokencheck-setup.log`，排查问题看它。

## 测试

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\installer\test-installer.ps1 -Version 1.1.5
```

真的去跑那个 `.exe`（静默模式），然后检查文件、快捷方式、卸载项、**用自带运行时跑应用自测**，
最后再跑一次卸载确认清理干净。它装到临时目录、用临时的快捷方式名和注册表项，不影响已装的副本。

> 注意：某些受限环境（例如沙箱里的会话）不允许从工作目录启动的进程写 `%TEMP%`、
> 开始菜单或 HKCU，这两个检查在那里会失败；这不是安装包的问题，换到普通桌面会话即可。

## 已知限制

- 安装包**没有代码签名**，首次运行 Windows 可能提示「未知发布者」。要消掉需要一张代码签名证书。
- 体积几乎全在 JRE 上（应用本体只有 5.7 MB）；如需更小，可以换用 `jlink` 精简过的运行时。
