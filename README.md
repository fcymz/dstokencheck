# DeepSeek 余额小窗口 (dstokencheck)

一个 Java 桌面小挂件：用 **DeepSeek API Key** 登录，定时读取账户余额，
并以**无边框、可拖动、可自由缩放**的小窗口常驻桌面。

![小窗口截图](tools/demo-window.png)

---

## 功能

| 功能 | 说明 |
| --- | --- |
| API Key 登录 | 走官方接口 `GET /user/balance`，登录时先联网验证，填错立刻提示 |
| 记住我 | 勾选后 API Key **加密**存到本机，之后每次打开直接显示余额，不再询问 |
| 退出登录 | 右键菜单 →「退出登录」，清除本机保存的 Key 并回到登录界面 |
| 余额显示 | 大字号显示主币种余额，其他币种分行列出 |
| 自动刷新 | 默认 60 秒，右键菜单可选 **5** / 10 / 30 / 60 / 300 秒（最低 5 秒） |
| 字体大小 | 右键菜单「字体大小」可在 **70%–250%** 之间自由调节；也可 `Ctrl + 滚轮` 快速缩放 |
| 无边框窗口 | 无系统标题栏，深色圆角卡片样式 |
| 可拖动 | 在窗口任意空白处按住左键拖动 |
| 可缩放 | 拖拽窗口四条边或四个角即可改变大小（最小尺寸随字体缩放），右键菜单也有预设尺寸 |
| 窗口置顶 | 默认为置顶；即使点任务栏，小窗口也会自动回到任务栏之上（见下） |
| 开机自启 | 右键菜单 →「开机自启」，登录 Windows 后自动启动（写在 HKCU Run 项，无需管理员权限） |
| 位置记忆 | 关闭时保存窗口位置与大小到配置文件 |

> 早期版本支持账号密码登录，已移除：平台的网页登录受服务端设备风控保护
> （`RISK_DEVICE_DETECTED`），纯 HTTP 客户端无法可靠通过，而 API Key 是官方支持的正规接口。

## 环境要求

- JDK 8 或更高（`pom.xml` 以 Java 8 为编译目标）
- Maven 3.x（构建用）

## 运行方式

### 方式一：直接下载（不需要 JDK / Maven）

