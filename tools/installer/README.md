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

## 代码签名（登记发布者）

未签名的安装包在 Windows 上会显示「未知发布者」，而且**很可能被 Defender 直接拦下**。
本项目 v1.1.5 的安装包就被判定为 `Trojan:Win32/Sabsik.FL.A!ml` —— 结尾的 `!ml` 表示这是
机器学习启发式的判断，属于**误报**（自解压包 + 内嵌运行时是很常见的触发特征）。

签名只签**安装包本身**（`setup.exe`）。jar 不需要签：Java 用 `-jar` 运行时不校验 jar 签名；
包内的 `javaw.exe` 是 Temurin 自己的、本来就有 Eclipse Adoptium 的签名。

### 先看清楚：哪种证书才真的有用

| 方案 | 费用 | 效果 |
| --- | --- | --- |
| **不签** | 免费 | SmartScreen 强拦截；本项目的实测是被 Defender 判为木马误报 |
| **自签名** | 免费 | 只在**信任该证书的机器**上才算发布者（自己的机器、或公司用组策略统一部署）。对公众不仅没用，还可能更糟 |
| **OV 证书**（DigiCert / Sectigo 等） | 约 $150–300/年 | 面向所有人显示发布者；SmartScreen 声誉**逐步积累**（新文件仍可能先被提示）。2023 年 6 月起私钥必须放在硬件令牌或云 HSM 里 |
| **SignPath Foundation**（开源项目） | **免费** | 走他们的受管流水线做 OV 级签名。开源项目可以申请：<https://signpath.io> |
| **Azure Artifact Signing**（原 Trusted Signing） | 约 $9.99/月 | 便宜且适合 CI，但**个人开发者目前只限美国与加拿大**（组织限美/加/欧/英），中国大陆的个人开发者用不了 |
| **EV 证书** | $400+/年 | **2024 年起不再即时免除 SmartScreen**，与 OV 走同样的声誉积累，为绕警告而买 EV 已不划算 |

来源：微软官方文档 [Code signing options for Windows app developers](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)
与 [SmartScreen reputation](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation)。

### 怎么签

```powershell
# 已有 .pfx（例如 CA 签发的证书导出文件）
.\tools\installer\sign-installer.ps1 -Setup dist\dstokencheck-1.1.5-setup.exe -Pfx mycert.pfx -Password 口令

# 证书已在证书库里（CurrentUser\My），按指纹或主题找
.\tools\installer\sign-installer.ps1 -Setup dist\dstokencheck-1.1.5-setup.exe -Thumbprint <指纹>

# 生成一张自签名证书试用（仅用于自己的机器 / 验证流程）
.\tools\installer\sign-installer.ps1 -Setup dist\dstokencheck-1.1.5-setup.exe -SelfSigned -PublisherName fcymz
```

打包时直接签，一步到位：

```powershell
.\tools\installer\build-installer.ps1 -Version 1.1.5 -SignPfx mycert.pfx -SignPassword 口令
```

脚本默认加 DigiCert 时间戳（`-TimestampUrl` 可换）；签名会改变文件字节，所以最终打印的
SHA256 是**签名之后**的。脚本最后会把 `Get-AuthenticodeSignature` 的结果打出来 —— 自签名会显示
`UnknownError`（"terminated in a root certificate which is not trusted"），这正是它只对受信机器有效的含义。

证书**不要提交进仓库**（`.gitignore` 已忽略 `*.pfx`）。CI 里签名的话，把 pfx 以 base64 存成
GitHub Secret，在 `release.yml` 的 installer 作业里解码后加上 `-SignPfx` 即可。

### 被 Defender 误报怎么办

签名能降低概率，但**误报的正规解法是向微软提交复审**（免费，通常一两天内处理）：

1. 打开 <https://www.microsoft.com/en-us/wdsi/filesubmission>
2. 选 **Software developer** → 填被误报的文件（用 Release 里的 `dstokencheck-1.1.5-setup.exe`）
3. 检测名填 `Trojan:Win32/Sabsik.FL.A!ml`，说明这是自己开发的开源 Java 桌面小工具的自解压安装包，
   包内是自己的程序与 Temurin 的 JRE
4. 提交后按邮件里的链接跟踪状态；确认是误报后微软会更新特征库

用户侧的临时办法：Defender 的隔离记录里选「允许」，或在「病毒和威胁防护 → 排除项」里加该文件。

## 已知限制

- 体积几乎全在 JRE 上（应用本体只有 5.7 MB）；如需更小，可以换用 `jlink` 精简过的运行时。
- 安装包目前**没有代码签名**：见上节，需要一张 OV 证书（或申请免费的 SignPath 开源签名）。
