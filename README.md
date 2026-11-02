<div align="center">

# SwiftPlayer for Android

**为自建 NAS 家庭影院打造的 Android 播放器 —— 本地 · SMB（飞牛 NAS）· Emby 三合一**

*A self-hosted-home-theater video player for Android: local files, SMB (incl. fnOS NAS) and Emby in one app.*

![License](https://img.shields.io/badge/License-GPL--3.0-blue)
![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple)
![Player](https://img.shields.io/badge/Engine-Media3%20%2F%20ExoPlayer-orange)

![SwiftPlayer 演示](docs/demo.gif)

</div>

---

## 为什么有这个项目

市面上的播放器要么只管本地文件，要么是 Emby/Jellyfin 的"套壳 WebView"，要么对国产 NAS（飞牛 fnOS）的 SMB 支持很差。SwiftPlayer 用 Kotlin + Media3 原生实现，把 **本地硬盘 / 局域网 SMB 共享 / Emby 媒体库** 装进同一个 App，并且把自建 NAS 场景里最疼的点（SMB 连不上不知道为什么、长片快进像蜗牛）逐个解决。

## 功能亮点

### 📂 三种片源，一个入口

| 片源 | 能力 |
|---|---|
| **本地** | 系统媒体库 + 「所有文件访问」浏览隐藏目录（`.` 开头文件夹），缩略图、时长、分辨率 |
| **SMB** | 飞牛 fnOS / 群晖 / Windows 共享；**真随机读取**（seek 只重开文件句柄，不重建连接）；共享名**留空自动识别**；子目录逐级浏览 + 返回键回退；连接失败**中文归因**（服务未开启 / 账号错误 / 无权限 / 共享不存在，附排查指引） |
| **Emby** | 原生 API 客户端（非 WebView）：海报墙、详情、收藏、搜索、演员、类型、相似推荐、**播放进度双向同步**、跨设备续播、外挂字幕 side-load、管理员删除影片 |

### 🎬 手势播放（核心差异化）

- **横拖渐进式 seek**：幂函数加速曲线——小幅拖动秒级微调，大幅拖动一次跳 20+ 分钟（2h 电影：1 屏 ≈ 7 分钟，2 屏 ≈ 23 分钟），配合中心 HUD 实时预览目标时间与缩略图
- **长按倍速**：长按 2x 起播，右拖升 3x / 4x，松手恢复原速
- **双击**：左 / 右 ±10s，中部播放暂停
- **竖拖**：左半屏亮度 / 右半屏音量

### 🧠 为自建 NAS 优化的工程细节

- **局域网地址优选**：Emby 双地址配置（外网 + 内网），60s TTL 探测，在家自动切内网直连（更快、seek 更顺）
- **SMB 数据源**：基于 smbj 实现的 Media3 `DataSource`，按播放生命周期复用连接，连续 seek 不卡顿
- **路径规范**：全链路 UNC 反斜杠规范化（踩过 smbj `/` 分隔符的坑）
- **深色锁定** + M3 视觉体系，深浅色系统下 UI 一致
- 核心手势策略有 **单元测试** 覆盖

## 系统要求

- Android 7.0（API 24）及以上
- Emby 服务器 4.x（演员/类型浏览需刮削元数据）
- SMB 服务器需开启 SMB2/3（飞牛 fnOS：文件管理 → SMB）

## 构建

```bash
git clone https://github.com/cptbt22/SwiftPlayer-Android.git
# 用 Android Studio 打开，或：
./gradlew :app:assembleDebug
```

**签名说明**：项目不带签名密钥。Release 构建时：

1. 在项目根放置你自己的 `swiftplayer-release.jks`（**请勿提交到 Git**，已在 .gitignore 忽略）；
2. 通过环境变量 `SP_STORE_PASSWORD` / `SP_KEY_ALIAS` / `SP_KEY_PASSWORD`，或在 `local.properties`（已忽略）中配置 `sp.store.password` / `sp.key.alias` / `sp.key.password`；
3. 没有密钥时 Release 自动回退 debug 签名，方便本地安装测试。

## 使用

1. **Emby**：Emby 页 → 右下角 + → 填服务器地址（可另填局域网地址）→ 登录。演员/类型浏览依赖刮削器写入的人物与 Genres 元数据。
2. **SMB（飞牛 NAS）**：局域网页 → 手动添加 → 填 NAS 的 IP 与账号密码，**共享名可留空**（自动识别）；也可从扫描结果直接点击连接。若连接失败，请确认飞牛「文件管理 → SMB」已开启服务并勾选共享目录、用户已被授权。
3. **本地**：授予「所有文件访问」权限即可浏览全部存储。

## 项目结构

```
app/src/main/java/com/swiftplayer/
├── player/     # 手势状态机、seek 策略（可测纯函数）、seek HUD
├── smb/        # SMB 浏览客户端、随机读取 DataSource、路径规范、局域网发现
├── data/emby/  # Emby API / 仓库层（地址优选、播放决策、进度同步）
└── ui/         # 主页三 Tab、Emby 视图态机、播放器控制
```

## 技术栈与致谢

[Kotlin](https://kotlinlang.org) · [Media3/ExoPlayer](https://github.com/androidx/media) · [OkHttp](https://github.com/square/okhttp) · [smbj](https://github.com/hierynomus/smbj) · [JmDNS](https://github.com/jmdns/jmdns) · [Glide](https://github.com/bumptech/glide) · [Material Components](https://github.com/material-components/material-components-android)

感谢以上开源项目，本项目站在它们的肩膀上。

## 商标声明与免责

- 本项目是 **Emby 服务器的非官方第三方客户端**，与 Emby LLC 无任何隶属或合作关系。*Emby* 是 Emby LLC 的商标。
- *飞牛 / fnOS* 为其权利方的商标/产品，本项目与其无隶属关系，相关提示文字仅为帮助用户排查连接问题。
- *Android* 是 Google LLC 的商标。
- 本项目仅供个人学习与技术研究，请遵守所在地法律法规，支持正版内容。使用本项目产生的任何后果由使用者自行承担。
- 界面演示动图为示意图，实际效果以真机运行为准。

## License

[GPL-3.0](LICENSE) © 2026 SwiftPlayer contributors

使用本项目代码的衍生作品需同样以 GPL-3.0 开源。欢迎 Issue / PR。
