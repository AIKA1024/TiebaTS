package gm.tieba.tabswitch.hooker.extra;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import gm.tieba.tabswitch.compat.XC_MethodHook;
import gm.tieba.tabswitch.compat.XposedBridge;
import gm.tieba.tabswitch.compat.XposedHelpers;
import gm.tieba.tabswitch.XposedContext;
import gm.tieba.tabswitch.hooker.IHooker;

public class StackTrace extends XposedContext implements IHooker {
    public static List<String> sStes = new ArrayList<>();

    @NonNull
    @Override
    public String key() {
        return "check_stack_trace";
    }

    @Override
    public void hook() throws Throwable {
        for (final Method method : XposedHelpers.findClass("com.baidu.tieba.LogoActivity").getDeclaredMethods()) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    final List<String> sts = new ArrayList<>();
                    final StackTraceElement[] stes = Thread.currentThread().getStackTrace();
                    boolean isXposedStackTrace = false;
                    for (final StackTraceElement ste : stes) {
                        final String name = ste.getClassName();
                        if (name.contains("Activity")
                                || name.equals("android.app.Instrumentation")) break;
                        if (isXposedStackTrace) sts.add(name);
                        if (name.equals("java.lang.Thread")) isXposedStackTrace = true;
                    }

                    for (final String st : sts) {
                        if (!sStes.contains(st)) sStes.add(st);
                    }
                }
            });
        }
    }
}
