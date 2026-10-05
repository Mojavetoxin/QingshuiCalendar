# 清水日历 · 应用图标备选方案

工程里现在保留 **3 套**：**B 已启用**，A 与 E 备用。
（C / D / F 三套是设计探索阶段的方案，已从工程移除，过程记录保留在预览页 `清水日历-图标方案预览.html`。）

## 当前与备用

| 代号 | 名称 | 状态 | 一句话 |
|---|---|---|---|
| **B** | 青滴日历 | **启用中** | 白色日历页 + 青色表头 + 两个绑环 + 一颗饱满水滴 |
| A | 水泡晶莹 | 备用 | 深蓝底上一颗透明水泡，泡里浮着一页白色日历 |
| E | 原图直出 | 备用 | 直接把那张水泡照片当图标（位图，各密度已备好） |

两者的取舍：**B 的小尺寸辨识度最高**（32px 仍然一眼是日历），
**A 更贴合参考图的水泡氛围**但小尺寸下细节会糊一些。

## 换成 A 方案（矢量，两步）

```bash
# 在仓库根目录执行
cp design/icons/A_background.xml app/src/main/res/drawable/ic_launcher_background.xml
cp design/icons/A_foreground.xml app/src/main/res/drawable/ic_launcher_foreground.xml
cp design/icons/monochrome_A.xml app/src/main/res/drawable/ic_launcher_monochrome.xml
```

改完提交、推送，Actions 重新打包即可。

**换回 B** 就反过来拷 `B_*` 与 `monochrome_B.xml`。

## E 方案（位图）怎么装

照片没法做成矢量，所以 E 用位图背景层。步骤见 `E/README.txt`，要点是：

1. 把 `E/mipmap-*` 五个目录复制进 `app/src/main/res/`
2. `mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 里
   把 `@drawable/ic_launcher_background` 改成 `@mipmap/ic_launcher_background`
3. 把 `ic_launcher_foreground.xml` 清空成一个不画东西的空 `<vector>`

> 位图背景在 Android 13 的「主题图标」模式下不生效，那种情况会退回单色层。

## 文件说明

| 文件 | 作用 |
|---|---|
| `B_background.xml` / `B_foreground.xml` | **当前启用**的图标（背景层 / 前景层） |
| `A_background.xml` / `A_foreground.xml` | A 方案备用 |
| `monochrome_B.xml` | **当前启用**的单色层（Android 13 主题图标） |
| `monochrome_A.xml` | A 方案的单色层 |
| `E/mipmap-*/ic_launcher_background.png` | E 方案各密度位图（108 / 162 / 216 / 324 / 432） |

图标按安卓自适应图标规范绘制：**108dp 画布、中央 72dp 安全区**，
桌面上真正看到的只有安全区，外圈 18dp 是出血区（图标放大或视差时才会露出来）。

---

整理：水源
