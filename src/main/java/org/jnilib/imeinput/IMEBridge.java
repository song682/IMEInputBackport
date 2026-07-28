package org.jnilib.imeinput;

/**
 * IME 输入法桥接层（窗口子类化方案）。
 * <p>
 * 通过 JNI 在 LWJGL 游戏窗口上安装子类化窗口过程（{@code SubclassProc}），
 * 直接拦截 {@code WM_IME_*} 消息，复用 Windows IMM32 读取组字/候选/结果串：
 * <ul>
 *   <li>{@code GCS_RESULTSTR}（已确认文字）→ 通过 {@link IMECallback#onCommit(String)} 回调插入文本框</li>
 *   <li>{@code GCS_COMPSTR}（组字预览）→ 通过 {@link IMECallback#onCompositionUpdate} 回调</li>
 *   <li>候选窗由系统在游戏窗口上自动弹出（可用 {@link #setCandidatePos} 微调位置）</li>
 * </ul>
 * <p>
 * 本地库符号名依赖类的全限定名 {@code org.jnilib.imeinput.IMEBridge}，请勿更改包名。
 * <p>
 * 本地库以「自包含」方式随模组 jar 分发：三份架构 DLL
 * （{@code JNILibIMEInput-x64.dll} / {@code -x86.dll} / {@code -arm64.dll}）打包在
 * classpath 根目录。JVM 无法直接从 jar 条目加载 native 库，故加载时先把匹配架构的 DLL
 * 释放（提取）到临时文件，再以绝对路径 {@link System#load(String)} 加载。
 */
public class IMEBridge {

    static {
        loadNativeLibrary();
    }

