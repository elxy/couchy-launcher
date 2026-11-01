# OPPO / OnePlus TV 按键处理机制（逆向记录）

设备：OPPO `OPPOCNM652`，Android 9（TV），固件含 MTK + CVTE + OPPO 三层定制。
记录日期：2026-11-01。

本文是实测 + 反编译 `services.jar` 得到的结论，用于回答一个问题：
**Couchy Launcher 能否截获遥控器的“应用”键？** 结论是不能，原因在固件层，
本文记录了全部证据，以及 root 后可选的改法。

---

## 1. 结论速览

| 遥控器按键 | 内核 keycode | Android keyCode | 我们的 App 能否收到 |
|---|---|---|---|
| 首页 | `KEY_HOMEPAGE` (172) | `KEYCODE_HOME` (3) | **不能**（系统消费，我们只感知“被拉到前台”） |
| 应用 | `KEY_F3` (61) | `KEYCODE_F3` (133) | **不能**（固件把 F3 并入 HOME 分支） |
| 返回 | `KEY_BACK` | `KEYCODE_BACK` (4) | 能 |
| 菜单 | `KEY_MENU` | `KEYCODE_MENU` (82) | 能 |
| 方向/确认 | `KEY_UP` 等 | `KEYCODE_DPAD_*` | 能 |

**“应用”键的真实身份：固件把它映射成 `KEYCODE_F3`，然后让 F3 与 HOME 走同一分支。**

> **2026-11-01 修正**：上表只回答“能不能作为 `KeyEvent` 到达 App”。**键的身份并没有丢**：固件按 keyCode
> 给 HOME intent 换了 category——首页键是 `android.intent.category.HOME`，应用键是
> `com.heytap.tv.launcher.category.MODERN_HOME`。桌面在 `onCreate`/`onNewIntent` 里
> `intent.hasCategory(MODERN_HOME)` 就能区分，原生桌面就是这么做的。证据与改法见
> `tigris-install/OPPO-OPPOCNM652-换桌面记录.md` 第 16 节。
它不是 `KEYCODE_APPS`(187)、`KEYCODE_ALL_APPS`(284) 或 `KEYCODE_APPSELECT`(244)。

---

## 2. 硬件与内核层

### 输入设备

```
add device 1: /dev/input/event2
  name: "OPPO RC"                      ← 蓝牙遥控器（走这个）
add device 2: /dev/input/event1
  name: "MStar Smart TV Keypad"        ← 电视机身按键
add device 3: /dev/input/event0
  name: "MStar Smart TV IR Receiver"   ← 红外接收
```

遥控器事件带 `deviceID=4`，与 `event2` 对应。

### `getevent` 实测输出

```
EV_MSC  MSC_SCAN  000c0223   EV_KEY  KEY_HOMEPAGE  DOWN/UP      ← 首页（HID Consumer 页）
EV_MSC  MSC_SCAN  0007003c   EV_KEY  KEY_F3        DOWN/UP      ← 应用（HID Keyboard 页！）
EV_MSC  MSC_SCAN  000c0224   EV_KEY  KEY_BACK      DOWN/UP
```

注意 scancode 的页不同：首页/返回是 `0x000c`（Consumer Page），
应用键是 `0x0007`（Keyboard Page）。遥控器固件把它当作**键盘 F3** 上报。

### keylayout

`/system/usr/keylayout/Generic.kl:85` → `key 61 F3`（标准映射，非厂商私有）。

`/system/usr/keylayout/ttxkeymap.ini` 是红外遥控器（TTX）的映射表，
对应 `BTN_*` → `KEYCODE_*`，与本蓝牙遥控器无关。

---

## 3. 系统层：固件如何把 F3 当成 HOME

### 拦截链

调用顺序（**重要**：`CustomPWM` 先执行，F3 的并轨发生在它之后）：

```
PhoneWindowManager.interceptKeyBeforeDispatching
   ├── [索引 201] CustomPWM.interceptKeyBeforeDispatching   ← 先跑厂商总调度器
   │      ├── mQuickModePolicy   .interceptKeyBeforeDispatching
   │      ├── mVolumeEventPolicy .interceptKeyBeforeDispatching
   │      ├── mDisableKeyPolicy  .interceptKeyBeforeDispatching
   │      └── mCustomKeyPolicy   .interceptKeyBeforeDispatching
   └── [索引 212-218] F3 ──┐
                          ├─→ 同一个 HOME 分支（F3 在这里被并轨）
        [索引 213-214] HOME ─┘
```

