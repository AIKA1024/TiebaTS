@file:JvmName("XposedBridge")

package gm.tieba.tabswitch.compat

import gm.tieba.tabswitch.XposedContext
import java.lang.reflect.Method

/**
 * Legacy-shaped facade for logging and hooking. Modern API 102 removes
 * `de.robv.android.xposed.XposedBridge`; all calls are routed to [XposedContext].
 */
object XposedBridge {

    @JvmStatic
    fun log(message: String) = XposedContext.log(message)

    @JvmStatic
    fun log(t: Throwable?) = XposedContext.log(t)

    @JvmStatic
    fun hookMethod(method: Method, callback: XC_MethodHook): Unhook =
        XposedContext.install(method, callback)

    @JvmStatic
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook): Set<Unhook> =
        clazz.declaredMethods
            .filter { it.name == methodName }
            .map { XposedContext.install(it, callback) }
            .toSet()
}
