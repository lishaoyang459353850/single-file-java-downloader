# PyDownload (Java 单文件版)

仿 `pydownload-v2.py` 的 Java 单文件多线程下载器（IDM 风格），**仅一个 Java 源文件**，零外部依赖，纯 JDK 标准库。

## 编译 / 运行（需 JDK 8+）

```powershell
.\build.ps1          # 编译并打包为可执行 PyDownload.jar
.\build.ps1 -Run     # 编译打包后直接运行
.\build.ps1 -Clean   # 清理 build/ 与旧 JAR
# 或手动:
javac -encoding UTF-8 PyDownload.java
jar cfe PyDownload.jar PyDownload PyDownload.class
java -jar PyDownload.jar
```

脚本会自动检测 `JAVA_HOME` 或 PATH 中的 `javac`；缺 JDK 会明确报错。

## 功能

| 功能 | 实现 |
|---|---|
| 8 线程锁定分片下载 | `Executors` + `Range: bytes=`，不支持 Range 自动降级单线程；线程数锁定 8 避免分片错乱导致文件损坏 |
| 断点续传 | 下载目录生成 `.pydlpart`(数据)+`.pydlmeta`(续传点)，中断后可继续，完成后自动重命名 |
| 总进度 + 8 线程进度 | 总进度条 + 8 个线程进度条，200ms 刷新 |
| 实时速度 | 累计字节/耗时，B/s·KB/s·MB/s 自动切换 |
| 自动解析文件名/扩展名 | 优先 `Content-Disposition`，其次 URL 路径，无扩展名时按 `Content-Type` 推断 |
| DNS 临时代理 | 设置面板→网络/DNS，可填首选/备选 DNS；提供 **Cloudflare 1.1.1.1 / Baidu 180.76.76.76 / Google 8.8.8.8 / 114DNS 114.114.114.114** 一键填入 |
| HTTP/SOCKS 代理 | 设置面板启用，立即生效（SOCKS 让 DNS 走代理远端解析） |
| 超时弹窗 | 连接/读取超时默认 15s，`JOptionPane` 弹窗并提示"可配置 DNS/代理后重试" |
| INI 自动生成 | 首次运行创建 `settings.ini`（代理/DNS/线程/超时/保存目录/自动打开目录/日志级别） |
| 完备日志 | `java.util.logging` → `download.log`（含时间戳），主界面日志面板实时显示 |
| 垃圾清理 | 设置面板→维护：一键清理 `.pydlpart`/`.pydlmeta` 临时文件 + 清空日志 |
| 下载完自动打开目录 | 默认开启，调用 `Desktop.getDesktop().open(目录)` 跨平台打开保存目录 |
| 设置面板 | 集成 DNS / 代理 / 下载 / 维护(清理+日志) 三个标签页 |

## 文件清单

- `PyDownload.java` — 唯一源码文件（约 495 行，含 `Config`/`Log`/`Net`/`Downloader`/`MainWindow`/`SettingsDlg` 内部类）
- `build.ps1` — PowerShell 编译打包脚本
- `README.md` — 本文件
