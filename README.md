# single-file-java-downloader
Single-file Java multithreaded download manager (IDM-style) with 8-thread chunked download, resume support, DNS/proxy, auto filename detection, logging, and temp-file cleanup. Zero dependencies, pure JDK + Swing.
PyDownload (Java Single-File Edition)

A Java single-file multi-threaded downloader (IDM style) inspired by pydownload-v2.py. Only one Java source file, zero external dependencies, pure JDK standard library.

Build / Run (Requires JDK 8+)

.\build.ps1          # Compile and package as executable PyDownload.jar
.\build.ps1 -Run     # Compile, package and run directly
.\build.ps1 -Clean   # Clean build/ and old JAR
# Or manually:
javac -encoding UTF-8 PyDownload.java
jar cfe PyDownload.jar PyDownload PyDownload.class
java -jar PyDownload.jar


The script automatically detects javac from JAVA_HOME or PATH; it will report a clear error if JDK is missing.

Features

Feature Implementation

8-thread locked chunked download Executors + Range: bytes=, falls back to single-thread when Range is unsupported; thread count fixed at 8 to avoid chunk misalignment and file corruption

Resume broken downloads Creates .pydlpart (data) + .pydlmeta (resume offset) in download directory; continues from breakpoint after interruption, automatically renames upon completion

Total progress + 8 thread progress One total progress bar + 8 individual thread bars, refreshed every 200ms

Real-time speed Accumulated bytes / elapsed time, auto-switches between B/s, KB/s, MB/s

Auto filename/extension detection Priority: Content-Disposition → URL path tail; if still no extension, infers from Content-Type

DNS temporary proxy Settings panel → Network/DNS tab; allows primary/backup DNS entries; provides one-click fill for Cloudflare 1.1.1.1, Baidu 180.76.76.76, Google 8.8.8.8, 114DNS 114.114.114.114

HTTP/SOCKS proxy Enable in settings panel, takes effect immediately (SOCKS resolves DNS via remote proxy)

Timeout popup Connection/read timeout defaults to 15s; shows JOptionPane popup with suggestion "configure DNS/proxy and retry"

Auto-generated INI config Creates settings.ini on first run (proxy, DNS, threads, timeout, save directory, auto-open folder, log level)

Comprehensive logging java.util.logging → download.log (with timestamps); real-time log panel in main window

Garbage cleanup Settings panel → Maintenance tab: one-click clean .pydlpart / .pydlmeta temp files + clear logs

Auto-open download folder Enabled by default; calls Desktop.getDesktop().open(dir) to open the save directory cross-platform

Settings panel Three tabs integrated: Network/DNS, Download, Maintenance (cleanup + log)

Files

• PyDownload.java — The only source file (~495 lines, contains inner classes: Config, Log, Net, Downloader, MainWindow, SettingsDlg)

• build.ps1 — PowerShell build & package script

• README.md — This file
