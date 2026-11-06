package gm.tieba.tabswitch.widget

import android.view.View
import android.widget.TextView
import gm.tieba.tabswitch.compat.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.util.getColor
import gm.tieba.tabswitch.util.getObjectField
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class NavigationBar(thisObject: Any) : XposedContext() {

    /**
     * On some Tieba 22.x activities the NavigationBar is no longer a direct field of the host;
     * it lives in a controller/holder object. Fall back to a shallow graph search instead of
     * throwing during preference-page construction.
     */
    private val mNavigationBar: Any? = runCatching {
        getObjectField(thisObject, "com.baidu.tbadk.core.view.NavigationBar")
    }.getOrNull() ?: findNestedNavigationBar(thisObject)

    private fun instanceFields(type: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredFields.asSequence().filterNot { Modifier.isStatic(it.modifiers) })
            current = current.superclass
        }
    }

    private fun read(field: Field, instance: Any): Any? = runCatching {
        field.isAccessible = true
        field.get(instance)
    }.getOrNull()

    private fun findNestedNavigationBar(root: Any): Any? {
        val target = runCatching {
            findClass("com.baidu.tbadk.core.view.NavigationBar")
        }.getOrNull() ?: return null

        val holders = instanceFields(root.javaClass).mapNotNull { read(it, root) }
        holders.firstOrNull(target::isInstance)?.let { return it }
        holders.forEach { holder ->
            instanceFields(holder.javaClass).forEach { field ->
                val value = read(field, holder)
                if (value != null && target.isInstance(value)) return value
            }
        }
        return null
    }

    fun addTextButton(text: String?, l: View.OnClickListener?) {
        val controlAlignClass = findClass("com.baidu.tbadk.core.view.NavigationBar\$ControlAlign")
        val horizontalRight = controlAlignClass.enumConstants.find { it.toString() == "HORIZONTAL_RIGHT" }
            ?: throw IllegalStateException("HORIZONTAL_RIGHT enum constant not found")
        val textView = XposedHelpers.callMethod(
            mNavigationBar,
            "addTextButton", horizontalRight, text, l
        ) as TextView
        textView.setTextColor(getColor("CAM_X0105"))
    }

    fun setTitleText(title: String?) {
        title?.let {
            XposedHelpers.callMethod(mNavigationBar, "setTitleText", it)
        }
    }

    fun setCenterTextTitle(title: String?) {
        title?.let {
            XposedHelpers.callMethod(mNavigationBar, "setCenterTextTitle", it)
        }
    }
}
