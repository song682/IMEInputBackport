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
     */
    public static class Candidate {
        /** 候选词在列表中的索引 */
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
     */
    public static class CompositionInfo {
        /** 当前读音（原始拼音/假名） */
        public final String reading;
        /** 当前组字串（转换中的文字） */
        public final String composition;
        /** IME 是否正在组字中 */
        public final boolean isComposing;

        public CompositionInfo(String reading, String composition, boolean isComposing) {
            this.reading = reading;
            this.composition = composition;
            this.isComposing = isComposing;
        }

        @Override
        public String toString() {
            return String.format("CompositionInfo{reading='%s', composition='%s', composing=%s}",
                    reading, composition, isComposing);
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
         * 候选词列表更新（自绘候选窗时使用）。
         * @param candidates 候选词数组，可能为空数组
         */
        void onCandidatesUpdate(Candidate[] candidates);

        /**
         * IME 提交确认文字。
         * @param text 已提交的文字
         */
        void onCommit(String text);
    }
}