    /**
     * 从模组 jar 中提取并加载与当前架构匹配的本地库。
     * <p>
     * 依据 {@code os.arch} 决定候选 DLL 的尝试顺序，并把全部三份 DLL 作为兜底依次尝试——
     * 架构不匹配的 DLL 在 {@link System#load(String)} 阶段自然失败并被跳过，从而无需精确
     * 解析五花八门的 {@code os.arch} 取值（amd64/x86_64/aarch64/arm64 等）。
     * Extracts and loads the arch-matching native library bundled in the mod jar;
     * candidates are ordered by {@code os.arch} with all three tried as fallback.
     */
    private static void loadNativeLibrary() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        java.util.List<String> candidates = new java.util.ArrayList<String>();
        if (arch.contains("64")) {
            // 64 位：ARM64 优先匹配 arm64，其余 64 位归为 x64 / On 64-bit, prefer arm64 for ARM, else x64
            if (arch.contains("aarch") || arch.contains("arm")) {
                candidates.add("JNILibIMEInput-arm64.dll");
            }
            candidates.add("JNILibIMEInput-x64.dll");
        } else {
            // 32 位 / 32-bit
            candidates.add("JNILibIMEInput-x86.dll");
        }
        // 兜底：确保三份 DLL 都被尝试，规避 os.arch 取值差异 / Ensure all three are attempted
        for (String name : new String[] {
                "JNILibIMEInput-x64.dll", "JNILibIMEInput-x86.dll", "JNILibIMEInput-arm64.dll" }) {
            if (!candidates.contains(name)) {
                candidates.add(name);
            }
        }
        for (String name : candidates) {
            if (tryExtractAndLoad(name)) {
                return;
            }
        }
        throw new UnsatisfiedLinkError(
                "JNILibIMEInput: no matching native library for os.arch=" + arch);
    }

    /**
     * 把指定 classpath 资源释放到临时文件并尝试加载。
     * Extracts the given classpath resource to a temp file and tries to load it.
     *
     * @param resourceName jar 内 DLL 资源名（位于 classpath 根目录）
     * @return 加载成功返回 true；资源不存在、架构不匹配或加载失败返回 false
     */
    private static boolean tryExtractAndLoad(String resourceName) {
        java.io.InputStream in =
                IMEBridge.class.getClassLoader().getResourceAsStream(resourceName);
        if (in == null) {
            return false;
        }
        try {
            java.io.File tmp = java.io.File.createTempFile("JNILibIMEInput-", ".dll");
            tmp.deleteOnExit();
            java.io.OutputStream out = new java.io.FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
            System.load(tmp.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            // 架构不匹配 / IO 失败：跳过该候选 / Arch mismatch or IO failure: skip this candidate
            return false;
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // ignore
            }
        }
    }

    // ============================================================
    //  后端编排（AUTO / TSF / IMM32）
    //  Backend orchestration (AUTO / TSF / IMM32)
    // ============================================================

    /** 自动：先试 TSF，失败降级 IMM32 / Auto: try TSF first, fall back to IMM32 */
    public static final int BACKEND_AUTO = 0;
    /** 强制 TSF 后端 / Force the TSF backend */
    public static final int BACKEND_TSF = 1;
    /** 强制 IMM32 子类化后端 / Force the IMM32 subclass backend */
    public static final int BACKEND_IMM32 = 2;
    /** 未安装 / Not installed */
    public static final int BACKEND_NONE = -1;

    /** 当前激活后端；仅在游戏主线程读写 / Active backend; main-thread only */
    private static int activeBackend = BACKEND_NONE;

    /**
     * 按配置模式在游戏窗口上安装 IME 后端。
     * AUTO / TSF 先试 TSF（安装链任一环节失败即本地层整体回滚）；
     * TSF 失败或模式为 IMM32 时落到现有子类化路径。重复调用无副作用。
     * Installs the IME backend per configured mode: AUTO/TSF try TSF first
     * (native side rolls back fully on any failure); otherwise falls back to
     * the existing IMM32 subclass path. Idempotent.
     *
     * @param hwnd 窗口句柄（{@code HWND} 的整数值）
     * @param mode {@link #BACKEND_AUTO} / {@link #BACKEND_TSF} / {@link #BACKEND_IMM32}
     * @return 实际激活的后端（{@link #BACKEND_TSF} 或 {@link #BACKEND_IMM32}），供日志输出
     */
    public static int install(long hwnd, int mode) {
        if (activeBackend != BACKEND_NONE) {
            return activeBackend;
        }
        if (mode != BACKEND_IMM32 && nativeTsfInstall(hwnd)) {
            activeBackend = BACKEND_TSF;
        } else {
            // TSF 失败或强制 IMM32：走现有子类化路径 / TSF failed or forced IMM32
            subclassWindow(hwnd);
            activeBackend = BACKEND_IMM32;
        }
        return activeBackend;
    }

    /**
     * 卸载当前激活的后端（窗口重建/重挂前调用）。未安装时无副作用。
     * Uninstalls the active backend (called before window rebuild/rehook).
     *
     * @param hwnd 窗口句柄（IMM32 路径需要；TSF 路径忽略）
     */
    public static void uninstall(long hwnd) {
        if (activeBackend == BACKEND_TSF) {
            nativeTsfUninstall();
        } else if (activeBackend == BACKEND_IMM32) {
            unsubclassWindow(hwnd);
        }
        activeBackend = BACKEND_NONE;
    }

    /**
     * 按激活后端分发的 IME 使能开关。
     * TSF → {@link #nativeTsfSetEnabled}（AssociateFocus 切换真实/空文档）；
     * IMM32 → 现有 {@link #setImeEnabled}。
     * Backend-dispatched IME enable switch.
     *
     * @param hwnd    窗口句柄
     * @param enabled true 启用中日韩输入，false 强制英文
     */
    public static void setEnabled(long hwnd, boolean enabled) {
        if (activeBackend == BACKEND_TSF) {
            nativeTsfSetEnabled(enabled);
        } else if (activeBackend == BACKEND_IMM32) {
            setImeEnabled(hwnd, enabled);
        }
    }

    /**
     * 按激活后端分发的候选/组字窗定位（窗口客户区像素坐标）。
     * TSF → {@link #nativeTsfSetCaretPos}（由 {@code GetTextExt} 交给输入法）；
     * IMM32 → 现有 {@link #setCandidatePos}。
     * Backend-dispatched candidate/composition window positioning.
     *
     * @param hwnd 窗口句柄
     * @param x    客户区 X（像素）
     * @param y    客户区 Y（像素，通常为光标底部）
     */
    public static void setCaretPos(long hwnd, int x, int y) {
        if (activeBackend == BACKEND_TSF) {
            nativeTsfSetCaretPos(x, y);
        } else if (activeBackend == BACKEND_IMM32) {
            setCandidatePos(hwnd, x, y);
        }
    }

    /**
     * 当前激活的后端。
     * @return {@link #BACKEND_TSF} / {@link #BACKEND_IMM32} / {@link #BACKEND_NONE}
     */
    public static int getActiveBackend() {
        return activeBackend;
    }

    /**
     * 后端名称（日志用）。 / Backend display name for logging.
     *
     * @param backend 后端常量
     * @return 可读名称
     */
    public static String backendName(int backend) {
        switch (backend) {
            case BACKEND_AUTO:  return "AUTO";
            case BACKEND_TSF:   return "TSF";
            case BACKEND_IMM32: return "IMM32";
            default:            return "NONE";
        }
    }

    // ============================================================
    //  TSF 后端 native（由 tsf_bridge.cpp 实现）
    //  TSF backend natives (implemented in tsf_bridge.cpp)
    // ============================================================

    /**
     * 在窗口上安装 TSF 后端。安装链任一步失败即整体回滚并返回 false，
     * 不抛异常，供 {@link #install} 静默降级到 IMM32。
     * Installs the TSF backend; full rollback + false on any failure.
     *
     * @param hwnd 窗口句柄
     * @return 安装成功返回 true
     */
    private static native boolean nativeTsfInstall(long hwnd);

    /** 卸载 TSF 后端并释放全部 COM 状态。 / Uninstalls TSF and releases COM state. */
    private static native void nativeTsfUninstall();

    /**
     * TSF 路径的 IME 使能开关（AssociateFocus 切换真实/空文档管理器）。
     * TSF-path IME enable switch (AssociateFocus real/empty document manager).
     *
     * @param enabled true 允许组字，false 禁用输入法
     */
    private static native void nativeTsfSetEnabled(boolean enabled);

    /**
     * 上报光标位置（窗口客户区像素），供输入法经 {@code GetTextExt} 定位候选窗。
     * Reports the caret position consumed by {@code GetTextExt}.
     *
     * @param x 客户区 X（像素）
     * @param y 客户区 Y（像素）
     */
    private static native void nativeTsfSetCaretPos(int x, int y);

    // ============================================================
    //  窗口子类化 / IME 使能
    // ============================================================

    /**
     * 在指定窗口（LWJGL 游戏窗口）上安装子类化窗口过程，并恢复默认 IME 上下文关联。
     * 重复调用无副作用。
     *
     * @param hwnd 窗口句柄（{@code HWND} 的整数值）
     */
    public static native void subclassWindow(long hwnd);

    /**
     * 恢复窗口的原始窗口过程。
     *
     * @param hwnd 窗口句柄
     */
    public static native void unsubclassWindow(long hwnd);

    /**
     * 启用 IME（允许中日韩输入）或强制英文。
     * 启用：重新开启 IME 并恢复用户之前的转换模式；
     * 禁用：切换为字母数字模式并关闭 IME（直接输入），使微软拼音在游戏内无法组字。
     * 仅在文本框聚焦时启用，其余场景强制英文，避免误触发输入法。
     *
     * @param hwnd    窗口句柄
     * @param enabled true 启用中日韩输入，false 强制英文
     */
    public static native void setImeEnabled(long hwnd, boolean enabled);

    /**
     * 将组字/候选窗定位到文本光标附近（窗口客户区像素坐标）。
     *
     * @param hwnd 窗口句柄
     * @param x    客户区 X（像素）
     * @param y    客户区 Y（像素，通常为光标底部）
     */
    public static native void setCandidatePos(long hwnd, int x, int y);

    // ============================================================
    //  回调
    // ============================================================

    /**
     * 设置 IME 事件回调。回调在游戏窗口消息线程（即主线程）上执行。
     *
     * @param callback {@link IMECallback} 实现，传 null 清空
     */
    public static native void setCallback(IMECallback callback);

    /**
     * 清除已注册的回调。
     */
    public static native void clearCallback();

    // ============================================================
    //  数据结构
    // ============================================================

    /**
     * IME 候选词。
     * <p>
     * 自绘候选窗升级后，本地层仅上报当前可见页（{@code dwPageStart} /
     * {@code dwPageSize} 切片），因此 {@link #index} 为页内相对索引（从 0 起）。
     * Since the self-drawn upgrade the native layer reports only the visible page
     * (sliced by {@code dwPageStart}/{@code dwPageSize}), so {@link #index} is
     * page-relative (0-based).
     */
    public static class Candidate {
        /** 候选词在当前页内的索引 */
        public final int index;
        /** 候选词文本 */
        public final String text;

        public Candidate(int index, String text) {
            this.index = index;
            this.text = text;
        }

        @Override
        public String toString() {
            return index + ": " + text;
        }
    }

    /**
     * IME 组字状态快照。
     * <p>
     * 形态对齐高版本 MC 的 {@code PreeditEvent(fullText, caretPosition, blocks, focusedBlock)}：
     * 在扁平组字串之上补充了分段（clause）、聚焦段与光标位置，
     * 数据来自 IMM32 的 {@code GCS_COMPCLAUSE} / {@code GCS_COMPATTR} / {@code GCS_CURSORPOS}。
     * Aligned with the modern {@code PreeditEvent} shape: clause segmentation,
     * focused clause and caret position on top of the flat composition string.
     */
    public static class CompositionInfo {
        /** 当前读音（原始拼音/假名） */
        public final String reading;
        /** 当前组字串（转换中的文字，即 fullText） */
        public final String composition;
        /** 光标在组字串中的字符索引（0..length） */
        public final int caretPosition;
        /** 分段/分句片段；无分段信息时为单元素数组（整串作一段） */
        public final String[] blocks;
        /** 当前聚焦分段的索引 */
        public final int focusedBlock;
        /** IME 是否正在组字中 */
        public final boolean isComposing;

        /**
         * 由本地层回调构造：传入 IMM32 分段边界数组，在此切分为 blocks。
         * Constructed from native callback: clause boundaries are split into blocks here.
         *
         * @param reading      读音串
         * @param composition  组字串
         * @param caretPosition 光标字符索引
         * @param clauses      分段边界（GCS_COMPCLAUSE，首尾为 0 与串长），可为 null
         * @param focusedBlock 聚焦分段索引
         * @param isComposing  是否组字中
         */
        public CompositionInfo(String reading, String composition, int caretPosition,
                               int[] clauses, int focusedBlock, boolean isComposing) {
            this.reading = reading == null ? "" : reading;
            this.composition = composition == null ? "" : composition;
            int len = this.composition.length();
            // 光标钳位到 [0, len] / Clamp caret into [0, len]
            this.caretPosition = Math.max(0, Math.min(caretPosition, len));
            this.blocks = splitBlocks(this.composition, clauses);
            // 聚焦段钳位到合法索引 / Clamp focused block into valid range
            this.focusedBlock = Math.max(0, Math.min(focusedBlock, this.blocks.length - 1));
            this.isComposing = isComposing;
        }

        /**
         * 按分段边界切分组字串；边界非法或缺失时退化为整串单段。
         * Splits the composition by clause boundaries; falls back to one block.
         */
        private static String[] splitBlocks(String composition, int[] clauses) {
            int len = composition.length();
            if (clauses != null && clauses.length >= 2) {
                java.util.List<String> list = new java.util.ArrayList<String>(clauses.length - 1);
                boolean valid = true;
                for (int i = 0; i < clauses.length - 1 && valid; i++) {
                    int start = clauses[i];
                    int end = clauses[i + 1];
                    if (start < 0 || end < start || end > len) {
                        valid = false;
                    } else if (end > start) {
                        list.add(composition.substring(start, end));
                    }
                }
                if (valid && !list.isEmpty()) {
                    return list.toArray(new String[0]);
                }
            }
            return new String[] { composition };
        }

        @Override
        public String toString() {
            return String.format(
                    "CompositionInfo{composition='%s', caret=%d, blocks=%d, focused=%d, composing=%s}",
                    composition, caretPosition, blocks.length, focusedBlock, isComposing);
        }
    }

    // ============================================================
    //  回调接口
    // ============================================================

    /**
     * IME 事件回调接口。
     * 注意：回调在游戏窗口消息线程执行，需与游戏主循环协调（本工程通过队列在客户端 tick 消费）。
     */
    public interface IMECallback {

        /**
         * IME 组字状态更新（组字预览）。
         * @param info 当前组字状态
         */
        void onCompositionUpdate(CompositionInfo info);

        /**
         * 候选词列表更新（自绘候选窗使用）。
         * 本地层已按当前可见页切片，候选项索引为页内相对索引。
         * Candidate list update (for the self-drawn window); the native layer
         * pre-slices the visible page, indices are page-relative.
         *
         * @param candidates    当前页候选词数组，可能为空数组
         * @param selectedIndex 页内选中项索引；-1 表示无选中项 / page-relative selection, -1 = none
         */
        void onCandidatesUpdate(Candidate[] candidates, int selectedIndex);

        /**
         * IME 提交确认文字。
         * @param text 已提交的文字
         */
        void onCommit(String text);
    }
}
