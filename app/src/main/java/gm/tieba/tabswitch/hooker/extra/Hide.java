package gm.tieba.tabswitch.hooker.extra;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import gm.tieba.tabswitch.compat.XC_MethodHook;
import gm.tieba.tabswitch.compat.XposedHelpers;
import gm.tieba.tabswitch.BuildConfig;
import gm.tieba.tabswitch.XposedContext;
import gm.tieba.tabswitch.hooker.IHooker;

public class Hide extends XposedContext implements IHooker {

    @NonNull
    @Override
    public String key() {
        return "hide";
    }

    /**
     * @deprecated hook VMStack_getThreadStackTrace instead.
     */
    @Deprecated
    @Override
    public void hook() throws Throwable {
        for (final Class<?> clazz : new Class<?>[]{Throwable.class, Thread.class}) {
            XposedHelpers.findAndHookMethod(clazz, "getStackTrace", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    final StackTraceElement[] stes = (StackTraceElement[]) param.getResult();
                    final List<StackTraceElement> filtered = new ArrayList<>();
                    for (final StackTraceElement ste : stes) {
                        final String name = ste.getClassName();
                        if (!name.contains("posed") && !name.contains("Hooker")
                                && !name.contains(BuildConfig.APPLICATION_ID)
                                && !name.equals("java.lang.reflect.Method")) {
                            filtered.add(ste);
                        }
                    }

                    final StackTraceElement[] result = new StackTraceElement[filtered.size()];
                    for (int i = 0; i < filtered.size(); i++) {
                        result[i] = filtered.get(i);
                    }
                    param.setResult(result);
                }
            });
        }
    }
}
