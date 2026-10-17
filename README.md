<p align="center">
  <img src="docs/logo.svg" width="112" alt="虾米爱打卡图标" />
</p>

# 虾米爱打卡 · XiamiCheckIn

一个支持工作日及可选周末提醒、按目标 WiFi 判断的 Android 打卡助手。到达设定时间并连接指定 WiFi 后，自动打开飞书；未连接时等待网络，锁屏时提醒并等待解锁。

| 项目 | 信息 |
| --- | --- |
| App 名称 | 虾米爱打卡 |
| 工程 / 建议仓库名 | `XiamiCheckIn` |
| Android 包名 | `com.xiami.checkin` |
| 版本 | `1.0.0` |
| 支持系统 | Android 8.0（API 26）及以上 |

## 功能

- 添加、删除多个提醒时间，默认仅周一至周五执行。主界面可开启「周末也自动打开」，周六、周日也使用同一组时间，开关状态自动保存。
- 通过系统分钟广播 `TIME_TICK` / 时间变更广播检查当前时间，同一时间点每天最多触发一次。
- 当前主界面初始化时，若列表不含 `09:15`，会补入 `09:15` 与 `18:50`。
- 连接目标 WiFi 后打开飞书；未连接时通过前台服务等待，并提供「关闭监控」操作。
- 锁屏时记录待打开状态，解锁后继续。
- 打开飞书前先显示不透明过渡页，获得焦点后等待约 1.5 秒，再切回飞书，处理飞书原本就在前台的情况。
- 定时触发、通知点击、解锁和「立即打开飞书」使用同一唤起流程。
- 橙色时钟图标、独立通知图标及 Android 13 主题图标。

## 设置周末自动打开

在主界面的监控时间列表上方开启「周末也自动打开」，再用右下角 `+` 设置提醒时间。开启后每天按这组时间执行；关闭后新的定时提醒仅在周一至周五触发。设置重启 App 后仍保留，已有的时间点不变。

周末仍需满足目标 WiFi 条件，锁屏时仍等待解锁。开关控制新的定时提醒；已经触发并进入等待 WiFi 或解锁的流程不因此取消。「立即打开飞书」按钮不受日期限制。

## 配置目标 WiFi

把以下配置添加到工程根目录的 `local.properties`，保留 Android Studio 自动生成的 `sdk.dir`：

```properties
target.wifi.ssid=你的办公WiFi名称
```

参考文件为 [`local.properties.example`](local.properties.example)。配置修改后需要重新构建安装。此文件按 UTF-8 读取，支持中文 WiFi 名称；反斜杠按 Java Properties 规则写作 `\\`。

`local.properties` 已被 Git 忽略，仓库中不保存你的实际 WiFi 名称。没有配置时，构建使用占位值 `YOUR_WIFI_SSID`。WiFi 名称会写入生成的 APK，分享 APK 时请使用适合公开的配置。

## 构建与运行

- JDK **21**（Gradle 运行环境）；Java 源码兼容目标为 11。
- Android SDK **35**。
- Gradle **9.3.1**、Android Gradle Plugin **9.1.0**。
- Jetpack Compose / Material 3；Compose 编译插件版本见 `gradle/libs.versions.toml`。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式发布请使用自己的签名配置；签名文件和密码配置已加入忽略规则。


## 手机设置

根据系统提示授予通知、WiFi / 位置读取和后台位置权限，并开启系统定位服务。在系统设置中为「虾米爱打卡」允许后台运行、自启动及所需的悬浮窗权限，避免后台唤起被拦截。

当前调度依赖应用进程接收分钟广播，前台服务有助于维持运行；进程被杀或设备休眠时仍可能错过时间点。Manifest 中的精确闹钟权限是现有声明，当前调度并未使用精确 `AlarmManager` 闹钟。

## 工程结构

```text
app/src/main/java/com/xiami/checkin/
├── helper/       # 时间、WiFi、通知及飞书唤起
├── receiver/     # 提醒、开机与解锁广播
├── service/      # 前台服务与 WiFi 等待
└── ui/           # 主界面、过渡页和主题
```

- App 名称和通知文案：`app/src/main/res/values/strings.xml`
- 包名与版本：`app/build.gradle.kts`
- 工程名：`settings.gradle.kts`
- 图标：`app/src/main/res/drawable/`、`mipmap-anydpi-v26/`、`mipmap-anydpi-v33/`
- 品牌图：`docs/logo.svg`


## 真机验证

1. 连接目标 WiFi，将提醒设置为最近的工作日时间，让飞书保持前台。到点后应先显示过渡页，再返回飞书。
2. 在飞书前台锁屏，等待提醒后解锁；通知点击也应经过过渡页。
3. 在过渡页等待期间按 Home 或锁屏，不应在等待结束后突然拉起飞书。
4. 从非目标 WiFi 切换到目标 WiFi，检查等待监控是否结束并进入唤起流程。
5. 以飞书显示的实际考勤结果为准；助手不读取打卡结果，前后台切换也不保证打卡成功。请在普通全屏模式下验证。
6. 周六或周日关闭「周末也自动打开」，设置一个尚未触发的未来时间，应不产生新的定时提醒；开启后用另一个未来时间验证正常触发，并重启 App 确认开关状态保留。周一至周五两种开关状态均应正常触发。

## 项目来源
「飞书」及包名 `com.ss.android.lark` 指本工具唤起的第三方客户端。本项目是独立辅助工具，与飞书官方无隶属关系。
