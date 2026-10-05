# 清水日历 · 应用图标备选方案

这里的六套图标**随时可替换**，不用改代码逻辑，只换资源文件。

当前工程里**正在使用 A 方案（水泡晶莹）**。

## 六套方案

| 代号 | 名称 | 一句话 | 小尺寸辨识度 | 与参考图的契合 |
|---|---|---|---|---|
| **A** | 水泡晶莹 | 深蓝底上一颗透明水泡，泡里浮着一页白色日历 | ★★★★★ | ★★★★★ |
| B | 青滴日历 | 白色日历页 + 青色表头 + 一颗饱满水滴 | ★★★★★ | ★★★☆☆ |
| C | 一滴涟漪 | 一滴水落进水面，泛开三圈涟漪 | ★★★★☆ | ★★★★☆ |
| D | 水泡与小野 | 复刻参考图构图：大水泡 + 底下举黄伞的小人 | ★★★☆☆ | ★★★★★ |
| E | 原图直出 | 直接用那张水泡照片当图标 | ★★★☆☆ | ★★★★★ |
| F | 浅色水环 | 米白底 + 青色圆环 + 环中一颗水滴 | ★★★★★ | ★★★☆☆ |

## 怎么换成别的方案

矢量方案（A / B / C / D / F）只有两步：

```bash
# 假设要换成 B 方案（在仓库根目录执行）
cp design/icons/B_background.xml app/src/main/res/drawable/ic_launcher_background.xml
cp design/icons/B_foreground.xml app/src/main/res/drawable/ic_launcher_foreground.xml
```

改完提交、推送，Actions 重新打包即可。

想恢复 A 方案就反过来拷 `A_background.xml` / `A_foreground.xml`。

## E 方案（位图）怎么装

照片没法做成矢量，所以 E 用的是位图背景层。步骤见 `E/README.txt`，要点是：

1. 把 `E/mipmap-*` 五个目录复制进 `app/src/main/res/`
2. `mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 里
   把 `@drawable/ic_launcher_background` 改成 `@mipmap/ic_launcher_background`
3. 把 `ic_launcher_foreground.xml` 清空成一个不画东西的空 `<vector>`

> 位图背景在 Android 13 的「主题图标」模式下不生效，那种情况会退回单色层。

## 文件说明

- `*_background.xml` —— 自适应图标的**背景层**（108×108 视口，整幅铺满）
- `*_foreground.xml` —— **前景层**（内容放在中央 72×72 安全区内，外圈 18 是出血区）
- `monochrome.xml` —— **单色层**（Android 13 主题图标用）
- `E/mipmap-*/ic_launcher_background.png` —— E 方案各密度位图

设计要点：所有方案都按安卓自适应图标规范绘制，108dp 画布、中央 72dp 安全区；
桌面上真正看到的只有安全区，外圈出血区会在图标放大/视差时露出来。

---

整理：水源
