# ReelsPlayer LAN

[English](README.md) | 简体中文

一款 Android 视频播放器：把下载好的 Reels、Shorts、TikTok 视频按竖向滑动的方式刷起来 —— 并且补上原版没有的能力：**直接从 NAS 或电脑共享（SMB）里串流播放**。

选本地文件夹，或者选 NAS 上的文件夹，体验完全一样：全屏、竖向滑动。

> 本项目是 [ReelsPlayer](https://github.com/ctyfrank/reelsplayer)（MIT）的衍生项目：保留原有的竖向滑动 UI 和本地播放，把「只读本地文件」的数据层换成同时支持 SMB 的实现。所有引用的项目见[引用说明](#引用说明)。

## 功能

**播放**

- 全屏竖向滑动播放，点击暂停/播放，长按右侧 2 倍速
- 单个视频循环、底部细进度条、`当前 / 总数` 计数

**本地视频**

- 用系统文件选择器选一个文件夹，递归播放其中及所有子文件夹的视频
- 播放顺序按相对路径排序，同一目录的视频相邻
- 大目录扫描时显示已找到的视频数

**局域网 / SMB**

- **多服务器保存**：每台服务器独立的主机、端口、共享名、域、用户名、密码
- 支持 **访客 / 匿名**，以及给老设备用的 **SMB1** 开关
- **凭据加密**：服务器列表用 Android Keystore 里生成的 AES-256-GCM 密钥整体加密，拷走应用配置也拿不到密码
- **目录浏览**：逐级浏览共享目录；每台服务器单独记住上次播放的目录
- **递归扫描**：把选中的共享目录及子目录里的视频全部加入滑动列表

**问题诊断**

- **内置日志**：播放失败、SMB 连接/列目录/串流细节、扫描结果都会记录完整异常链（含 SMB 的 NT 状态码），不再只是一个 toast
- **一键导出**：日志页把设备信息、应用版本、网络、已保存服务器（不含密码）和全部日志写成一个文件，并直接调起分享面板
- **崩溃捕获**：未捕获异常会先写进同一份日志，再退出进程

**串流优化**

- 可随机读的 SMB 数据源，1 MiB 预读窗口（MP4/MKV 的索引在文件尾，必须支持跳读）
- 512 MB LRU 磁盘缓存，重看和拖动进度直接命中本地
- 播放当前视频时预取下一个视频的开头（计费网络下自动跳过）
- ExoPlayer 缓冲按局域网调优（1.5 秒起播，最多缓冲 180 秒）
- 播放器实例池化复用，滑动时不反复重建

## 使用

### 本地文件夹

首页点「选择文件夹」→ 选中目录 → 直接滑动播放。

### NAS / 电脑共享

1. 首页点「局域网服务器」。
2. 点 **+**：填主机（`192.168.1.10` 或 `nas.local`），可选端口和共享名，再填用户名和密码，或勾选「访客 / 匿名」。点「测试连接」可以在不保存的情况下验证登录。
3. 点进服务器，逐级进入放视频的目录，点「播放此文件夹」。

在服务器列表里长按某一项，可以编辑、测试或删除。

### 视频放不出来的时候

播放失败时底部会弹出提示，点「查看日志」即可进入日志页：里面记了是哪台服务器、哪个文件、ExoPlayer 的错误码，以及底层 SMB 的状态码。点「导出」会把整份报告写成文件并调起分享面板，点「复制」放进剪贴板。想看得更细，先勾上「详细日志」，会额外记录每个目录的扫描和每个条目的绑定过程。

日志同时会写到 `filesDir/reelsplayer-lan.log`（超过 2 MB 自动轮转），所以上一次崩溃的记录在重启后仍然在。

说明：

- 默认协商 SMB2/SMB3；只有老路由器 / 老 NAS 才需要勾选「旧设备（SMB1）」。
- 密码用设备绑定的 Keystore 密钥加密，只会发给你配置的那台服务器。
- 串流缓存在应用缓存目录里，清除应用缓存即清空。

## 环境要求

- Android 8.0 及以上（API 26）
- 视频格式取决于 ExoPlayer 的支持范围（常见如 MP4/H.264、WebM、MKV 等）
- 局域网：一台 SMB 服务器（Windows 共享、Samba、群晖/威联通、路由器 USB 共享等）

## 构建

需要 **JDK 17**（AGP 8.2 要求）以及包含 `platforms;android-34`、`build-tools;34.0.0` 的 Android SDK。

macOS / Linux：

```bash
git clone https://github.com/ctyfrank/reelsplayer-lan.git
cd reelsplayer-lan
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

Windows（PowerShell，`.properties` 里 `\` 是转义符，务必用正斜杠）：

```powershell
git clone https://github.com/ctyfrank/reelsplayer-lan.git
cd reelsplayer-lan
Set-Content local.properties "sdk.dir=E:/Android/Sdk"
.\gradlew.bat assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

签名发布：仓库根目录放 `reelsplayer-lan-release.jks`，并设置环境变量 `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，再执行 `./gradlew assembleRelease`。

## 技术栈

Kotlin、AndroidX ViewPager2、Media3 ExoPlayer 1.2.0、jcifs-ng 2.1.10（SMB）、Android Keystore（AES-256-GCM）。compileSdk 34、minSdk 26、targetSdk 34、JDK 17。

## 引用说明

本项目是修改后的衍生作品，不是原作者发布的版本。它建立在以下工作之上：

| 项目 | 作者 | 许可证 | 本项目使用了什么 |
|---|---|---|---|
| [ReelsPlayer](https://github.com/ctyfrank/reelsplayer) | underwear | MIT | 基础应用：竖向滑动播放、本地目录播放、界面。`MainActivity`、`VideoAdapter` 与相关布局由此修改而来。 |
| [CIFS Documents Provider](https://github.com/wa2c/cifs-documents-provider) | wa2c | MIT | jcifs-ng 用法参考：SMB 版本协商、NTLM / 访客 / 匿名上下文、超时配置。未复制其源码文件。 |
| [jcifs-ng](https://github.com/AgNO3/jcifs-ng) | AgNO3 GmbH & Co. KG | LGPL-2.1 | 运行时使用的 SMB2/SMB3（及 SMB1）客户端库。 |
| [Bouncy Castle](https://www.bouncycastle.org/java.html)（`bcprov-jdk18on`） | The Legion of the Bouncy Castle | MIT 风格 | jcifs-ng 依赖的加密实现。 |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Google / AndroidX | Apache-2.0 | 播放引擎、`DataSource` 接口、磁盘缓存（`SimpleCache`）。 |
| [AndroidX AppCompat / Material / ViewPager2 / DocumentFile](https://developer.android.com/jetpack/androidx) | Google / AndroidX | Apache-2.0 | 界面组件、滑动容器、SAF 辅助类。 |
| [SLF4J](https://www.slf4j.org/) | QOS.ch | MIT | jcifs-ng 使用的日志门面（绑定 `slf4j-nop`）。 |

上游 MIT 许可证全文见 [CREDITS.md](CREDITS.md)。jcifs-ng 采用 LGPL-2.1：本项目以独立库的形式原样链接，源码可在上表链接处获取。

## 开源协议

MIT，见 [LICENSE](LICENSE)。部分代码衍生自 ReelsPlayer（MIT，© 2026 underwear）。