package com.github.catvod.tools;

import com.googlecode.d2j.dex.Dex2jar;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * dex2jar 命令行封装：把 Android 的 dex 格式 jar 转成 JVM 可加载的 class jar。
 *
 * 用法：
 *   java -cp "build/classes:libs/dex2jar/*" com.github.catvod.tools.Dex2JarTool <input.jar> <output.jar>
 *
 * 说明：Android 的 spider.jar 内部是 classes.dex，JVM 的 URLClassLoader 无法直接加载，
 * 必须先由本工具（基于 dex2jar / d2j）转换成标准 class jar。
 */
public final class Dex2JarTool {

    private Dex2JarTool() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法: Dex2JarTool <input-dex-jar> <output-jvm-jar>");
            System.exit(2);
        }
        File input = new File(args[0]);
        File output = new File(args[1]);
        if (!input.exists() || input.length() == 0) {
            System.err.println("输入文件不存在或为空: " + input.getAbsolutePath());
            System.exit(3);
        }
        try {
            convert(input, output);
            System.out.println("转换成功: " + input.getAbsolutePath() + " -> " + output.getAbsolutePath());
        } catch (Throwable e) {
            System.err.println("转换失败: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** 供宿主进程直接调用（同 JVM 内） */
    public static void convert(File input, File output) throws Exception {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Path target = output.toPath();
        Files.deleteIfExists(target);
        Dex2jar.from(input)
                .reUseReg(false)
                .topoLogicalSort()
                .skipDebug(true)
                .optimizeSynchronized(false)
                .to(target);
        if (!output.exists() || output.length() == 0) {
            throw new IllegalStateException("转换产物为空: " + output.getAbsolutePath());
        }
    }
}
