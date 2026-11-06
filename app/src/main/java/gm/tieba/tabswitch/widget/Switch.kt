package gm.tieba.tabswitch.widget

import android.os.Vibrator
import android.view.View
import gm.tieba.tabswitch.compat.XposedBridge
import gm.tieba.tabswitch.compat.XposedHelpers
import gm.tieba.tabswitch.compat.XposedHelpers.ClassNotFoundError
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.util.callMethod
import gm.tieba.tabswitch.util.getObjectField
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

class Switch : XposedContext() {

    var bdSwitch: View
    private var mMethods: Array<Method>

    /**
     * Tieba 22.x reshuffles BdSwitchView's methods. Upstream fell back to hard-coded indices
     * (mMethods[3], [6], [8], [11]) which silently call the wrong method after any rebuild.
     * Locate the ON/OFF enum field and probe void no-arg methods instead.
     */
    private var mStateField: Field? = null

    init {
        val cls = findClass("com.baidu.adp.widget.BdSwitchView.BdSwitchView")
        bdSwitch = XposedHelpers.newInstance(cls, getContext()) as View
        mMethods = cls.declaredMethods
        mStateField = cls.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && field.type.isEnum &&
                field.type.enumConstants?.map { it.toString() }
                    ?.containsAll(listOf("ON", "OFF")) == true
        }?.apply { isAccessible = true }
    }

    fun setOnSwitchStateChangeListener(l: InvocationHandler) {
        val clazz: Class<*> = try {
            findClass("com.baidu.adp.widget.BdSwitchView.BdSwitchView\$b")
        } catch (e: ClassNotFoundError) {
            findClass("com.baidu.adp.widget.BdSwitchView.BdSwitchView\$a")
        }
        val proxy = Proxy.newProxyInstance(sClassLoader, arrayOf(clazz), l)
        XposedHelpers.callMethod(bdSwitch, "setOnSwitchStateChangeListener", proxy)
    }

    fun isOn(): Boolean = try {
        XposedHelpers.callMethod(bdSwitch, "isOn") as Boolean
    } catch (e: NoSuchMethodError) {
        mStateField?.get(bdSwitch)?.toString() == "ON"
    }

    /** Try every void no-arg method until the observed state matches, then stop. */
    private fun applyFallbackState(targetOn: Boolean) {
        if (isOn() == targetOn) return
        mMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
                method.returnType == Void.TYPE
        }.forEach { method ->
            runCatching { callMethod(method, bdSwitch) }
            if (isOn() == targetOn) return
        }
        XposedBridge.log("TiebaTS: unable to change BdSwitchView state to $targetOn")
    }

    fun changeState() {
        try {
            XposedHelpers.callMethod(bdSwitch, "changeState")
        } catch (e: NoSuchMethodError) {
            applyFallbackState(!isOn())
        }
    }

    fun turnOn() {
        try {
            XposedHelpers.callMethod(bdSwitch, "turnOn")
        } catch (e: NoSuchMethodError) {
            applyFallbackState(true)
        }
    }

    fun turnOff() {
        try {
            XposedHelpers.callMethod(bdSwitch, "turnOff")
        } catch (e: NoSuchMethodError) {
            applyFallbackState(false)
        }
    }

    fun getVibrator(): Vibrator? = getObjectField(bdSwitch, Vibrator::class.java)
}
