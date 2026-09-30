# DeepSeek 余额小窗口

一款 Windows 桌面小工具：使用 DeepSeek API Key 读取账户余额，以无边框小窗口常驻桌面，
定时自动刷新。

![小窗口截图](tools/demo-window.png)

## 目录

- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [运行方式](#运行方式)
- [使用方法](#使用方法)
- [配置与数据](#配置与数据)
- [命令行参数](#命令行参数)
- [接口](#接口)
- [项目结构](#项目结构)
- [已知限制](#已知限制)

## 功能特性

| 功能 | 说明 |
| --- | --- |
| API Key 登录 | 使用官方接口 `GET /user/balance`；登录前先联网校验，Key 错误立即提示 |
| 记住我 | 勾选后 API Key 加密保存到本机，之后每次启动直接显示余额 |
| 退出登录 | 右键菜单 →「退出登录」，清除本机保存的 Key 并返回登录界面 |
| 余额显示 | 主币种余额以大字号显示，其他币种分行列出 |
| 自动刷新 | 默认 60 秒，可选 5 / 10 / 30 / 60 / 300 秒 |
| 字体大小 | 70%–250% 无级调节，整个界面等比缩放；也支持 `Ctrl + 滚轮` |
| 无边框窗口 | 无系统标题栏，深色圆角卡片样式 |
| 可拖动 | 在窗口空白处按住左键拖动 |
| 可缩放 | 拖拽四条边或四个角改变大小，最小尺寸随字体缩放；右键菜单提供预设尺寸 |
| 窗口置顶 | 默认开启，点击任务栏后仍保持在任务栏之上 |
| 开机自启 | 登录 Windows 后自动启动，写入当前用户注册表项，无需管理员权限 |
| 位置记忆 | 退出时保存窗口位置与尺寸 |
| 启动日志 | 记录每次启动的关键状态，便于排查开机自启是否生效 |

## 环境要求

| 用途 | 要求 |
| --- | --- |
| 运行（下载预编译版本） | Java 8 或更高版本（[下载](https://adoptium.net/)） |
| 运行（从源码构建） | 同上 |
| 构建 | JDK 8 或更高版本、Maven 3.x |
| 系统 | Windows（开机自启依赖注册表，其余功能跨平台可用） |

编译目标为 Java 8（class 版本 52），更高版本的 JRE 亦可运行。

## 运行方式

### 方式一：下载预编译版本

无需 JDK 与 Maven。

1. 打开 [Releases 页面](https://github.com/fcymz/dstokencheck/releases)，下载最新版的
   `dstokencheck.jar` 与 `run.bat`。
2. 将两个文件放入**同一个文件夹**（例如 `D:\dstokencheck\`）。
3. 双击 `run.bat`。

### 方式二：从源码构建

```bat
git clone https://github.com/fcymz/dstokencheck.git
cd dstokencheck
mvn clean package
run.bat
```

也可直接运行：

```bat
java -jar target\dstokencheck.jar
```

`run.bat` 按以下顺序查找程序，均不存在时打印提示并暂停：

1. `target\dstokencheck.jar`（本地构建产物）
2. 与 `run.bat` 同目录的 `dstokencheck.jar`（从 Releases 下载）

## 使用方法

### 首次登录

1. 启动后弹出登录窗口，粘贴 API Key。可在
   [platform.deepseek.com/api_keys](https://platform.deepseek.com/api_keys) 创建，
   点击窗口内的链接可直接打开该页面。
2. 如需免去后续输入，勾选「记住我」，再点击「登录」。
3. 程序会先请求一次余额确认 Key 有效，验证通过后才保存并显示余额。

### 窗口操作

| 操作 | 方式 |
| --- | --- |
| 移动窗口 | 在窗口空白处按住左键拖动 |
| 调整大小 | 拖拽窗口四条边或四个角（边缘 5 像素内按下即进入缩放） |
| 打开菜单 | 在窗口上点击右键 |
| 置顶开关 | 点击右上角图钉图标，或右键菜单 →「窗口置顶」 |
| 立即刷新 | 点击右上角刷新图标，或右键菜单 →「立即刷新」 |
| 隐藏窗口 | 点击右上角关闭图标 |

右键菜单包含：立即刷新、退出登录、开机自启、窗口置顶、窗口尺寸、字体大小、刷新间隔、退出。

### 字体大小

三种方式效果相同，均对**整个界面等比缩放**（而非只放大金额数字）：

- `Ctrl` + 鼠标滚轮：每次调整 5%
- 右键菜单 →「字体大小」：选择 80% / 100% / 125% / 150% / 175% / 200% / 250%，或「更大 / 更小」
- 右键菜单 →「字体大小」→「自定义…」：滑块在 70%–250% 之间无级调节，边拖边预览

调整时窗口按同比例自动缩放，避免文字被裁切；右上角图标同步缩放（最小 16 像素）。
窗口最小尺寸随字体变化：100% 时为 240×130，200% 时为 480×260。调整后仍可手动拖拽改变形状。

### 刷新间隔

右键菜单 →「每 N 秒刷新」，可选 5 / 10 / 30 / 60 / 300 秒，最低 5 秒。

### 开机自启

右键菜单勾选「开机自启」即可开启，取消勾选即关闭。

- **须以 `run.bat` 或 `java -jar dstokencheck.jar` 方式启动后才能设置。**
  在 IDE 中直接运行的是 `target\classes` 目录而非 jar，程序无法确定需注册的路径；
  此时点击菜单项会弹出说明，不会写入无效项。
- 开启时会把 jar 复制到稳定位置 `%LOCALAPPDATA%\dstokencheck\dstokencheck.jar`，
  注册表项指向该副本。副本过期时会在下次启动自动刷新。
- 注册表项位置：

  ```
  HKCU\Software\Microsoft\Windows\CurrentVersion\Run\DeepSeekBalanceBoard
  ```

- 随登录启动的实例若**没有可用的已保存 Key**，会静默退出而不弹出登录窗口。
  手动启动不受此影响。

**确认是否生效**：程序每次启动都会向 `%USERPROFILE%\.dstokencheck\startup.log`
追加记录。开机后查看该文件，出现本次开机时间的记录即表示已成功启动。

也可随时用命令核对：

```bat
java -jar target\dstokencheck.jar --autostart status
```

**关闭方式**（任选其一）：

- 右键菜单取消勾选「开机自启」
- 执行 `java -jar target\dstokencheck.jar --autostart off`
- 在注册表编辑器中删除上述项

### 退出登录

右键菜单 →「退出登录」，将清除本机保存的 API Key 并返回登录界面。
也可手动删除配置文件中的 `apiKeyProtected` 与 `rememberApiKey` 两行，或直接删除配置文件。

## 配置与数据

### 配置文件

位置：`%USERPROFILE%\.dstokencheck\config.properties`

```properties
apiKeyProtected=dpapi:...   # 加密后的 API Key，本机唯一的保存位置
rememberApiKey=true         # 是否记住
refreshSeconds=60           # 刷新间隔，最低 5 秒
fontScale=1.0               # 字体缩放，0.7 ~ 2.5
alwaysOnTop=true            # 窗口置顶
opacity=1.0                 # 窗口不透明度
window.x / window.y / window.width / window.height   # 窗口位置与尺寸
```

同目录下的 `startup.log` 为启动日志，不含任何凭据。

### API Key 的加密

API Key 不会以明文写入磁盘。加密方案按平台自动选择，存储值带前缀以标识方案：

| 前缀 | 方案 | 适用场景 | 说明 |
| --- | --- | --- | --- |
| `dpapi:` | Windows DPAPI（`CryptProtectData`） | Windows（默认） | 密钥由 Windows 托管，绑定当前用户与本机；更换用户或复制到其他设备均无法解密 |
| `aesgcm:` | AES-256-GCM，密钥由 PBKDF2 从本机信息派生 | 非 Windows，或 DPAPI 调用失败时 | 可防止明文读取与跨设备复制，但属混淆级别 |

其他说明：

- Key 在保存前会先联网校验，避免存入无效值。
- 解密失败（更换用户或设备、文件损坏、内容被篡改）时返回 `null`，程序会清除无效配置
  并重新请求输入，不会反复启动失败。
- 未勾选「记住我」时，Key 仅存在于内存中，退出即失效。

**安全边界**：上述两种方案均无法防御已能以当前用户身份在本机执行代码的攻击者。
如需更强保护，请勿勾选「记住我」，每次启动手动输入。

## 命令行参数

```bat
:: 在终端中验证 Key 并打印余额
java -jar target\dstokencheck.jar --diag <API_KEY>

:: 查看 / 开启 / 关闭开机自启（与右键菜单等价）
java -jar target\dstokencheck.jar --autostart status
java -jar target\dstokencheck.jar --autostart on
java -jar target\dstokencheck.jar --autostart off

:: 自测：拖动、缩放、最小尺寸、菜单项、字体缩放、刷新下限等
java -jar target\dstokencheck.jar --selftest

:: 将窗口离屏渲染为 PNG
java -jar target\dstokencheck.jar --shot <输出目录>

:: 演示模式：不联网，使用示例数据预览界面
java -jar target\dstokencheck.jar --demo

:: 随 Windows 登录启动（由注册表项自动传入，一般无需手动使用）
java -jar target\dstokencheck.jar --at-logon
```

`--selftest` 全部通过时返回码为 0。

## 接口

本工具仅使用一个官方公开接口：

```
GET https://api.deepseek.com/user/balance
Authorization: Bearer <API_KEY>
```

响应示例：

```json
{
  "is_available": true,
  "balance_infos": [
    {
      "currency": "CNY",
      "total_balance": "87.65",
      "granted_balance": "0.00",
      "topped_up_balance": "87.65"
    }
  ]
}
```

参考文档：[Get User Balance | DeepSeek API Docs](https://api-docs.deepseek.com/api/get-user-balance/)

## 项目结构

```
src/main/java/com/ruoyi/dstokencheck/
├── Main.java                     入口：界面编排与命令行参数分发
├── config/AppConfig.java         配置读写
├── security/SecretStore.java     API Key 加密（DPAPI 优先，AES-GCM 兜底）
├── autostart/AutoStart.java      开机自启：读写 HKCU Run 注册表项
├── model/
│   ├── Wallet.java               单个币种钱包
│   └── BalanceSnapshot.java      一次刷新的完整快照与币种汇总
├── net/
│   ├── Http.java                 基于 HttpURLConnection 的 HTTP 封装
│   └── DeepSeekClient.java       余额接口调用与 Key 掩码显示
└── ui/
    ├── Theme.java                颜色与字体（自动选择中文字体）
    ├── IconButton.java           自绘矢量图标按钮
    ├── ApiKeyDialog.java         无边框登录窗口
    └── BalanceBoard.java         无边框主窗口（自实现拖动、缩放、置顶、退出登录）

tools/                            开发期工具，不影响程序运行
├── SecretProbe.java              验证加密与「记住我」存取
├── capture-window.ps1            真实截屏指定窗口，用于验证渲染
├── window-timeline.ps1           按时间轴输出窗口出现顺序
├── verify-topmost.ps1            抬起任务栏并校验窗口 z-order
├── scan-chunks.ps1               扫描平台前端资源以定位接口
├── apikey-window.png             登录窗口渲染效果
└── demo-window.png               小窗口渲染效果
```

开发期工具不会读写真实配置：`capture-window.ps1` 与 `window-timeline.ps1` 会将
`user.home` 指向 `%TEMP%` 下的临时目录后再启动程序。`SecretProbe.java` 读写的是
`user.home`，运行时请自行加上 `-Duser.home=%TEMP%\probe` 隔离。

## 已知限制

- 跨币种余额不做汇率换算，仅汇总同一币种，避免产生无意义的总数。
- 无边框窗口没有系统标题栏，拖动与缩放均由程序自行实现，因此在窗口边缘 5 像素内按下即进入缩放。
- 仅缓存余额数据，不保存余额以外的任何账户信息。
