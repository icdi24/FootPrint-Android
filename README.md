# 脚印 · Android App

「脚印」是双人共享监督打卡与积分系统（Web 版 + 后端见 `icdi24` 同名仓库）。本仓库是它的 **Android 原生 App**。

App 是纯前端客户端：所有业务数据（账号、积分、目标、打卡、愿望单）都在后端 PostgreSQL，App 只负责展示 + 操作 + 唯一本地特有的能力——**读取手机各 App 使用时长**并上报。

## 功能

- **今天**：问候 + 积分横幅 + 今日待办一键打卡 + 进行中的小目标 + 手机使用时长
- **目标**：日常目标（周/月视图网格、前后翻页）+ 小目标（提交验收/开始/放弃）
- **愿望单**：愿望列表 + 新建/编辑 + 兑换 + 撤销 + 兑换记录
- **我们**：对方打卡 + 驳回 + 待验收小目标 + 并排对比 + 手机在谁手里（双方时长 + Top5 App 并排）
- **我的**：统计 + 积分流水 + 改口令 + 积分系数 + 备份

> 未实现（M2，后续）：锁机、双人契约锁。未做：打卡弹层的证据文字/长按秒打卡。

## 技术栈

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose（像素风，自绘控件，不用 Material 圆角控件） |
| 包名 / applicationId | `me.footprint.app`（**定死勿改**） |
| minSdk / targetSdk / compileSdk | 26 / 37 / 37 |
| 版本组合 | Gradle **9.6.0** + AGP **9.4.1** + Kotlin **2.2.10**（AGP 内置）+ JDK **17** |
| 网络 | OkHttp 4.12.0 + kotlinx.serialization（**不用 Retrofit**） |
| 本地存储 | 仅 `SharedPreferences`（token、上报节流时间），不引数据库 |
| 字体 | Fusion Pixel 12px（与 Web 同款，woff2→ttf 打进 `res/font`） |

## 架构

```
手机 App（Kotlin/Compose，纯前端）
   │  HTTP + Cookie（fp_token）调接口
   ▼
后端（Node.js + Express + PostgreSQL，跑在另一台电脑/服务器）
```

App 不连后端就无法登录/打卡/看数据，唯一离线可用的是「读本机使用时长」。BASE_URL 见下。

## 打开工程

1. 装 **Android Studio**（需 2026.2.2 或更新，自带 AGP 9.x）。
2. SDK 需要 **API 37**。
3. `File → Open` 选本仓库根目录。
4. 首次打开会自动下载 Gradle 9.6.0 + AGP。

> 仓库故意没有 `gradle-wrapper.jar`（二进制无法可靠用文本生成），由 Android Studio 自动补。
> `local.properties`（含 `sdk.dir`、`backendUrl`）不进 git，需本机自建，见下节。

## 改后端地址（BASE_URL）

`BuildConfig.BASE_URL` 从 `local.properties` 读：

```properties
# local.properties（不进 git）
sdk.dir=C:\\Users\\你\\AppData\\Local\\Android\\Sdk
backendUrl=http://100.81.110.28:3201/
```

- **末尾 `/` 必须留**，代码靠它拼相对路径。
- 不写 `backendUrl` 默认 `http://10.0.2.2:3000/`（模拟器访问宿主机）。
- 改完 Sync/Rebuild 后 `BuildConfig` 才更新。
- 真机连后端：手机装 Tailscale 连到后端所在电脑（BASE_URL 用那台电脑的 Tailscale IP），或用同一 Wi-Fi + 局域网 IP。**不要用 `localhost`**（那是手机自己）。

## 关键实现（踩坑点）

- **时区**：后端按 `Asia/Shanghai` 判「今天」，App 也用 `ZoneId.of("Asia/Shanghai")` 算今天 0 点，**不用设备时区**。
- **Cookie**：登录响应的 `fp_token` 落进 SharedPreferences，之后每个请求手动带 `Cookie: fp_token=...`。
- **上报**：`onStart`/`onStop` 各报一次当日累计，后端覆盖写（幂等）；60 秒节流。
- **未授权不显示 0**：没开「使用情况访问」时显示引导，绝不显示「0 分钟」。
- **包可见性**：Manifest 声明了 `QUERY_ALL_PACKAGES`（否则 App 名拿不到，只显示包名）。若要上架 Google Play 需改成 `<queries>` 精确声明。

## 已知边界

- **使用时长在模拟器恒为 0**，核心功能必须真机测。
- 部分 ROM（小米/华为/OPPO）的「使用情况访问」跳转页不同，代码有兜底。
