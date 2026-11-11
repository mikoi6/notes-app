# 便签 · Notes

一个手机便签 App。网页版是**单文件 HTML**，无依赖、无后端、不用构建；安卓版是一个 41 KB 的 WebView 壳，把同一份页面装进去。

数据全部存在本机 `localStorage`，不上传任何服务器。

**[▸ 在线试用](https://mikoi6.github.io/notes-app/)**（手机浏览器打开后可「添加到主屏幕」）　·　**[▸ 下载 APK](https://github.com/mikoi6/notes-app/releases/latest)**

<img src="screenshots/home.png" width="300" alt="便签列表">

## 功能

### 文件夹：无限层级

2 列卡片浏览，进文件夹后显示子文件夹 + 本层便签。另有一个树视图，用缩进表达完整层级，每行显示**递归**便签数。

| 文件夹内 | 文件夹树 |
| --- | --- |
| ![](screenshots/folder.png) | ![](screenshots/tree.png) |

- 新建 / 重命名 / 移动 / 删除；删除父级会连子孙一起删，删前告诉你有多少条受牵连
- 移动时自动禁用**自己和自己的所有后代**，防止把子树拖进自己里面把树弄断

数据结构上「无限层级」不需要任何特殊处理 —— 扁平数组 + 单指针：

```js
folder = { id, name, parentId, color, createdAt }   // parentId 为 null 即根目录
note   = { id, folderId, title, content, tags, pinned, order, createdAt, updatedAt }
```

所以「移动文件夹」只是改一个 `parentId`，树视图靠递归渲染出来。

### 标签、置顶、拖拽排序

- 标签支持逗号批量输入（`工作, 紧急` 一次建两个），卡片上前 3 个可见，多的折叠成 `+N`
- 顶部标签条是全库统计的，点任意标签跳到**跨文件夹**的筛选视图
- 长按卡片约 0.4 秒进入拖拽，卡片浮起、其它卡片让位、松手保存

排序优先级是 **置顶 > 手动顺序 > 最近更新**。把普通便签拖到置顶便签上方，松手后会弹回置顶组下面 —— 这是有意的，置顶就该永远在最前。

### 搜索：列表级 + 便签内

列表搜索同时匹配标题、正文、标签，结果里的关键词高亮，而且**摘要是从命中处截取的**（前后各留一点上下文），不是永远从正文开头截一段。

| 搜索结果 | 便签内查找 |
| --- | --- |
| ![](screenshots/search.png) | ![](screenshots/find.png) |

便签内查找这里有个技术限制：`<textarea>` 自身无法给部分文字加高亮。做法是进入查找时，把正文换成一层**只读高亮视图**，字号、行高、左右内边距、`white-space: pre-wrap` 都与正文完全对齐，所以切换时看不出跳动。当前命中是实心橙底，右侧显示 `2/3` 进度，上下箭头循环跳转；点「完成」回到编辑，**光标停在刚才那个命中上**，可以直接改。

### Markdown

编辑器边写边预览，右上角眼睛图标切换。解析器是手写的（约 90 行，无依赖），支持标题、有序/无序列表、引用、代码块、行内代码、粗斜体、删除线、链接、分割线。**先转义再解析**，XSS 用例验证过。

| 编辑 | 预览 |
| --- | --- |
| ![](screenshots/editor.png) | ![](screenshots/preview.png) |

### 导出：四种形态

右上角 `⋯` → 导出与备份。

| 形态 | 说明 |
| --- | --- |
| 单条便签 | TXT / Markdown / HTML，HTML 会把正文渲染成排版（适合直接发给别人） |
| 全量单文件 | TXT / Markdown / HTML，多用于存档和打印 |
| **按文件夹结构** | 一条便签一个文件，目录层级照搬，见下 |
| JSON 备份 | 完整数据，可导回来（id 重映射后合并，不覆盖现有数据） |

<img src="screenshots/export.png" width="300" alt="导出菜单">

「按文件夹结构」导出的结果：

```
便签-20261112-0238/
├── 本周待办.md
└── 工作/
    ├── 性能优化记录.md
    ├── 空壳/                        ← 空文件夹也保留
    └── 周报/
        └── 第 46 周/
            └── 第 46 周周报.md
```

- **正文就是便签正文**，不重复写标题、不塞「位置：工作 / 周报」——目录本身就是位置。只有正文里没有一级标题时才补一个 `# 标题`
- 文件修改时间 = 便签最后编辑时间，解压后按时间排序就是你的时间线
- 同目录重名自动变 `重名 (2).md`；文件夹名里的 `/ : * ?` 替换成 `_`（不清洗的话，叫「工作/周报」的文件夹会被拆成两层）

投递有两条路径：**安卓上直接建真实目录**（写进 `下载/便签-…/工作/周报/`，文件管理器点进去就是多层文件夹）；**浏览器上打包成 ZIP**。ZIP 是手写编码器（约 90 行，仅存储不压缩），关键点是给文件名打 UTF-8 标记位，否则中文名解压会变乱码。

### 深色模式

跟随系统，无手动开关。

<img src="screenshots/dark.png" width="300" alt="深色模式">

## 安卓版怎么做的

没有用 Capacitor / Cordova，手写了一个纯 Java 的 WebView 壳，不依赖 androidx，所以 APK 只有 41 KB。

三个值得说的点：

**1. 不用 `file:///android_asset`，改用虚拟域名。** `shouldInterceptRequest` 把 `https://notes.local/` 映射到 assets。原因是 `file://` 协议下 `localStorage` 在不同 WebView 版本行为不一致，而 `https://notes.local` 的 origin 稳定，数据不会莫名其妙消失。

**2. 导出走原生通道。** WebView 里 `<a download>` 基本没用，所以 JS 检测到 `AndroidBridge` 就把内容交给原生：单文件用 `MediaStore` 写「下载/便签」，目录树则逐个 insert、中间目录由 MediaStore 自动带出来（Android 10+）；Android 8/9 退回应用私有目录 + `mkdirs`。

**3. 返回键交给网页。** 按返回键时调 `androidBack()`，页面依次关弹窗 → 关查找 → 关编辑器 → 退搜索 → 退标签筛选 → 回上级文件夹，都处理完了才退出 App。

`minSdk 26`（Android 8.0+），`targetSdk 34`，图标是 vector 画的便签纸，没有 PNG。

## 目录结构

```
.
├── index.html                 网页版，同时也作为 APK 里的页面
├── 便签.apk                   已构建好的安装包
├── build-apk.sh               改完页面后一键重打包
├── screenshots/               README 用的截图
└── android/
    ├── app/src/main/
    │   ├── java/com/notes/app/MainActivity.java   WebView 壳 + JS 桥 + 文件写入
    │   ├── assets/index.html                      构建时自动从根目录同步
    │   └── res/                                   图标、主题
    ├── build.gradle
    └── settings.gradle
```

## 本地运行

网页版不需要任何环境：

```bash
# 方式一：直接双击 index.html
# 方式二：起个静态服务（推荐，某些浏览器对 file:// 的 localStorage 有限制）
python -m http.server 8000
```

## 重新打包 APK

`build-apk.sh` 会把根目录的 `index.html` 同步进 `android/app/src/main/assets/` 再构建，输出覆盖根目录的 `便签.apk`。

它从 `.toolchain/` 读工具链，需要这三样：

| 路径 | 内容 |
| --- | --- |
| `.toolchain/sdk-jdk` | JDK 17 |
| `.toolchain/sdk` | Android SDK：`platforms;android-34`、`build-tools;34.0.0` |
| `.toolchain/gradle-home/gradle-8.7` | Gradle 8.7 |

这三样是本地下载的（约 868 MB），没有进仓库 —— 用 `sdkmanager` 装好后放到对应路径，或改 `build-apk.sh` 顶部的三个变量指向你的环境即可。

**签名**：仓库里不放签名密钥（`android/keystore/`、`*.jks` 都在 `.gitignore` 里）。自己生成一个：

```bash
keytool -genkeypair -v -keystore android/keystore/notes.jks \
  -alias notes -keyalg RSA -keysize 2048 -validity 10000
```

生成后把密码填进 `android/app/build.gradle` 的 `signingConfigs.release`（当前是硬编码的占位值）。注意签名变了，手机上要先卸载旧版才能装新版。

## 已知限制

- **数据在 `localStorage`**，换浏览器、清缓存、换设备都会丢。请在 `⋯` → 备份为 JSON 里定期导出
- **Android 10+ 无法只创建一个空文件夹**，只能靠写入文件顺带带出来。所以按文件夹结构导出时，空的子文件夹会被略过，完成提示里会写明略过了几个
- **APK 未在真机验证过**。构建、签名、lint、页面 md5 一致性都过了，但实际的触感和 WebView 兼容性需要装到手机上确认
- **自签名证书**，只适合自己安装。上应用商店需要正式签名、隐私政策、跟进最新 targetSdk
- 没有云同步。单机应用，这是设计取舍不是遗漏

## 版本

- `v1.0.0` — 首个版本：文件夹树、标签、置顶拖拽、Markdown、搜索与查找高亮、单条/全量/按目录导出、JSON 备份、安卓 APK