1. 打开 [Releases 页面](https://github.com/fcymz/dstokencheck/releases)，下载最新版的
   **`dstokencheck.jar`** 和 **`run.bat`**。
2. 把这两个文件放到**同一个文件夹**里（例如 `D:\dstokencheck\`）。
3. 双击 `run.bat`。

只需要设备装了 **Java 8 或更高版本**（[下载](https://adoptium.net/)）。
没装 Java 的话 `run.bat` 会一闪而过，请先装 JRE/JDK。

### 方式二：从源码构建

需要 **JDK 8+** 和 **Maven**：

```bat
git clone https://github.com/fcymz/dstokencheck.git
cd dstokencheck
mvn clean package
run.bat
```

或者直接：

```bat
java -jar target\dstokencheck.jar
```

> `run.bat` 会按顺序找两个位置：`target\dstokencheck.jar`（本地构建产物），
> 然后是和自己同目录的 `dstokencheck.jar`（从 Releases 下载的）。
> 两个都没有时会打印明确提示并暂停，不会一闪而过。

> **`run.bat` 必须保持纯 ASCII 内容。**
> `cmd.exe` 用系统 OEM 代码页（简中环境是 GBK）解析批处理文件，不是 UTF-8。
> 文件里一旦有中文注释，UTF-8 字节会被按 GBK 误解码，可能解出 `&` 之类的命令分隔符，
> 于是**注释行会被当成命令执行**——表现为满屏
> `'xxx' is not recognized as an internal or external command`，甚至把注释里的
> `mvn clean package` 真的跑一遍，最后根本走不到启动那一步。
> `--selftest` 里有一项专门检查这个（非 ASCII 字节数必须为 0）。

## 发布新版本

推送一个 `v*` 形式的 tag，GitHub Actions 会自动构建并把 jar 挂到 Release 上：

```bat
git tag v1.0.0
git push origin v1.0.0
```

工作流定义见 `.github/workflows/release.yml`。用 CI 构建而不是把 jar 提交进仓库，
是为了不让每次更新都往 git 历史里塞一份 5.6 MB 的二进制。

## 使用说明

1. 首次启动弹出登录窗口，粘贴 API Key（在
   [platform.deepseek.com/api_keys](https://platform.deepseek.com/api_keys) 创建，
   点窗口里的链接可直接打开）。
2. 勾选「记住我」→ 点「登录」。程序会先请求一次余额确认 Key 有效，再保存。
3. 之后每次打开都会自动读取已保存的 Key 并直接显示余额。
4. 窗口操作：
   - **拖动**：按住窗口空白处拖
   - **缩放**：拖窗口边缘 / 角
   - **改字体**：`Ctrl + 滚轮`，或右键 →「字体大小」
   - **右键菜单**：刷新、退出登录、开机自启、置顶、窗口尺寸、字体大小、刷新间隔、退出
   - 右上角图标：图钉（置顶）、刷新、隐藏

### 置顶（为什么会盖住任务栏，以及怎么修的）

`setAlwaysOnTop(true)` 只是给窗口打上 `WS_EX_TOPMOST` 标记。**任务栏也是 topmost 窗口**，
而 topmost 这一层内部是谁最后被激活谁在上面——所以把小窗口拖到任务栏上、再点一下任务栏，
任务栏就会盖住小窗口，尽管 `isAlwaysOnTop()` 依然返回 `true`。

修法是主动把窗口重新放回 topmost 层的最前面：

- 窗口失去激活（点任务栏正是这种情况）时立刻重排一次；
- 另有一个 1 秒的守护定时器兜底，覆盖任务栏预览、弹出面板这类不产生激活事件的情况；
- 调用的是 `SetWindowPos(HWND_TOPMOST, …, SWP_NOACTIVATE)`，**不会抢焦点**，
  所以不会打断你正在别的程序里打字；
- 自己的对话框（设置窗口等）打开时跳过，免得小窗口压到自己的对话框上面。

> 踩坑记录：`HWND_TOPMOST` 是 `(HWND)-1`，但 jna-platform 没导出这个常量，
> 得自己写。而 `Pointer.createConstant` 有 `int` 和 `long` 两个重载，
> **`createConstant(-1)` 会走 int 重载并零扩展成 `0xFFFFFFFF`**，作为 `hWndInsertAfter`
> 是非法值，`SetWindowPos` 直接返回 false、错误码 1400（ERROR_INVALID_WINDOW_HANDLE）。
> 必须写成 `createConstant(-1L)`。
>
> 这个 bug 光看代码看不出来，是靠 `tools/verify-topmost.ps1` 把任务栏真的抬到上面、
> 再检查 z-order 才抓到的。

### 字体大小
三种改法，效果相同，都是对**整个界面等比缩放**（不是只放大金额数字）：

- **`Ctrl` + 鼠标滚轮**：在窗口上滚动，每次 ±5%。
- **右键 →「字体大小」**：选 80% / 100% / 125% / 150% / 175% / 200% / 250%，或「更大 / 更小」。
- **右键 →「字体大小」→「自定义…」**：滑块在 70%–250% 之间无级调节，边拖边预览。

调节时窗口会按同比例自动放大或缩小，避免文字被裁掉；右上角图标按钮同步缩放
（最小 16px，防止小到点不中）。窗口最小尺寸也随之变化——100% 时 240×130，200% 时 480×260。
调完仍可继续手动拖边缘改变形状。

### 开机自启

右键菜单勾选「开机自启」即可，取消勾选就关闭。

> **必须用 `run.bat` 或 `java -jar target\dstokencheck.jar` 启动才能设置。**
> 在 IntelliJ IDEA 里直接点 Run 运行的是 `target\classes` 目录而不是 jar，
> 程序无法确定该把什么写进注册表。这种情况下点击菜单项不会静默失败，
> 而是弹窗告诉你原因——不会写坏注册表。

- 实现方式是往 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 写一个名为
  `DeepSeekBalanceBoard` 的字符串值，**只影响当前用户，不需要管理员权限**。
- 开启时会先把 jar **复制一份到稳定位置**
  `%LOCALAPPDATA%\dstokencheck\dstokencheck.jar`，注册表指向这个副本：

  ```
  "C:\...\bin\javaw.exe" -jar "C:\Users\<你>\AppData\Local\dstokencheck\dstokencheck.jar" --at-logon
  ```

  这样做是必须的：如果注册表直接指向构建产物 `target\dstokencheck.jar`，
  一次 `mvn clean` 就会删掉它，开机启动失败，而程序又因为启动失败无法自我修复，
  开机自启就**永久坏掉**了。复制到稳定位置后，`mvn clean` 不再影响它。
  用 `javaw.exe` 是为了启动时不弹黑色控制台窗口。
- 每次启动都会核对这条注册表项；副本过期会刷新、路径变了会改写（自愈）。
  **注册表项本身就是唯一的状态来源**——菜单勾选读它、切换写它、启动校对它，
  配置文件里不再另存一份。校对只会**刷新已存在的项**：既不主动创建
  （那会把用户关掉的功能又打开），也不主动删除（那会毁掉用户手工加的项）。

  > 早期版本在配置文件里也记了一份 `autoStart`，两边会不一致：配置里标志丢了之后，
  > 注册表项还在、菜单也显示已勾选，但启动校对会整段跳过，稳定副本就再也不刷新了。
  > 现在配置文件里没有这个字段，旧文件里的残留会被自动清掉。
- 末尾的 `--at-logon` 是给「随登录启动」的实例做标记用的。开机自启时如果**没有可用的
  已保存 Key**，程序会**静默退出**而不弹登录框——否则每次登录 Windows 都会跳一个对话框。
  手动双击启动不受影响，照常提示登录。

#### 怎么确认自启到底有没有生效

程序每次启动都会往 `%USERPROFILE%\.dstokencheck\startup.log` 追加几行。开机后打开这个文件：

```
2026-09-28 17:10:20  --- launch: startedAtLogon=true java=1.8.0_181 jar=...\dstokencheck.jar ---
2026-09-28 17:10:20  config: rememberApiKey=true hasProtectedKey=true autoStartRegistryEntry=true
2026-09-28 17:10:20  autostart: registry entry already up to date (stableJar=true)
2026-09-28 17:10:20  stored key decrypted OK -> showing the widget
```

- **有这次开机时间的行** → Windows 确实启动了它。
- **完全没有新行** → Windows 没执行注册表项（或被安全软件拦了）。
- `no saved API key -> exiting without a window` → 启动了，但没保存 Key 所以没有窗口；
  去勾选「记住我」重新登录一次。
- `no usable stored key` → 保存的 Key 解不开（换了用户/机器），重新登录一次。

也可以随时用命令核对：

```bat
java -jar target\dstokencheck.jar --autostart status
```

关闭方式（任选其一）：

- 右键菜单取消勾选「开机自启」
- `java -jar target\dstokencheck.jar --autostart off`
- 注册表编辑器删除该项

### 命令行参数

```bat
:: 终端里直接验证 Key 并打印余额
java -jar target\dstokencheck.jar --diag <API_KEY>

:: 查看 / 开启 / 关闭开机自启（与菜单等价，会同步写入配置文件）
java -jar target\dstokencheck.jar --autostart status
java -jar target\dstokencheck.jar --autostart on
java -jar target\dstokencheck.jar --autostart off

:: 自测拖动 / 缩放 / 最小尺寸 / 退出登录 / 开机自启 / 菜单交互 / run.bat 编码 / 字体缩放 / 刷新下限
java -jar target\dstokencheck.jar --selftest

:: 把两个窗口离屏渲染成 PNG
java -jar target\dstokencheck.jar --shot tools

:: 演示模式：不联网，用假数据显示窗口，用于预览界面
java -jar target\dstokencheck.jar --demo
```

## API Key 的保存与加密

Key **不会以明文写入磁盘**。加密策略按平台自动选择，格式自带前缀以便识别：

| 前缀 | 方案 | 适用 | 强度 |
| --- | --- | --- | --- |
| `dpapi:` | Windows DPAPI（`CryptProtectData`） | Windows（默认） | 密钥由 Windows 托管，**绑定当前用户 + 本机**。换个用户或把配置文件拷到别的电脑都无法解密 |
| `aesgcm:` | AES-256-GCM，密钥由 PBKDF2 从本机信息派生 | 非 Windows 或 DPAPI 调用失败时兜底 | **混淆级别**，不是真正的保密：能以此用户身份运行代码的人也能派生出同一把密钥 |

其他细节：

- 登录时会**先联网验证** Key 再保存，避免把打错的 Key 存下来。
- 解密失败（换了用户/机器、文件损坏或被篡改）会返回 `null` 而不是垃圾数据，程序会
  自动清除无效配置并重新询问，不会反复启动失败。
- 不勾选「记住我」时 Key 只存在于内存中，退出即失效。

**能力边界**：两种方案都防不住「已经能以你的身份在本机运行代码」的攻击者。
如果你需要更强的保护，请不要勾选「记住我」，每次启动手动粘贴。

### 配置文件

位置：`%USERPROFILE%\.dstokencheck\config.properties`

```properties
apiKeyProtected=dpapi:...（一长串密文，此处省略）   # 加密后的 Key（唯一保存位置）
rememberApiKey=true                                          # 是否记住
refreshSeconds=60                                            # 刷新间隔，最低 5 秒
fontScale=1.0                                                # 字体缩放，0.7 ~ 2.5
alwaysOnTop=true
opacity=1.0
window.x=... / window.y=... / window.width=... / window.height=...
```

想强制重新登录，删掉 `apiKeyProtected` 和 `rememberApiKey` 两行（或直接删文件）即可，
也可以直接用界面上的「退出登录」。

## 接口说明

只用了一个官方文档里的公开接口：

```
GET https://api.deepseek.com/user/balance
Authorization: Bearer <API_KEY>
```

响应形如：

```json
{
  "is_available": true,
  "balance_infos": [
    { "currency": "CNY", "total_balance": "87.65",
      "granted_balance": "0.00", "topped_up_balance": "87.65" }
  ]
}
```

参考：[Get User Balance | DeepSeek API Docs](https://api-docs.deepseek.com/api/get-user-balance/)

## 代码结构

```
src/main/java/com/ruoyi/dstokencheck/
├── Main.java                     入口：GUI 编排 + --diag/--autostart/--demo/--selftest/--shot
├── config/AppConfig.java         配置读写（%USERPROFILE%\.dstokencheck）
├── security/SecretStore.java     Key 加密：DPAPI 优先，AES-GCM 兜底
├── autostart/AutoStart.java      开机自启：读写 HKCU Run 注册表项
├── model/Wallet.java             单个币种钱包
├── model/BalanceSnapshot.java    一次刷新的完整快照 + 币种汇总逻辑
├── net/Http.java                 Java 8 的 HttpURLConnection 封装
├── net/DeepSeekClient.java       余额接口调用 + Key 掩码显示
└── ui/
    ├── Theme.java                颜色与字体（自动挑选中文字体）
    ├── IconButton.java           自绘图标按钮
    ├── ApiKeyDialog.java         无边框 API Key 登录窗口
    └── BalanceBoard.java         无边框主窗口（自实现拖动、缩放、退出登录）

tools/                            开发期工具（可选，不影响运行）
├── SecretProbe.java              验证加密与「记住我」存取（会写 user.home，请配合 -Duser.home 用）
├── capture-window.ps1            真实截屏某个窗口（DPI 感知），用于验证渲染
├── window-timeline.ps1           按时间轴打印窗口出现顺序，用于验证「记住我」自动登录
├── verify-topmost.ps1            抬起任务栏并检查 z-order，验证「不被任务栏盖住」
├── scan-chunks.ps1               扫描平台前端 chunk（当初定位接口用，现已不需要）
├── apikey-window.png             登录窗口渲染效果
└── demo-window.png               小窗口渲染效果
```

> **工具不会碰你的真实配置。** `capture-window.ps1` 和 `window-timeline.ps1` 都会把
> `user.home` 指到 `%TEMP%` 下的临时目录再启动程序，所以既不会读也不会写
> `%USERPROFILE%\.dstokencheck\`。`SecretProbe.java` 读的是 `user.home`，
> 请自己加上 `-Duser.home=%TEMP%\probe` 再跑。
>
> （早期版本的 `capture-window.ps1` 为了拿到干净的启动状态，会直接
> `Remove-Item` 真实配置文件——那会连加密保存的 API Key 一起删掉，且不进回收站。
> 已改为隔离临时目录，并用哨兵文件验证过不再触碰真实配置。）

## 已知限制

- 跨币种余额不做汇率换算，只汇总同一币种，避免出现无意义的总数。
- 拖动与缩放由程序自己实现（无边框窗口没有系统标题栏），在窗口边缘 5 像素内按下即进入缩放。
- `authorization` 头是逐一请求发送的，本程序不缓存余额以外的任何账户信息。
- `tools/SecretProbe.java` 会写真实配置文件，运行时请先备份（它的 `--clear` 会清空登录状态）。

## 一个踩过的坑：窗口圆角不要用「逐像素透明」

圆角最初是用 `setBackground(new Color(0,0,0,0))`（逐像素半透明）实现的，结果
**登录界面的所有文字都不显示**——渐变背景和自绘的矢量图标（右上角 ✕）都正常，唯独
`JLabel` / `JTextField` / `JButton` 的文字一个字都看不见，而且不报任何错。

原因：Windows 上逐像素半透明的窗口会让 Java2D 走 "LCD 次像素抗锯齿" 分支，该分支把字形
的 alpha 写成 0（它假设目标是**不透明**表面）。于是文字实际上「画成功了」，只是全程透明。
矢量线条不受影响，所以只有字消失。

修复方式是**不用逐像素透明**，改成不透明窗口 + `setShape(RoundRectangle2D)` 裁剪圆角，
视觉一致而文字正常。窗口大小变化时要同步更新 shape（见 `BalanceBoard.applyShape`）。

> 排查这类问题时，离屏 `window.paint(g)` 渲染到 `BufferedImage` 是**不可靠**的：
> 它走的是普通 `Graphics2D`，画得好好的，和屏幕上的结果不一致。要用
> `tools/capture-window.ps1` 真实截屏，或把离屏目标设成 `TYPE_INT_RGB`（本项目已改）。