字节码证据（`PhoneWindowManager.interceptKeyBeforeDispatching`，975 条指令）：

```
200  move/from16      v4, v41
201  invoke-virtual   ... Lcom/android/server/policy/CustomPWM;->interceptKeyBeforeDispatching(...)J
202  move-result-wide v8
205  if-gez           v0, +5e0h     # CustomPWM 返回 <0 → 直接采用其结果
212  const/16         v0, 133       # KEYCODE_F3
213  const/4          v5, 3         # KEYCODE_HOME
214  if-eq            v7, v5, +492h # keyCode == HOME → 跳
215  if-ne            v7, v0, +008h # keyCode != F3 → 跳过
216  move/from16      v37, v11      # ★ F3 落入 HOME 分支
217  move/from16      v8, v21
218  goto/16          +48eh         # ★ 与 HOME 汇合
```

完整字节码见 `docs/reverse/custompwm-bytecode.txt`。

`interceptKeyBeforeQueueing`（Tq）和 `interceptKeyBeforeDispatching`（Ti）
两级都会被 `CVTE` 和 `CustomPWM` 各拦一次，日志里能看到：

```
cvteInterceptKeyBeforeQueueing Go in event =133
cvte interceptKeyBeforeQueueing failed          ← “failed”= 没有消费，不是出错
interceptKeyTq keyCode=133 policyFlags=0x22000001
WindowManager,CustomPWM: interceptKeyTq keyCode=133 down=true
cvteInterceptKeyBeforeDispatching Go in event =133
cvte interceptKeyBeforeDispatching failed
interceptKeyTi keyCode=133 scanCode=61 deviceID=4 mHomePressed=false
WindowManager,CustomPWM: interceptKeyTi vid = 0x2b31 pid = 0x6891 devicename = OPPO RC
WindowManager,CustomPWM: interceptKeyTi keyCode=133 kids_mode = 0 forbid_home_key = 0
```

### 铁证：`PhoneWindowManager` 里的 F3/HOME 合并

反编译 `services.jar` → `classes.dex` →
`com.android.server.policy.PhoneWindowManager.interceptKeyBeforeDispatching`
（方法共 975 条指令，以下为索引 212–218）：

```
212  const/16  v0, 133       # KEYCODE_F3
213  const/4   v5, 3         # KEYCODE_HOME
214  if-eq     v7, v5, +492h # 若 keyCode == HOME → 跳到 HOME 处理
215  if-ne     v7, v0, +008h # 若 keyCode != F3 → 跳过
216  move/from16 v37, v11    # ★ F3 落入 HOME 分支
217  move/from16 v8, v21
218  goto/16   +48eh         # ★ 跳到 HOME 的同一处
```

**这是固件显式写入的并轨，不是判断失误。** F3 与 HOME 之后走完全相同的代码。

### `CustomPWM` 各 policy 实际处理的 keyCode

`CustomPWM$CustomKeyPolicy.interceptKeyBeforeDispatching`（273 条指令）分支表：

| keyCode | 行为 |
|---|---|
| 23 (`DPAD_CENTER`) | 播放按键音（若 `IAudioManager.isKeyToneEnable()`） |
| 82 (`MENU`) / 265 (`ASSIST`) | **长按** → 打开设置（82 用 `com.android.tv.settings.GLOBAL_SETTINGS`，265 用 `android.settings.SETTINGS`） |
| 3 (`HOME`) | **长按** → `WindowManagerInternal.updateCurrentWindowSnapshot()`，再广播 `com.heytap.tv.intent.action.RECENTS_APP`（extra `reason=show`，flags `0x11000000`） |
| 142 | 启动 `com.heytap.smarthome.tv/com.heytap.smarthome.ui.activity.LaunchActivity` |
| 131 (`F1`) / 141 | 特殊处理（遥控器失联/充电状态等分支） |
| 134 (`F4`) | 广播 `com.oneplus.action.RC_CHARGE_SHIPPING_MODE` |
| 136 | 广播 `com.heytap.tv.support.action.SHOW_SOURCE_LIST`（信号源列表） |
| 137 / 138 | 广播 `com.oneplus.action.RC_CHARGE_START` / `RC_CHARGE_STOP` |
| 139 / 140 | 其他遥控器状态分支 |
| — | 另有 `optv://com.oneplus.tv.launcher2/recomFilmActivity` 的启动分支 |

**没有 133。** `CustomPWM$QuickModePolicy` 也只处理 3 和 82，同样不含 133。
所以 F3 并非被 `CustomPWM` 消费，而是**更早**在 `PhoneWindowManager` 就被并轨了。

