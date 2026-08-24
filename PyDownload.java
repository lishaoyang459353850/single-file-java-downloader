import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.*;

/**
 * PyDownload - Java 单文件多线程下载器 (IDM 风格)
 * 对应 Python 项目 pydownload-v2.py。
 * 功能：8 线程锁定分片下载、断点续传、总/线程进度、实时速度、
 *       DNS 临时代理、超时弹窗、INI 配置(自动生成)、完备日志、
 *       垃圾清理、下载完自动打开目录、设置面板(集成 DNS/清理/日志)、
 *       推荐 DNS(Cloudflare/Baidu/Google/114)、原生 Java(无扩展)。
 */
public class PyDownload {

    // ===================== 常量 =====================
    private static final String VERSION = "2.0.0";
    private static final String APP = "PyDownload";
    private static final int THREADS = 8;          // 锁定 8 线程
    private static final int READ_BUF = 256 * 1024; // 256KB
    private static final int MIN_CHUNK = 512 * 1024; // 小于此不分片
    private static final int TIMEOUT_MS = 15000;
    private static final String CONFIG_FILE = "settings.ini";
    private static final String LOG_FILE = "download.log";
    private static final String PART_EXT = ".pydlpart";

    // ===================== 入口 =====================
    public static void main(String[] a) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new MainWindow().init());
    }

    // ===================== 配置(INI) =====================
    static class Config {
        final Properties p = new Properties();
        Config() {
            File f = new File(CONFIG_FILE);
            if (!f.exists()) {
                p.setProperty("proxy.enabled", "false");
                p.setProperty("proxy.host", "");
                p.setProperty("proxy.port", "1080");
                p.setProperty("proxy.type", "socks"); // socks / http
                p.setProperty("dns.primary", "");
                p.setProperty("dns.secondary", "");
                p.setProperty("download.threads", String.valueOf(THREADS));
                p.setProperty("download.timeout", String.valueOf(TIMEOUT_MS));
                p.setProperty("download.saveDir", new File(System.getProperty("user.home"), "Downloads").getAbsolutePath());
                p.setProperty("download.autoOpenDir", "true");
                p.setProperty("log.level", "INFO");
                p.setProperty("log.maxKB", "2048");
                save();
            } else {
                try (InputStream in = new FileInputStream(f)) { p.load(in); }
                catch (IOException e) { Log.logErr("读取配置失败", e); }
            }
        }
        String get(String k, String d) { return p.getProperty(k, d); }
        int getInt(String k, int d) { try { return Integer.parseInt(p.getProperty(k)); } catch (Exception e) { return d; } }
        boolean getBool(String k, boolean d) { return "true".equalsIgnoreCase(p.getProperty(k, String.valueOf(d))); }
        void set(String k, String v) { p.setProperty(k, v); save(); }
        void save() { try (OutputStream out = new FileOutputStream(CONFIG_FILE)) { p.store(out, APP + " " + VERSION + " configuration"); } catch (IOException e) { Log.logErr("保存配置失败", e); } }
    }

    // ===================== 日志 =====================
    static class Log {
        private static final Logger L = Logger.getLogger(APP);
        private static MainWindow ui;
        static { initFile(); }
        static void setUI(MainWindow w) { ui = w; }
        private static void initFile() {
            try {
                FileHandler fh = new FileHandler(LOG_FILE, 2 * 1024 * 1024, 1, true);
                fh.setFormatter(new SimpleFormatter() {
                    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    @Override public String format(LogRecord r) { return sdf.format(new Date(r.getMillis())) + " [" + r.getLevel() + "] " + r.getMessage() + "\n"; }
                });
                L.addHandler(fh); L.setLevel(Level.INFO);
            } catch (IOException e) { System.err.println("日志初始化失败: " + e.getMessage()); }
        }
        static void info(String m) { L.info(m); uiLog(m); }
        static void warn(String m) { L.warning(m); uiLog("WARN " + m); }
        static void err(String m) { L.severe(m); uiLog("ERROR " + m); }
        static void logErr(String ctx, Throwable t) { L.severe(ctx + ": " + t); uiLog("ERROR " + ctx + ": " + t.getMessage()); }
        private static void uiLog(String m) { if (ui != null) ui.appendLog(m); }
    }

    // ===================== DNS / 代理 =====================
    static class Net {
        /** 应用 DNS(通过系统属性 + InetAddress 预热)，返回是否生效 */
        static boolean applyDnsFromConfig(Config c) {
            String p = c.get("dns.primary", "").trim();
            if (p.isEmpty()) return false;
            System.setProperty("sun.net.spi.nameservice.provider.1", "dns,sun");
            System.setProperty("sun.net.spi.nameservice.nameservers", p);
            return true;
        }
        static void applyProxyFromConfig(Config c) {
            if (!c.getBool("proxy.enabled", false)) { clearProxy(); return; }
            String h = c.get("proxy.host", "").trim();
            String t = c.get("proxy.type", "socks");
            if (h.isEmpty()) return;
            int port = c.getInt("proxy.port", 1080);
            if ("socks".equalsIgnoreCase(t)) {
                System.setProperty("socksProxyHost", h);
                System.setProperty("socksProxyPort", String.valueOf(port));
            } else {
                System.setProperty("http.proxyHost", h);
                System.setProperty("http.proxyPort", String.valueOf(port));
                System.setProperty("https.proxyHost", h);
                System.setProperty("https.proxyPort", String.valueOf(port));
            }
            System.setProperty("java.net.useSystemProxies", "true");
            Log.info("代理已启用: " + t + " " + h + ":" + port);
        }
        static void clearProxy() {
            for (String k : new String[]{"socksProxyHost","socksProxyPort","http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort"})
                System.clearProperty(k);
        }
    }

    // ===================== 下载核心 =====================
    static class Downloader {
        interface Listener { void onProgress(long total, long done, double speedBps, int[] perThreadPct); void onComplete(File f); void onError(String msg); }
        final String url; final File target; final Config cfg; final Listener lis;
        volatile boolean cancelled = false;
        Downloader(String url, File target, Config cfg, Listener lis) { this.url = url; this.target = target; this.cfg = cfg; this.lis = lis; }

        void start() { new Thread(this::run, "DL-Main").start(); }
        void cancel() { cancelled = true; }

        private void run() {
            try {
                int timeout = cfg.getInt("download.timeout", TIMEOUT_MS);
                // 探测
                HttpURLConnection probe = (HttpURLConnection) new URL(url).openConnection();
                probe.setRequestMethod("GET"); probe.setConnectTimeout(timeout); probe.setReadTimeout(timeout);
                probe.setRequestProperty("Connection", "keep-alive"); probe.setRequestProperty("Accept", "*/*");
                probe.connect();
                long size = probe.getContentLengthLong();
                boolean range = "bytes".equalsIgnoreCase(probe.getHeaderField("Accept-Ranges"));
                String ct = probe.getHeaderField("Content-Type");
                probe.disconnect();
                if (size <= 0) range = false;

                File part = new File(target.getAbsolutePath() + PART_EXT);
                File meta = new File(target.getAbsolutePath() + ".pydlmeta");
                // 断点续传：读取已下载量
                long resumeFrom = 0;
                if (range && part.exists() && meta.exists()) {
                    try { resumeFrom = Long.parseLong(new String(Files.readAllBytes(meta.toPath())).trim()); }
                    catch (Exception ignored) { resumeFrom = 0; }
                    if (resumeFrom >= size) resumeFrom = 0; // 已完成或异常，重下
                } else { safeDelete(part); safeDelete(meta); }
                final long RESUME = resumeFrom;

                if (range && size > 0) {
                    long usable = size - RESUME;
                    int chunks = (usable > MIN_CHUNK * THREADS) ? THREADS : Math.max(1, (int)(usable / MIN_CHUNK));
                    long chunk = (long) Math.ceil((double) usable / chunks);
                    long[][] ranges = new long[chunks][2];
                    for (int i = 0; i < chunks; i++) {
                        ranges[i][0] = RESUME + i * chunk;
                        ranges[i][1] = (i == chunks - 1) ? (size - 1) : (RESUME + (i + 1) * chunk - 1);
                        if (ranges[i][0] >= size) ranges[i][0] = ranges[i][1] = -1;
                    }
                    Log.info("开始下载: " + size + " 字节, 支持Range, " + chunks + "/" + THREADS + " 线程(从 " + RESUME + " 续传)");
                    downloadMulti(range, size, RESUME, part, meta, ranges, timeout, chunks);
                } else {
                    Log.info("开始下载: " + (size > 0 ? size + " 字节" : "未知大小") + (range ? "" : ", 不支持Range(单线程)"));
                    downloadSingle(size, part, timeout);
                }
                if (cancelled) { Log.warn("下载已取消"); return; }
                safeDelete(meta);
                if (!part.renameTo(target)) { throw new IOException("重命名临时文件失败: " + part + " -> " + target); }
                Log.info("下载完成: " + target + " (" + target.length() + " 字节)");
                lis.onComplete(target);
            } catch (Exception e) {
                Log.logErr("下载失败", e);
                lis.onError(e.getMessage());
            }
        }

        private void downloadMulti(boolean range, long size, long resume, File part, File meta, long[][] ranges, int timeout, int activeThreads) {
            AtomicLong done = new AtomicLong(resume);
            int[] pct = new int[THREADS];
            ExecutorService ex = Executors.newFixedThreadPool(activeThreads);
            CountDownLatch latch = new CountDownLatch(activeThreads);
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < activeThreads; i++) {
                final int idx = i; final long s = ranges[i][0]; final long e = ranges[i][1];
                ex.submit(() -> {
                    try {
                        if (s < 0 || cancelled) { latch.countDown(); return; }
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setRequestProperty("Connection", "keep-alive"); c.setRequestProperty("Accept", "*/*");
                        c.setRequestProperty("Range", "bytes=" + s + "-" + e);
                        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
                        try (InputStream in = c.getInputStream(); RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                            byte[] buf = new byte[READ_BUF];
                            long off = s, rem = e - s + 1, chunkTotal = rem;
                            while (rem > 0 && !cancelled) {
                                int r = in.read(buf, 0, (int) Math.min(buf.length, rem));
                                if (r <= 0) break;
                                synchronized (raf) { raf.seek(off); raf.write(buf, 0, r); }
                                off += r; rem -= r; done.addAndGet(r);
                                pct[idx] = (int) ((off - s) * 100 / chunkTotal);
                                // 持久化续传点
                                try { Files.write(meta.toPath(), String.valueOf(off).getBytes()); } catch (IOException ignored) {}
                            }
                        }
                        c.disconnect();
                    } catch (Exception ex1) { Log.warn("分片" + idx + "异常: " + ex1.getMessage()); }
                    finally { latch.countDown(); }
                });
            }
            // 进度刷新
            new Thread(() -> {
                while (!cancelled && latch.getCount() > 0) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    long d = done.get();
                    double sp = (System.currentTimeMillis() - t0) > 0 ? d / ((System.currentTimeMillis() - t0) / 1000.0) : 0;
                    lis.onProgress(size, d, sp, pct.clone());
                }
            }, "DL-Progress").start();
            try { latch.await(); } catch (InterruptedException ie) { cancelled = true; }
            ex.shutdownNow();
            long d = done.get();
            double sp = (System.currentTimeMillis() - t0) > 0 ? d / ((System.currentTimeMillis() - t0) / 1000.0) : 0;
            lis.onProgress(size, d, sp, pct.clone());
        }

        private void downloadSingle(long size, File part, int timeout) {
            AtomicLong done = new AtomicLong(0); int[] pct = new int[THREADS]; pct[0] = 0;
            long t0 = System.currentTimeMillis();
            new Thread(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("Connection", "keep-alive"); c.setRequestProperty("Accept", "*/*");
                    c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
                    try (InputStream in = c.getInputStream(); RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                        byte[] buf = new byte[READ_BUF]; int r;
                        while ((r = in.read(buf)) > 0 && !cancelled) {
                            raf.write(buf, 0, r); done.addAndGet(r);
                            if (size > 0) pct[0] = (int)(done.get() * 100 / size);
                            lis.onProgress(size, done.get(), done.get()/Math.max(1,((System.currentTimeMillis()-t0)/1000.0)), pct.clone());
                        }
                    }
                    c.disconnect();
                } catch (Exception e) { Log.logErr("单线程下载异常", e); lis.onError(e.getMessage()); }
            }, "DL-Single").start();
            while (!cancelled && ((System.currentTimeMillis() - t0) < 500 || done.get() < size - 1 || size <= 0)) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                if (size > 0 && done.get() >= size) break;
            }
        }

        private void safeDelete(File f) { if (f.exists()) try { f.delete(); } catch (Exception ignored) {} }
    }

    // ===================== 主窗口 =====================
    static class MainWindow extends JFrame {
        private final Config cfg = new Config();
        private JTextField urlField;
        private JButton startBtn, settingsBtn, openDirBtn;
        private JProgressBar totalBar;
        private JProgressBar[] threadBars = new JProgressBar[THREADS];
        private JLabel speedLabel, sizeLabel, statusLabel;
        private JTextArea logArea;
        private Downloader current;
        private File lastTarget;

        void init() {
            Log.setUI(this);
            Net.applyDnsFromConfig(cfg); Net.applyProxyFromConfig(cfg);
            setTitle(APP + " " + VERSION + " - 多线程下载器");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(900, 700); setLocationRelativeTo(null);
            setLayout(new BorderLayout(8, 8));
            ((JComponent)getContentPane()).setBorder(new EmptyBorder(10,10,10,10));
            add(buildTop(), BorderLayout.NORTH);
            add(buildCenter(), BorderLayout.CENTER);
            add(buildBottom(), BorderLayout.SOUTH);
            appendLog(APP + " " + VERSION + " 启动。配置: " + new File(CONFIG_FILE).getAbsolutePath());
            setVisible(true);
        }

        private JPanel buildTop() {
            JPanel p = new JPanel(new BorderLayout(8,4));
            p.add(new JLabel("下载链接 URL:"), BorderLayout.NORTH);
            JPanel row = new JPanel(new BorderLayout(6,0));
            urlField = new JTextField(); urlField.setToolTipText("粘贴文件直链，程序会自动解析文件名与扩展名");
            startBtn = new JButton("开始下载"); startBtn.setBackground(new Color(0x2E7D32)); startBtn.setForeground(Color.WHITE);
            settingsBtn = new JButton("设置"); openDirBtn = new JButton("打开目录");
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            btns.add(openDirBtn); btns.add(settingsBtn); btns.add(startBtn);
            row.add(urlField, BorderLayout.CENTER); row.add(btns, BorderLayout.EAST);
            p.add(row, BorderLayout.CENTER);
            startBtn.addActionListener(e -> onStart());
            settingsBtn.addActionListener(e -> new SettingsDlg(this, cfg).setVisible(true));
            openDirBtn.addActionListener(e -> openSaveDir());
            return p;
        }

        private JPanel buildCenter() {
            JPanel wrap = new JPanel(new BorderLayout(0,8));
            JPanel info = new JPanel(new GridLayout(1,3,8,0));
            totalBar = new JProgressBar(0,100); totalBar.setStringPainted(true); totalBar.setForeground(new Color(0x1565C0));
            speedLabel = new JLabel("0 B/s"); speedLabel.setHorizontalAlignment(SwingConstants.CENTER);
            sizeLabel = new JLabel("大小: -"); sizeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
            info.add(labelBox("总进度", totalBar)); info.add(labelBox("速度", speedLabel)); info.add(labelBox("文件大小", sizeLabel));
            wrap.add(info, BorderLayout.NORTH);
            JPanel tg = new JPanel(new GridLayout(2, 4, 6, 6)); tg.setBorder(BorderFactory.createTitledBorder("8 线程进度"));
            for (int i = 0; i < THREADS; i++) { threadBars[i] = new JProgressBar(0,100); threadBars[i].setStringPainted(true); threadBars[i].setForeground(new Color(0x00897B)); tg.add(threadBars[i]); }
            wrap.add(tg, BorderLayout.CENTER);
            return wrap;
        }

        private JPanel buildBottom() {
            JPanel p = new JPanel(new BorderLayout(0,4));
            statusLabel = new JLabel("就绪"); statusLabel.setForeground(new Color(0x555555));
            logArea = new JTextArea(8, 60); logArea.setEditable(false); logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            JScrollPane sp = new JScrollPane(logArea); sp.setBorder(BorderFactory.createTitledBorder("日志"));
            p.add(statusLabel, BorderLayout.NORTH); p.add(sp, BorderLayout.CENTER);
            return p;
        }

        private JPanel labelBox(String t, JComponent c) { JPanel p = new JPanel(new BorderLayout(0,2)); p.add(new JLabel(t), BorderLayout.NORTH); p.add(c, BorderLayout.CENTER); return p; }

        void appendLog(String m) { SwingUtilities.invokeLater(() -> { logArea.append(m + "\n"); logArea.setCaretPosition(logArea.getDocument().getLength()); }); }

        private void onStart() {
            String url = urlField.getText().trim();
            if (url.isEmpty()) { JOptionPane.showMessageDialog(this, "请输入下载链接", "提示", JOptionPane.INFORMATION_MESSAGE); return; }
            if (!url.matches("(?i)^https?://.+")) { JOptionPane.showMessageDialog(this, "链接需以 http:// 或 https:// 开头", "提示", JOptionPane.WARNING_MESSAGE); return; }
            if (current != null) { JOptionPane.showMessageDialog(this, "已有下载任务在进行", "提示", JOptionPane.INFORMATION_MESSAGE); return; }
            String name = suggestName(url);
            JFileChooser fc = new JFileChooser(new File(cfg.get("download.saveDir", System.getProperty("user.home"))));
            fc.setSelectedFile(new File(fc.getCurrentDirectory(), name));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File target = fc.getSelectedFile();
            cfg.set("download.saveDir", target.getParent());
            startBtn.setEnabled(false); totalBar.setValue(0); for (JProgressBar b : threadBars) b.setValue(0); speedLabel.setText("0 B/s"); sizeLabel.setText("大小: -"); statusLabel.setText("连接中...");
            current = new Downloader(url, target, cfg, new Downloader.Listener() {
                @Override public void onProgress(long total, long done, double sp, int[] tp) { SwingUtilities.invokeLater(() -> { if (total > 0) totalBar.setValue((int)(done*100/total)); else totalBar.setIndeterminate(true); for (int i=0;i<Math.min(threadBars.length,tp.length);i++) threadBars[i].setValue(tp[i]); speedLabel.setText(formatSp(sp)); sizeLabel.setText("大小: "+(total>0?formatSize(total):"未知")); statusLabel.setText("下载中... "+formatSize(done)+(total>0?" / "+formatSize(total):"")); }); }
                @Override public void onComplete(File f) { SwingUtilities.invokeLater(() -> { totalBar.setValue(100); statusLabel.setText("完成: "+f.getName()); lastTarget = f; current=null; startBtn.setEnabled(true); Log.info("任务完成。"); if (cfg.getBool("download.autoOpenDir", true)) openSaveDir(); }); }
                @Override public void onError(String msg) { SwingUtilities.invokeLater(() -> { statusLabel.setText("错误"); current=null; startBtn.setEnabled(true); JOptionPane.showMessageDialog(MainWindow.this, "下载出错:\n"+msg+"\n\n提示: 可尝试在「设置」中配置 DNS 或代理后重试。", "下载错误", JOptionPane.ERROR_MESSAGE); }); }
            });
            current.start();
        }

        /** 从 URL/Content-Disposition 自动解析文件名与扩展名 */
        private String suggestName(String url) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET"); c.setConnectTimeout(cfg.getInt("download.timeout", TIMEOUT_MS)); c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("Accept", "*/*");
                String cd = c.getHeaderField("Content-Disposition");
                String name = null;
                if (cd != null) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)filename\\*?=\"?([^\";]+)").matcher(cd); if (m.find()) name = m.group(1).trim(); }
                if (name == null || name.isEmpty()) { String p = new URI(url).getPath(); name = p.substring(p.lastIndexOf('/')+1); if (name.isEmpty()) name = "download"; }
                // 去掉查询串
                int q = name.indexOf('?'); if (q>0) name = name.substring(0,q);
                if (!name.contains(".")) { String ct = c.getHeaderField("Content-Type"); name += inferExt(ct); }
                c.disconnect(); return name;
            } catch (Exception e) { String p = url; try { p = new URI(url).getPath(); } catch (Exception ignored) {} String n = p.substring(p.lastIndexOf('/')+1); return (n.isEmpty()?"download":n); }
        }

        private String inferExt(String ct) {
            if (ct == null) return ".bin";
            String t = ct.split(";")[0].trim().toLowerCase();
            Map<String,String> m = new HashMap<>();
            m.put("image/jpeg",".jpg"); m.put("image/png",".png"); m.put("image/gif",".gif"); m.put("image/webp",".webp");
            m.put("video/mp4",".mp4"); m.put("video/webm",".webm"); m.put("video/x-matroska",".mkv");
            m.put("audio/mpeg",".mp3"); m.put("audio/ogg",".ogg");
            m.put("application/pdf",".pdf"); m.put("application/zip",".zip"); m.put("application/x-7z-compressed",".7z"); m.put("application/x-rar-compressed",".rar");
            m.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document",".docx");
            m.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",".xlsx");
            m.put("application/vnd.openxmlformats-officedocument.presentationml.presentation",".pptx");
            m.put("text/plain",".txt"); m.put("text/html",".html"); m.put("application/json",".json");
            return m.getOrDefault(t, ".bin");
        }

        private void openSaveDir() {
            File dir = lastTarget != null ? lastTarget.getParentFile() : new File(cfg.get("download.saveDir", System.getProperty("user.home")));
            if (!dir.exists()) dir = new File(System.getProperty("user.home"));
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                try { Desktop.getDesktop().open(dir); }
                catch (IOException e) { Log.logErr("打开目录失败", e); JOptionPane.showMessageDialog(this, "打开目录失败: "+e.getMessage()); }
            } else { JOptionPane.showMessageDialog(this, "当前系统不支持自动打开目录"); }
        }

        private String formatSp(double b) { if (b<1024) return String.format("%.0f B/s",b); if (b<1024*1024) return String.format("%.1f KB/s",b/1024); return String.format("%.2f MB/s",b/1048576); }
        private String formatSize(long n) { if (n<1024) return n+" B"; if (n<1048576) return String.format("%.1f KiB",n/1024.0); if (n<1073741824) return String.format("%.1f MiB",n/1048576.0); return String.format("%.2f GiB",n/1073741824.0); }
    }

    // ===================== 设置对话框(集成 DNS / 清理 / 日志) =====================
    static class SettingsDlg extends JDialog {
        private final Config cfg;
        private JCheckBox proxyEn; private JTextField proxyHost, proxyPort, dnsPri, dnsSec;
        private JComboBox<String> proxyType;
        private JCheckBox autoOpen;
        SettingsDlg(Frame owner, Config c) { super(owner, "设置", true); cfg = c; init(); }

        private void init() {
            setLayout(new BorderLayout(8,8)); ((JComponent)getContentPane()).setBorder(new EmptyBorder(12,12,12,12));
            JTabbedPane tab = new JTabbedPane();

            // --- 网络/DNS ---
            JPanel net = new JPanel(new GridBagLayout()); GridBagConstraints g = new GridBagConstraints(); g.insets=new Insets(5,5,5,5); g.anchor=GridBagConstraints.WEST; g.fill=GridBagConstraints.HORIZONTAL;
            proxyEn = new JCheckBox("启用代理", cfg.getBool("proxy.enabled",false));
            g.gridx=0;g.gridy=0;g.gridwidth=3; net.add(proxyEn,g);
            g.gridwidth=1; g.gridy++; net.add(new JLabel("类型:"),g); g.gridx=1; proxyType=new JComboBox<>(new String[]{"socks","http"}); proxyType.setSelectedItem(cfg.get("proxy.type","socks")); net.add(proxyType,g);
            g.gridx=0;g.gridy++; net.add(new JLabel("主机:"),g); g.gridx=1;g.gridwidth=2; proxyHost=new JTextField(cfg.get("proxy.host",""),18); net.add(proxyHost,g);
            g.gridwidth=1; g.gridx=0;g.gridy++; net.add(new JLabel("端口:"),g); g.gridx=1; proxyPort=new JTextField(cfg.get("proxy.port","1080"),6); net.add(proxyPort,g);

            g.gridx=0;g.gridy++;g.gridwidth=3; net.add(new JLabel("<html><b>DNS 设置</b> (留空则使用系统 DNS)</html>"),g);
            g.gridwidth=1; g.gridx=0;g.gridy++; net.add(new JLabel("首选 DNS:"),g); g.gridx=1;g.gridwidth=2; dnsPri=new JTextField(cfg.get("dns.primary",""),16); net.add(dnsPri,g);
            g.gridwidth=1; g.gridx=0;g.gridy++; net.add(new JLabel("备选 DNS:"),g); g.gridx=1;g.gridwidth=2; dnsSec=new JTextField(cfg.get("dns.secondary",""),16); net.add(dnsSec,g);

            g.gridx=0;g.gridy++;g.gridwidth=3; net.add(new JLabel("推荐:"),g);
            JPanel rec = new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));
            rec.add(dnsBtn("Cloudflare 1.1.1.1","1.1.1.1","1.0.0.1"));
            rec.add(dnsBtn("Baidu 180.76.76.76","180.76.76.76",""));
            rec.add(dnsBtn("Google 8.8.8.8","8.8.8.8","8.8.4.4"));
            rec.add(dnsBtn("114DNS 114.114.114.114","114.114.114.114",""));
            g.gridx=0;g.gridy++;g.gridwidth=3; net.add(rec,g);
            tab.add("网络 / DNS", net);

            // --- 下载 ---
            JPanel dl = new JPanel(new GridBagLayout()); GridBagConstraints d=g; d.insets=new Insets(5,5,5,5); d.anchor=GridBagConstraints.WEST;
            d.gridx=0;d.gridy=0; dl.add(new JLabel("线程数(锁定 8，避免文件损坏):"),d); d.gridx=1; dl.add(new JLabel("8"),d);
            d.gridx=0;d.gridy++; dl.add(new JLabel("连接超时(毫秒):"),d); d.gridx=1; dl.add(new JLabel(String.valueOf(cfg.getInt("download.timeout",TIMEOUT_MS))),d);
            d.gridx=0;d.gridy++; autoOpen=new JCheckBox("下载完成后自动打开下载目录", cfg.getBool("download.autoOpenDir",true)); d.gridx=0;d.gridwidth=2; dl.add(autoOpen,d);
            d.gridy++; dl.add(new JLabel("保存目录: "+cfg.get("download.saveDir",System.getProperty("user.home"))),d);
            tab.add("下载", dl);

            // --- 维护(清理 + 日志) ---
            JPanel maint = new JPanel(new BorderLayout(8,8));
            JTextArea logView = new JTextArea(14,50); logView.setEditable(false); logView.setFont(new Font(Font.MONOSPACED,Font.PLAIN,11));
            File lf = new File(LOG_FILE);
            if (lf.exists()) { try { java.util.List<String> lines = Files.readAllLines(lf.toPath()); int start = Math.max(0,lines.size()-200); for (int i=start;i<lines.size();i++) logView.append(lines.get(i)+"\n"); } catch (IOException e) { logView.append("读取日志失败\n"); } }
            else logView.append("(尚无日志)\n");
            maint.add(new JLabel("最近日志(最多 200 行):"), BorderLayout.NORTH); maint.add(new JScrollPane(logView), BorderLayout.CENTER);
            JPanel mb = new JPanel(new FlowLayout(FlowLayout.LEFT,8,4));
            JButton clearCache = new JButton("清理临时文件(.pydlpart / .pydlmeta)");
            clearCache.addActionListener(e -> { int n = cleanTemp(); JOptionPane.showMessageDialog(this, "已清理 "+n+" 个临时文件", "垃圾清理", JOptionPane.INFORMATION_MESSAGE); });
            JButton clearLog = new JButton("清空日志文件");
            clearLog.addActionListener(e -> { try (FileWriter w=new FileWriter(lf,false)){w.write("");} catch (IOException ex){} logView.setText("(日志已清空)\n"); Log.info("日志已清空"); });
            mb.add(clearCache); mb.add(clearLog);
            maint.add(mb, BorderLayout.SOUTH);
            tab.add("维护 / 日志", maint);

            add(tab, BorderLayout.CENTER);
            JButton save = new JButton("保存"); JButton cancel = new JButton("取消");
            JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT)); bp.add(cancel); bp.add(save); add(bp, BorderLayout.SOUTH);
            save.addActionListener(e -> { saveCfg(); dispose(); }); cancel.addActionListener(e -> dispose());
            pack(); setLocationRelativeTo(getOwner());
        }

        private JButton dnsBtn(String tip, String pri, String sec) { JButton b = new JButton(tip.split(" ")[0]); b.setToolTipText(tip); b.addActionListener(e -> { dnsPri.setText(pri); dnsSec.setText(sec); }); return b; }

        private void saveCfg() {
            cfg.set("proxy.enabled", String.valueOf(proxyEn.isSelected()));
            cfg.set("proxy.host", proxyHost.getText().trim()); cfg.set("proxy.port", proxyPort.getText().trim());
            cfg.set("proxy.type", String.valueOf(proxyType.getSelectedItem()));
            cfg.set("dns.primary", dnsPri.getText().trim()); cfg.set("dns.secondary", dnsSec.getText().trim());
            cfg.set("download.autoOpenDir", String.valueOf(autoOpen.isSelected()));
            Net.applyDnsFromConfig(cfg); Net.applyProxyFromConfig(cfg);
            Log.info("设置已保存(代理="+(proxyEn.isSelected()?"启用":"禁用")+", DNS主="+(dnsPri.getText().trim().isEmpty()?"系统":dnsPri.getText().trim())+")");
        }

        private int cleanTemp() {
            File dir = new File(cfg.get("download.saveDir", System.getProperty("user.home")));
            int n = 0;
            File[] fs = dir.listFiles((d,f) -> f.endsWith(PART_EXT) || f.endsWith(".pydlmeta"));
            if (fs != null) for (File f : fs) { if (f.delete()) { n++; Log.info("清理临时文件: "+f.getName()); } }
            // 同时清理工作目录残留
            File wd = new File(System.getProperty("user.dir"));
            File[] ws = wd.listFiles((d,f) -> f.endsWith(PART_EXT) || f.endsWith(".pydlmeta"));
            if (ws != null) for (File f : ws) { if (f.delete()) { n++; Log.info("清理临时文件: "+f.getName()); } }
            return n;
        }
    }
}
