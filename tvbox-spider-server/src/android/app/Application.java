package android.app;

import android.content.Context;

/**
 * macos/JVM 端 android.app.Application shim。
 * 部分 jar 型 spider（如 Guard 系）在类字段/方法签名里引用 android.app.Application，
 * 类链接阶段需要该类型存在；桌面端无真实应用进程，这里只保证类型可用。
 */
public class Application extends Context {

    public Application() {
        super();
    }

    @Override
    public String toString() {
        return "Application(shim)";
    }
}
