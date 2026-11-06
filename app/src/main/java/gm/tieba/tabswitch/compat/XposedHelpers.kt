@file:JvmName("XposedHelpers")

package gm.tieba.tabswitch.compat

import gm.tieba.tabswitch.XposedContext
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-contained replacement for `de.robv.android.xposed.XposedHelpers`.
 *
 * A module declaring `targetApiVersion=102` gets no legacy Xposed classes at all, so every
 * reflection helper has to live inside the module. Method names and signatures intentionally
 * mirror the old API; all existing Hookers only need their import changed.
 */
object XposedHelpers {

    class ClassNotFoundError : Error {
        constructor(message: String?) : super(message)
        constructor(cause: Throwable?) : super(cause)
    }

    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val methodCache = ConcurrentHashMap<String, Method>()

    private fun loader(): ClassLoader = XposedContext.sClassLoader

    private fun primitive(name: String): Class<*>? = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType
        "byte" -> Byte::class.javaPrimitiveType
        "char" -> Char::class.javaPrimitiveType
        "short" -> Short::class.javaPrimitiveType
        "int" -> Int::class.javaPrimitiveType
        "long" -> Long::class.javaPrimitiveType
        "float" -> Float::class.javaPrimitiveType
        "double" -> Double::class.javaPrimitiveType
        "void" -> Void::class.javaPrimitiveType
        else -> null
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Void::class.javaPrimitiveType -> Void::class.javaObjectType
        else -> type
    }

    /** Accepts a Class, a class name (including primitives), or an instance used as a type hint. */
    @JvmStatic
    fun toClass(spec: Any?): Class<*> = when (spec) {
        is Class<*> -> spec
        is String -> primitive(spec) ?: try {
            Class.forName(spec, false, loader())
        } catch (e: ClassNotFoundException) {
            throw ClassNotFoundError(e)
        } catch (e: LinkageError) {
            throw ClassNotFoundError(e)
        }
        null -> throw ClassNotFoundError("null parameter type")
        else -> spec.javaClass
    }

    // ---- class lookup -------------------------------------------------------

    @JvmStatic
    @JvmOverloads
    fun findClass(className: String, classLoader: ClassLoader? = null): Class<*> = try {
        Class.forName(className, false, classLoader ?: loader())
    } catch (e: ClassNotFoundException) {
        throw ClassNotFoundError(e)
    } catch (e: LinkageError) {
        throw ClassNotFoundError(e)
    }

    @JvmStatic
    @JvmOverloads
    fun findClassIfExists(className: String, classLoader: ClassLoader? = null): Class<*>? = try {
        findClass(className, classLoader)
    } catch (ignored: ClassNotFoundError) {
        null
    }

    // ---- fields -------------------------------------------------------------

    private fun findField(clazz: Class<*>, fieldName: String): Field {
        val key = clazz.name + "#" + fieldName
        fieldCache[key]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val f = current.getDeclaredField(fieldName)
                f.isAccessible = true
                fieldCache[key] = f
                return f
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldError("$clazz#$fieldName")
    }

    /**
     * Must stay nullable: the original Java API returned a platform-typed Object, and most Purge
     * rules branch on `== null`. Declaring a non-nullable return makes Kotlin inject an
     * Intrinsics.checkNotNull() that throws whenever the field really is null.
     */
    @JvmStatic
    fun getObjectField(instance: Any?, fieldName: String?): Any? = try {
        findField(instance!!.javaClass, fieldName!!).get(instance)
    } catch (e: IllegalAccessException) {
        throw IllegalAccessError(e.message)
    }

    @JvmStatic
    fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
        try {
            findField(instance!!.javaClass, fieldName).set(instance, value)
        } catch (e: IllegalAccessException) {
            throw IllegalAccessError(e.message)
        }
    }

    @JvmStatic
    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? =
        findField(clazz, fieldName).get(null)

    @JvmStatic
    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        findField(clazz, fieldName).set(null, value)
    }

    @JvmStatic
    fun getIntField(instance: Any?, fieldName: String): Int =
        findField(instance!!.javaClass, fieldName).getInt(instance)

    @JvmStatic
    fun getBooleanField(instance: Any?, fieldName: String): Boolean =
        findField(instance!!.javaClass, fieldName).getBoolean(instance)

    @JvmStatic
    fun getStaticIntField(clazz: Class<*>, fieldName: String): Int =
        findField(clazz, fieldName).getInt(null)

    @JvmStatic
    fun getStaticBooleanField(clazz: Class<*>, fieldName: String): Boolean =
        findField(clazz, fieldName).getBoolean(null)

    @JvmStatic
    fun setStaticBooleanField(clazz: Class<*>, fieldName: String, value: Boolean) {
        findField(clazz, fieldName).setBoolean(null, value)
    }

    /** Legacy signature: returns a [Field] so Kotlin's `field[instance]` indexing still works. */
    @JvmStatic
    fun findFirstFieldByExactType(clazz: Class<*>?, type: Class<*>): Field {
        var current = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.type == type }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        throw NoSuchFieldError("No field of type $type in $clazz")
    }

    // ---- methods ------------------------------------------------------------

    private fun allMethods(clazz: Class<*>): List<Method> {
        val out = ArrayList<Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            out.addAll(current.declaredMethods)
            current = current.superclass
        }
        return out
    }

    private fun typeHints(args: Array<out Any?>): List<Class<*>?> =
        args.map { if (it is Class<*>) it else it?.javaClass }

    private fun score(actual: Array<Class<*>>, wanted: List<Class<*>?>): Int {
        // newInstance() calls this for every declared constructor, so arity must be checked here.
        if (actual.size != wanted.size) return -1
        var total = 0
        for (i in actual.indices) {
            val a = actual[i]
            val w = wanted[i]
            val score = when {
                w == null -> if (a.isPrimitive) return -1 else 1
                a == w -> 3
                boxed(a).isAssignableFrom(boxed(w)) -> 2
                else -> return -1
            }
            total += score
        }
        return total
    }

    @JvmStatic
    fun findMethodBestMatch(clazz: Class<*>, methodName: String?, vararg args: Any?): Method {
        val wanted = typeHints(args)
        var best: Method? = null
        var bestScore = -1
        for (m in allMethods(clazz)) {
            if (m.name != methodName || m.parameterTypes.size != wanted.size) continue
            val s = score(m.parameterTypes, wanted)
            if (s > bestScore) {
                best = m
                bestScore = s
            }
        }
        best?.let {
            it.isAccessible = true
            return it
        }
        throw NoSuchMethodError("$clazz#$methodName(${wanted.joinToString()})")
    }

    private fun findMethodExact(clazz: Class<*>, methodName: String, types: List<Class<*>>): Method {
        allMethods(clazz).firstOrNull {
            it.name == methodName && it.parameterTypes.contentEquals(types.toTypedArray())
        }?.let {
            it.isAccessible = true
            return it
        }
        throw NoSuchMethodError("$clazz#$methodName(${types.joinToString { it.name }})")
    }

    @JvmStatic
    fun findMethodExactIfExists(
        clazz: Class<*>, methodName: String, vararg parameterTypes: Any?
    ): Method? = runCatching {
        findMethodExact(clazz, methodName, parameterTypes.map { toClass(it) })
    }.getOrNull()

    @JvmStatic
    fun findMethodExactIfExists(
        className: String, classLoader: ClassLoader, methodName: String, vararg parameterTypes: Any?
    ): Method? = runCatching {
        findMethodExact(findClass(className, classLoader), methodName, parameterTypes.map { toClass(it) })
    }.getOrNull()

    private fun invoke(method: Method, instance: Any?, args: Array<out Any?>): Any? {
        method.isAccessible = true
        return try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        } catch (e: IllegalAccessException) {
            throw IllegalAccessError(e.message)
        }
    }

    @JvmStatic
    fun callMethod(instance: Any?, methodName: String?, vararg args: Any?): Any? =
        invoke(findMethodBestMatch(instance!!.javaClass, methodName, *args), instance, args)

    @JvmStatic
    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        invoke(findMethodBestMatch(clazz, methodName, *args), null, args)

    @JvmStatic
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val wanted = typeHints(args)
        val ctor: Constructor<*> = clazz.declaredConstructors.firstOrNull {
            score(it.parameterTypes, wanted) >= 0
        } ?: throw NoSuchMethodError("$clazz.<init>(${wanted.joinToString()})")
        ctor.isAccessible = true
        return try {
            ctor.newInstance(*args)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        } catch (e: IllegalAccessException) {
            throw IllegalAccessError(e.message)
        }
    }

    // ---- hook helpers (used by the Java Hookers) ----------------------------

    @JvmStatic
    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg paramsAndCallback: Any?): Unhook {
        val callback = paramsAndCallback.lastOrNull() as? XC_MethodHook
            ?: throw IllegalArgumentException("last argument must be an XC_MethodHook")
        val types = paramsAndCallback.dropLast(1).map { toClass(it) }
        return XposedContext.install(findMethodExact(clazz, methodName, types), callback)
    }

    @JvmStatic
    fun findAndHookConstructor(clazz: Class<*>, vararg paramsAndCallback: Any?): Unhook {
        val callback = paramsAndCallback.lastOrNull() as? XC_MethodHook
            ?: throw IllegalArgumentException("last argument must be an XC_MethodHook")
        val types = paramsAndCallback.dropLast(1).map { toClass(it) }
        val ctor = clazz.declaredConstructors.firstOrNull {
            it.parameterTypes.contentEquals(types.toTypedArray())
        } ?: throw NoSuchMethodError("$clazz.<init>(${types.joinToString { it.name }})")
        return XposedContext.install(ctor, callback)
    }
}