### `CustomPWM` 读取的两个系统开关

`CustomPWM.interceptKeyBeforeDispatching` 开头（索引 12–24）读 `Settings.Global`：

| 键 | 含义 | 出厂值 |
|---|---|---|
| `kids_mode` | 儿童模式。=1 时对 keyCode **142 / 136 / 133** 直接 `return -1`（消费） | `0` |
| `home_key` | =1 时对 `KEYCODE_HOME` 直接 `return -1`（彻底屏蔽 HOME） | `0` |

日志里的 `kids_mode = 0 forbid_home_key = 0` 即这两个值。

另有 `sys.optv.pip_mode`（SystemProperty，布尔）与
`sys.optv.xtc_pip_mode`（`Settings.Global` int）：画中画模式下屏蔽 HOME/MENU，
并对 `com.heytap.iot.castservice`、`com.okii.watch.tv` 特判。

---

## 4. 实测验证

### 物理按键 vs 注入按键（重要）

`adb shell input keyevent` 注入的事件 `policyFlags=0x2b000000`，
而物理按键是 `0x22000001`，且 `scanCode=0` vs 物理的 `scanCode=61`。
**注入事件走不同路径，不能用来推断物理按键行为。**

### 行为对照

| 前台 App | 注入 133 | 注入 3 (HOME) | 注入 132 (F2) |
|---|---|---|---|
| `com.eanyatonic.cmgplayer` | → Couchy 桌面 | → Couchy 桌面 | → `systemui/TVQuickModeActivity` |

> 早前曾误以为 F3 会打开 `TVQuickModeActivity`（快捷模式/熄屏），
> 实际那是 **F2 (132)** 的行为，与“应用”键无关。熄屏确有独立按键。

物理按键实测（前台为第三方播放器）：
- 按首页键 → 回桌面
- 按应用键 → **同样回桌面**，与首页键无差别

### 探针实验：F3 到不了 App

在 `MainActivity.dispatchKeyEvent` 和 Compose `onPreviewKeyEvent` 两处加 `Log.d` 探针，
按物理“应用”键 3 次，**日志完全为空**，而 App 进程存活（`pidof` 有值）。
证明按键在到达 Activity 之前就被系统处理掉了。

> 这个探针只覆盖按键派发路径，所以得不出“无法区分”的结论。区分信息走的是 HOME intent 的 category
> （以及 `persist.sys.home.reason`），见 `tigris-install/OPPO-OPPOCNM652-换桌面记录.md` 第 16 节。

---

## 5. 已发现的私有入口（未全部验证）

### 原生桌面 `com.heytap.tv.launcher`

APK：`/system/app/OptvLauncher2/OptvLauncher2.apk`
当前状态：`enabled=3`（`COMPONENT_ENABLED_STATE_DISABLED_USER`，被用户禁用）
HOME Activity：`com.oneplus.tv.launcher2.HomeLauncher`

“全部应用”页面有两个对外入口：

```
Activity: com.oneplus.tv.launcher2.activity.NewAllAppActivity
  filter 1: action=android.intent.action.VIEW, scheme=optv,
            authority=com.oneplus.tv.launcher2, path prefix=/appList
  filter 2: action=com.oneplus.tv.action.myapp
```

> 重新 `pm enable com.heytap.tv.launcher` 会让 HOME 解析变成 `android`
> （ResolverActivity 选择器），即**会抢默认桌面**，需谨慎。

### 其他厂商 URI / 动作

```
optv://com.oneplus.tv.launcher2/appList          → NewAllAppActivity
optv://com.oneplus.tv.launcher2/recomFilmActivity
com.oneplus.tv.action.myapp                      → NewAllAppActivity
com.oneplus.tv.launcher2.action.start            → HomeLauncher (HOME)
com.heytap.tv.intent.action.RECENTS_APP          → 最近任务（HOME 长按触发）
com.heytap.tv.support.action.SHOW_SOURCE_LIST    → 信号源列表
```

---

## 6. root 之后能否改这块机制

### 可以改，但代价与风险都高

关键点：**拦截发生在 `system_server` 进程里的 `services.jar`**，不在应用层。
要改就必须替换系统框架代码。

### 方案 A：Patch `PhoneWindowManager`（最直接）

目标：删除索引 212–218 中把 133 并入 HOME 分支的那两条 `move`/`goto`。

步骤（概念）：
1. root 后 `adb pull /system/framework/services.jar`
2. 解包 `classes.dex`，改 `PhoneWindowManager.interceptKeyBeforeDispatching`
   的字节码（把 `if-ne v7, v0, +008h` 改为无条件跳转到“未处理”分支）
