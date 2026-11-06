package gm.tieba.tabswitch.compat;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;

/**
 * Legacy-shaped hook callback types, implemented in Java on purpose.
 *
 * Kotlin sees Java fields/getters as platform types, which is exactly how the original
 * {@code de.robv.android.xposed.XC_MethodHook.MethodHookParam} behaved. Writing these in Kotlin
 * would force every existing Hooker to add null checks, and would hide getResult()/setResult().
 */
public class XC_MethodHook {

    public final int priority;

    public XC_MethodHook() {
        this(XposedInterface.PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    public void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        private Object mResult;
        private Throwable mThrowable;
        private boolean mHasResult;
        private boolean mHasThrowable;

        public MethodHookParam(Object thisObject, Object[] args) {
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return mResult;
        }

        public void setResult(Object value) {
            mResult = value;
            mHasResult = true;
            mHasThrowable = false;
            mThrowable = null;
        }

        public Throwable getThrowable() {
            return mThrowable;
        }

        public void setThrowable(Throwable value) {
            mThrowable = value;
            mHasThrowable = true;
            mHasResult = false;
            mResult = null;
        }

        public boolean getHasResult() {
            return mHasResult;
        }

        public boolean getHasThrowable() {
            return mHasThrowable;
        }
    }

    /** Handle returned by every hook registration, compatible with the legacy Unhook type. */
    public static class Unhook {
        private final XposedInterface.HookHandle handle;

        public Unhook(XposedInterface.HookHandle handle) {
            this.handle = handle;
        }

        public void unhook() {
            handle.unhook();
        }

        public Executable getHookedMethod() {
            return handle.getExecutable();
        }
    }
}
