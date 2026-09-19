package com.github.catvod.tools;

import com.github.catvod.DesktopContext;
import com.github.catvod.host.JarDiagnoser;
import com.github.catvod.host.ServerConfig;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 导入前静态预检命令行入口（tools/precheck-jar.sh 的 Java 侧实现）。
 *
 * 用法：
 *   java -cp "build/classes:libs/*" com.github.catvod.tools.PrecheckTool <jar> [--api csp_Xxx] [--json] [--no-probe]
 *
 * 输出：
 *   默认输出人类可读的预检报告；--json 输出完整 JSON（与 GET /api/diagnose/{siteKey} 同构）。
 * 说明：
 *   与运行期诊断复用同一套逻辑（JarDiagnoser），因此报告结论与服务实际加载结果一致；
 *   唯一写入是 dex→JVM 转换缓存 plugins/<name>-jvm.jar。
 */
public final class PrecheckTool {

    private PrecheckTool() {
    }

    public static void main(String[] args) {
        String jarPath = null;
        String api = "";
        boolean json = false;
        boolean probe = true;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--api".equals(arg) && i + 1 < args.length) {
                api = args[++i];
            } else if ("--json".equals(arg)) {
                json = true;
            } else if ("--no-probe".equals(arg)) {
                probe = false;
            } else if ("-h".equals(arg) || "--help".equals(arg)) {
                usage();
                return;
            } else if (arg.startsWith("--")) {
                System.err.println("未知参数: " + arg);
                usage();
                System.exit(2);
            } else if (jarPath == null) {
                jarPath = arg;
            }
        }
        if (jarPath == null) {
            usage();
            System.exit(2);
        }

        File root = root();
        File jar = new File(jarPath);
        if (!jar.isAbsolute()) jar = new File(root, jarPath);
        if (!jar.exists()) {
            System.err.println("jar 不存在: " + jar.getAbsolutePath());
            System.exit(3);
        }

        int port = 9978;
        try {
            port = ServerConfig.load(new File(root, "config/spiders.json")).port;
        } catch (Throwable ignored) {
        }

        DesktopContext.init(new File(root, "data"));
        JarDiagnoser.Result result = JarDiagnoser.diagnose(
                root, "", jar, api, "", DesktopContext.get(), port, probe);

        if (json) {
            System.out.println(ServerConfig.toJson(result.toMap()));
        } else {
            printReport(result);
        }
        // 主链路可跑（或仅有非致命缺失）时退出码 0，便于脚本串联
        String conclusion = result.conclusion == null ? "" : result.conclusion;
        if (conclusion.startsWith("无法") || conclusion.contains("失败")) System.exit(1);
    }

    private static void printReport(JarDiagnoser.Result r) {
        line();
        System.out.println(" jar 预检报告（precheck-jar.sh）");
        line();
        System.out.println("文件路径       : " + r.jarPath);
        System.out.println("文件大小       : " + r.jarSize + " bytes");
        System.out.println("格式判定       : " + r.jarFormat + " —— " + r.jarFormatDetail);

        if (!r.convertedPath.isEmpty()) {
            if (!r.convertError.isEmpty()) {
                System.out.println("dex 转换       : 失败（" + r.convertMs + " ms）");
                System.out.println("                 原因: " + r.convertError);
            } else if (r.convertReused) {
                System.out.println("dex 转换       : 复用已缓存产物（" + r.convertMs + " ms）");
                System.out.println("                 产物: " + r.convertedPath);
            } else {
                System.out.println("dex 转换       : 成功（" + r.convertMs + " ms）");
                System.out.println("                 产物: " + r.convertedPath);
            }
        }

        System.out.println("spider 类清单  : " + r.spiderClasses.size() + " 个"
                + (r.classCount > 0 ? "（jar 内 .class 共 " + r.classCount + " 个）" : ""));
        for (String name : r.spiderClasses) {
            System.out.println("  · " + name + "  " + describeSpiderClass(r, name));
        }
        for (String error : r.spiderClassLoadErrors) {
            System.out.println("  ! 类加载失败: " + error);
        }

        System.out.println("目标类         : " + (r.targetClass.isEmpty() ? "（未确定，站点 api 为空且无法自动推断）" : r.targetClass)
                + (r.api.isEmpty() ? "" : "   api=" + r.api)
                + (r.inferredApi ? "（由 jar 内唯一 Spider 子类自动推断）" : ""));
        if (!r.targetClass.isEmpty()) {
            System.out.println("类加载         : " + (r.classLoadOk
                    ? "OK（" + (r.isSpiderSubclass ? "确认是 Spider 子类" : "不是 Spider 子类") + "）"
                    : "失败: " + r.classLoadError));
        }
        if (r.instantiateOk || !r.instantiateError.isEmpty()) {
            System.out.println("实例化 / init  : " + (r.instantiateOk ? "OK" : "失败: " + r.instantiateError)
                    + " / " + (r.initOk ? "OK" : "失败: " + r.initError));
        }
        if (r.probe != null) {
            boolean ok = Boolean.TRUE.equals(r.probe.get("ok"));
            System.out.println("homeContent探针: " + (ok
                    ? "OK，返回 " + r.probe.get("chars") + " 字符，耗时 " + r.probe.get("elapsedMs") + " ms"
                    : "失败，耗时 " + r.probe.get("elapsedMs") + " ms，原因: " + r.probe.get("error")));
            if (ok && !String.valueOf(r.probe.get("preview")).isEmpty()) {
                System.out.println("                 预览: " + r.probe.get("preview"));
            }
            if (!ok && !String.valueOf(r.probe.get("stack")).isEmpty()) {
                System.out.println("                 异常栈:");
                for (String stackLine : String.valueOf(r.probe.get("stack")).split("\\R")) {
                    System.out.println("                   " + stackLine);
                }
            }
        }

        System.out.println("常量池扫描     : 已扫描 " + r.classesScanned + " 个类，外部引用 "
                + r.referenceCount + " 个" + (r.scanTruncated ? "（达到扫描上限，结果可能不全）" : ""));
        int android = r.missingAndroid.size();
        int other = r.missingOther.size();
        if (android == 0 && other == 0) {
            System.out.println("缺失类         : 无（宿主可见类可解析全部引用）");
        } else {
            System.out.println("缺失类         : 共 " + (android + other) + " 个（android.* " + android + " / 其他 " + other + "）");
            printMissing("android.*", r.missingAndroid);
            printMissing("其他分包", r.missingOther);
        }

        line();
        System.out.println("结论           : " + r.conclusion);
        line();
    }

    private static String describeSpiderClass(JarDiagnoser.Result r, String name) {
        for (Map<String, Object> item : r.spiderSubclasses) {
            if (name.equals(String.valueOf(item.get("class")))) {
                boolean isAbstract = Boolean.TRUE.equals(item.get("abstract"));
                return "→ Spider 子类" + (isAbstract ? "（abstract，不可实例化）" : "") + "，api 名 " + item.get("api");
            }
        }
        return "（非 Spider 子类，可能是内部辅助类）";
    }

    private static void printMissing(String title, List<String> list) {
        if (list.isEmpty()) return;
        System.out.println("  [" + title + "]");
        for (String name : list) System.out.println("    - " + name);
    }

    private static File root() {
        String home = System.getProperty("tvbox.home");
        if (home != null && !home.trim().isEmpty()) return new File(home).getAbsoluteFile();
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    private static void line() {
        System.out.println("============================================================");
    }

    private static void usage() {
        System.out.println("用法: bash tools/precheck-jar.sh <jar> [--api csp_Xxx] [--json] [--no-probe]");
        System.out.println("  --api csp_Xxx  指定站点 api（不指定时若 jar 内只有一个 Spider 子类会自动推断）");
        System.out.println("  --json         以 JSON 输出（与 /api/diagnose 同构）");
        System.out.println("  --no-probe     跳过实例化与 homeContent 探针，仅做静态检查");
    }
}