3. 重新打包 dex（smali → dex）
4. 处理 **APK/JAR 签名校验**：`services.jar` 通常被 `verify` 校验，
   Android 9 的 `system_server` 依赖 boot classpath 校验；
   若固件启用了 `dm-verity` / AVB，未签名改动会导致**无法开机**
5. 替换 `/system/framework/services.jar`，重启

**风险：**
- `dm-verity` / AVB → 改 `/system` 直接**变砖**，除非先 `adb disable-verity`
- 即使禁用 verity，改坏 `services.jar` → `system_server` 崩溃 → **bootloop**
- 需要保留原文件备份，并具备 recovery / fastboot 恢复能力
- OTA 升级会覆盖回去

### 方案 B：替换整个 `services.jar` 为 AOSP 版本

不可行。厂商定制（`CustomPWM`、`CVTE`、`optvjar`）散落在 `services.jar` 里，
换成 AOSP 版本会丢失大量 TV 功能（画中画、信号源、遥控器配对等）。

### 方案 C：只改 keylayout（最小改动，但不解决本问题）

`/system/usr/keylayout/Generic.kl` 把 `key 61 F3` 改成
`key 61 APPSELECT`（或自定义 keycode）。

**但这不解决问题**：`PhoneWindowManager` 的并轨是按 **keyCode 133** 判断的。
改成别的 keyCode 后，F3 分支不再命中，
但该 keyCode 会走“正常派发”路径——**理论上反而能到达 App**。

这条路的优点是改动极小（一个文本文件）、可回滚（备份 + recovery 覆盖）。
缺点是仍需 remount `/system`（要 root + 处理 verity），且
改了 keylayout 会影响所有使用同一 `Generic.kl` 的输入设备
（可改为按 Vendor/Product ID 建专属 `.kl` 来隔离，遥控器为 `vid=0x2b31 pid=0x6891`）。

> **这是 root 后最值得先试的方案**：新建
> `/system/usr/keylayout/Vendor_2b31_Product_6891.kl`，
> 复制 `Generic.kl` 内容并把 `key 61` 改为 `APPSELECT`，
> 不影响其他设备，且无需改 `services.jar`。

### 方案 D：root 后用 `Xposed`/`LSPosed` Hook（若可用）

Hook `PhoneWindowManager.interceptKeyBeforeDispatching`，
在 keyCode==133 时返回 `0`（继续派发）。

前提：该 Android 9 TV 上能装 LSPosed/EdXposed，
且 `system_server` 可被 hook。比改 jar 安全得多（可随时卸载恢复），
但依赖 Xposed 框架在该固件上可用，**未验证**。

### 方案 E：不改系统，绕过按键

完全停留在应用层，无风险：

1. **Menu 键**（82，可正常收到）作为应用栏入口。
   现状：`LauncherScreen.kt:729` 已用 Menu 打开设置，可改为应用栏 / 二级菜单。
2. **屏幕内 Tab**（CinePath 风格），方向键切换“首页 / 应用”。
3. 监听 `com.heytap.tv.intent.action.RECENTS_APP` 广播——
   该广播由系统在三方固件里发出，**第三方 App 有机会注册接收**（未验证是否 exported）。
   若可行，可用 HOME **长按**触发应用栏。

---

## 7. 建议的推进顺序（若将来 root）

1. 先做 **方案 E**（零风险，立刻可用）
2. root 后先试 **方案 D**（LSPosed hook，可逆）
3. 不可行再试 **方案 C**（专属 `.kl` 文件，改 keylayout）
4. 最后才考虑 **方案 A**（patch `services.jar`，风险最高）

---

## 8. 复现命令

```sh
D=192.168.1.213:5555

# 列出输入设备与能力
adb -s $D shell getevent -lp

# 实时抓按键（必须用 -t 分配 pty，否则输出被块缓冲，写不出来）
adb -s $D shell -t getevent -lt /dev/input/event2

# 按键拦截日志
adb -s $D shell logcat -v time | grep -E "keyCode=133|CustomPWM|interceptKeyT"

# 拉取系统框架并反编译
adb -s $D pull /system/framework/services.jar
mkdir x && cd x && unzip -o ../services.jar classes.dex
# 用 androguard 读取指定方法字节码
```

> 注意：`getevent` 的输出接到管道时会块缓冲，
> adb shell 退出后后台进程也会被杀。用 `tmux` + `shell -t` 才能稳定录制。
