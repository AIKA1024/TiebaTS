package gm.tieba.tabswitch

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import gm.tieba.tabswitch.compat.HookParam
import gm.tieba.tabswitch.compat.Unhook
import gm.tieba.tabswitch.compat.XC_MethodHook
import gm.tieba.tabswitch.compat.XCallback
import gm.tieba.tabswitch.compat.XposedHelpers
import io.github.libxposed.api.XposedInterface
import java.io.IOException
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.zip.ZipFile

/**
 * Framework abstraction layer for libxposed API 102.
 *
 * Modern Xposed uses a single interceptor-chain hooker instead of separate before/after callbacks.
 * [install] rebuilds the classic semantics on top of the chain; the twelve public helpers below
 * keep their original signatures so all 31 Hookers compile and behave as before.
 */
abstract class XposedContext {

    companion object {
        private const val TAG = "TiebaTS"

        val isModuleBetaVersion =
            BuildConfig.VERSION_NAME.contains("alpha") || BuildConfig.VERSION_NAME.contains("beta")
        val exceptions: MutableMap<String, Throwable> = HashMap(0)

        /** Set by [XposedInit] as soon as the framework attaches. */
        lateinit var framework: XposedInterface
        val isFrameworkReady: Boolean get() = ::framework.isInitialized

        lateinit var sClassLoader: ClassLoader
        lateinit var sPath: String
        var sProcessName: String = ""

        private lateinit var sHandler: Handler
        private lateinit var sContextRef: WeakReference<Context>

        @JvmStatic
        fun getContext(): Context = checkNotNull(sContextRef.get()) { "ApplicationContext is null" }

        fun attachBaseContext(context: Context) {
            sContextRef = WeakReference(context.applicationContext)
            sHandler = Handler(Looper.getMainLooper())
        }

        @JvmStatic
        fun log(message: String) {
            if (isFrameworkReady) {
                framework.log(Log.INFO, TAG, message)
            } else {
                Log.i(TAG, message)
            }
        }

        @JvmStatic
        fun log(t: Throwable?) {
            if (t == null) return
            if (isFrameworkReady) {
                framework.log(Log.ERROR, TAG, Log.getStackTraceString(t))
            } else {
                Log.e(TAG, "", t)
            }
        }

        /** Reads a file from the module APK. XModuleResources is not available in the modern API. */
        @JvmStatic
        fun readModuleAsset(filename: String): String? = try {
            ZipFile(sPath).use { zip ->
                zip.getEntry("assets/$filename")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
        } catch (e: IOException) {
            null
        }

        @JvmStatic
        protected fun load(filename: String) {
            val soPaths = Build.SUPPORTED_ABIS.map { abi -> "$sPath!/lib/$abi/lib$filename.so" }
            val errors = mutableListOf<Throwable>()

            for (soPath in soPaths) {
                try {
                    System.load(soPath)
                    return
                } catch (e: UnsatisfiedLinkError) {
                    errors.add(e)
                }
            }

            val linkError = UnsatisfiedLinkError("Failed to load native library: $filename")
            errors.forEach { linkError.addSuppressed(it) }
            log(linkError)
            throw linkError
        }

        fun runOnUiThread(r: Runnable) {
            sHandler.post(r)
        }

        @JvmStatic
        fun findClass(className: String): Class<*> = XposedHelpers.findClass(className)

        // ---- chain -> classic callback adapter --------------------------------

        @JvmStatic
        fun install(origin: Executable, callback: XC_MethodHook): Unhook {
            val handle = framework.hook(origin)
                .setPriority(callback.priority)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val param = HookParam(chain.thisObject, chain.args.toTypedArray())

                        callback.beforeHookedMethod(param)
                        if (param.hasThrowable) throw param.throwable!!
                        // param.setResult(...) short-circuits the original method
                        if (param.hasResult) return param.result

                        val result = try {
                            chain.proceed(param.args)
                        } catch (t: Throwable) {
                            param.throwable = t
                            callback.afterHookedMethod(param)
                            if (param.hasResult) return param.result
                            throw param.throwable ?: t
                        }

                        param.result = result
                        callback.afterHookedMethod(param)
                        if (param.hasThrowable) throw param.throwable!!
                        return param.result
                    }
                })
            return Unhook(handle)
        }

        // ---- method resolution ------------------------------------------------

        private fun resolveMethod(
            clazz: Class<*>, methodName: String, parameterTypes: Array<out Any?>
        ): Method {
            XposedHelpers.findMethodExactIfExists(clazz, methodName, *parameterTypes)?.let { return it }
            // DexKit rules only carry a method name. If that method is unambiguous, use it.
            clazz.declaredMethods.filter { it.name == methodName }.singleOrNull()?.let {
                it.isAccessible = true
                return it
            }
            throw NoSuchMethodError("$clazz#$methodName(${parameterTypes.joinToString()})")
        }

        private fun resolveConstructor(clazz: Class<*>, parameterTypes: Array<out Any?>): Constructor<*> {
            val types = parameterTypes.map { XposedHelpers.toClass(it) }.toTypedArray()
            val exact = clazz.declaredConstructors.firstOrNull {
                it.parameterTypes.contentEquals(types)
            }
            val chosen = exact ?: clazz.declaredConstructors.singleOrNull()
                ?: throw NoSuchMethodError("$clazz.<init>(${parameterTypes.joinToString()})")
            chosen.isAccessible = true
            return chosen
        }

        private fun before(origin: Executable, hook: (HookParam) -> Unit): Unhook =
            install(origin, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) = hook(param)
            })

        private fun after(origin: Executable, priority: Int, hook: (HookParam) -> Unit): Unhook =
            install(origin, object : XC_MethodHook(priority) {
                override fun afterHookedMethod(param: HookParam) = hook(param)
            })

        private fun replace(origin: Executable, hook: (HookParam) -> Any?): Unhook =
            install(origin, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    param.result = hook(param)
                }
            })

        // ---- public hook helpers (unchanged signatures) ------------------------

        @JvmStatic
        fun hookBeforeMethod(
            className: String, methodName: String, vararg parameterTypes: Any?,
            beforeHook: (HookParam) -> Unit
        ): Unhook = before(resolveMethod(findClass(className), methodName, parameterTypes), beforeHook)

        @JvmStatic
        fun hookAfterMethod(
            className: String, methodName: String, vararg parameterTypes: Any?,
            afterHook: (HookParam) -> Unit
        ): Unhook = after(resolveMethod(findClass(className), methodName, parameterTypes), XCallback.PRIORITY_DEFAULT, afterHook)

        @JvmStatic
        fun hookAfterMethodPriority(
            className: String, methodName: String, vararg parameterTypes: Any?,
            afterHook: (HookParam) -> Unit
        ): Unhook = after(resolveMethod(findClass(className), methodName, parameterTypes), XCallback.PRIORITY_LOWEST, afterHook)

        @JvmStatic
        fun hookReplaceMethod(
            className: String, methodName: String, vararg parameterTypes: Any?,
            replaceHook: (HookParam) -> Any?
        ): Unhook = replace(resolveMethod(findClass(className), methodName, parameterTypes), replaceHook)

        @JvmStatic
        fun hookBeforeMethod(
            clazz: Class<*>, methodName: String, vararg parameterTypes: Any?,
            beforeHook: (HookParam) -> Unit
        ): Unhook = before(resolveMethod(clazz, methodName, parameterTypes), beforeHook)

        @JvmStatic
        fun hookAfterMethod(
            clazz: Class<*>, methodName: String, vararg parameterTypes: Any?,
            afterHook: (HookParam) -> Unit
        ): Unhook = after(resolveMethod(clazz, methodName, parameterTypes), XCallback.PRIORITY_DEFAULT, afterHook)

        @JvmStatic
        fun hookReplaceMethod(
            clazz: Class<*>, methodName: String, vararg parameterTypes: Any?,
            replaceHook: (HookParam) -> Any?
        ): Unhook = replace(resolveMethod(clazz, methodName, parameterTypes), replaceHook)

        @JvmStatic
        fun hookBeforeMethod(method: Method, beforeHook: (HookParam) -> Unit): Unhook =
            before(method, beforeHook)

        @JvmStatic
        fun hookAfterMethod(method: Method, afterHook: (HookParam) -> Unit): Unhook =
            after(method, XCallback.PRIORITY_DEFAULT, afterHook)

        @JvmStatic
        fun hookReplaceMethod(method: Method, replaceHook: (HookParam) -> Any?): Unhook =
            replace(method, replaceHook)

        @JvmStatic
        fun hookBeforeConstructor(
            className: String, vararg parameterTypes: Any?,
            beforeHook: (HookParam) -> Unit
        ): Unhook = before(resolveConstructor(findClass(className), parameterTypes), beforeHook)

        @JvmStatic
        fun hookAfterConstructor(
            className: String, vararg parameterTypes: Any?,
            afterHook: (HookParam) -> Unit
        ): Unhook = after(resolveConstructor(findClass(className), parameterTypes), XCallback.PRIORITY_DEFAULT, afterHook)

        @JvmStatic
        fun hookBeforeConstructor(
            clazz: Class<*>, vararg parameterTypes: Any?,
            beforeHook: (HookParam) -> Unit
        ): Unhook = before(resolveConstructor(clazz, parameterTypes), beforeHook)

        @JvmStatic
        fun hookAfterConstructor(
            clazz: Class<*>, vararg parameterTypes: Any?,
            afterHook: (HookParam) -> Unit
        ): Unhook = after(resolveConstructor(clazz, parameterTypes), XCallback.PRIORITY_DEFAULT, afterHook)
    }
}
