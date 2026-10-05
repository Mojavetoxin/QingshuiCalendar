E 方案（原图直出）
=================
背景层用位图（这个目录里的 PNG，按密度放好即可），前景层留空。

启用方法：
  1. 把 mipmap-* 五个目录复制到 app/src/main/res/ 下（覆盖同名目录里的 ic_launcher_background.png，
     原本没有这些 png 文件，所以是新增，不影响其它资源）
  2. app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml 与 ic_launcher_round.xml 里
     把 <background android:drawable="@drawable/ic_launcher_background" />
     改成 <background android:drawable="@mipmap/ic_launcher_background" />
  3. 把 <foreground ...> 改成一个全透明的矢量（否则会盖住照片）：
     新建 app/src/main/res/drawable/ic_launcher_foreground.xml 内容为
     <vector ... android:viewportWidth="108" android:viewportHeight="108" />
     （只留根标签，不画任何东西）

注意：位图背景不支持 Android 13 的「主题图标」单色模式，那种情况会退回单色层。
