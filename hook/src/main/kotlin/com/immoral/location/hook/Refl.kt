package com.immoral.location.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Reflection helpers. Everything here is defensive: nothing throws, misses return null/false.
 *
 * We deliberately avoid `de.robv.android.xposed.XposedHelpers.getObjectField/callMethod` on hot
 * paths and instead cache [Method] / [Field] objects — the location report path runs for every
 * fix of the whole device.
 */
object Refl {

    // ---------------------------------------------------------------- classes

    fun findClass(name: String, loader: ClassLoader? = null): Class<*>? {
        try {
            return Class.forName(name, false, loader ?: Log::class.java.classLoader)
        } catch (_: Throwable) {
        }
        try {
            return Class.forName(name, false, Refl::class.java.classLoader)
        } catch (_: Throwable) {
        }
        try {
            return Class.forName(name)
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * Like [findClass] but tries every plausible class loader: the caller-supplied one, the thread
     * context loader, the system loader and its parent, our own loader and the Xposed boot loader.
     * This matters for classes that live in an APEX jar (e.g. `com.android.server.wifi.*`), which
     * are not always reachable through the loaded-package class loader.
     */
    fun findClassAnywhere(name: String, loader: ClassLoader? = null): Class<*>? {
        val loaders = ArrayList<ClassLoader?>(6)
        loaders.add(loader)
        try {
            loaders.add(Thread.currentThread().contextClassLoader)
        } catch (_: Throwable) {
        }
        try {
            loaders.add(ClassLoader.getSystemClassLoader())
            loaders.add(ClassLoader.getSystemClassLoader()?.parent)
        } catch (_: Throwable) {
        }
        loaders.add(Refl::class.java.classLoader)
        try {
            loaders.add(XposedBridge.BOOTCLASSLOADER)
        } catch (_: Throwable) {
        }
        for (candidate in loaders) {
            try {
                return Class.forName(name, false, candidate)
            } catch (_: Throwable) {
            }
        }
        return findClass(name, loader)
    }

    /** Field of an instance (or of a Class object) by name, or null. */
    fun fieldOn(target: Any?, name: String): Field? =
        fieldOf(if (target is Class<*>) target else target?.javaClass, name)

    fun fieldOf(cls: Class<*>?, name: String): Field? {
        var c = cls ?: return null
        while (true) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: Throwable) {
            }
            c = c.superclass ?: return null
        }
    }

    /** First existing field out of [names] declared in cls or any superclass. */
    fun anyField(cls: Class<*>?, vararg names: String): Field? {
        for (n in names) fieldOf(cls, n)?.let { return it }
        return null
    }

    fun get(target: Any?, field: Field?): Any? {
        if (target == null || field == null) return null
        return try {
            field.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    fun get(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        return get(target, anyField(target.javaClass, *names))
    }

    fun getString(target: Any?, vararg names: String): String? = get(target, *names) as? String

    fun getInt(target: Any?, vararg names: String): Int {
        val v = get(target, *names)
        return (v as? Number)?.toInt() ?: 0
    }

    fun getLong(target: Any?, vararg names: String): Long {
        val v = get(target, *names)
        return (v as? Number)?.toLong() ?: 0L
    }

    fun setField(target: Any?, name: String, value: Any?): Boolean {
        val f = fieldOf(target?.javaClass, name) ?: return false
        return try {
            f.set(target, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- methods

    fun methodOf(cls: Class<*>?, name: String, paramCount: Int = -1): Method? {
        val candidates = methodsNamed(cls, name)
        return candidates.firstOrNull { paramCount < 0 || it.parameterTypes.size == paramCount } ?: candidates.firstOrNull()
    }

    fun methodsNamed(cls: Class<*>?, name: String): List<Method> {
        var c = cls ?: return emptyList()
        val out = ArrayList<Method>(4)
        while (true) {
            try {
                for (m in c.declaredMethods) if (m.name == name) out.add(m)
            } catch (_: Throwable) {
            }
            c = c.superclass ?: break
        }
        return out
    }

    /** All methods (declared + inherited) of [cls] matching [predicate]. */
    fun methodsWhere(cls: Class<*>?, predicate: (Method) -> Boolean): List<Method> {
        var c = cls ?: return emptyList()
        val out = ArrayList<Method>(8)
        val seen = HashSet<String>(16)
        while (true) {
            try {
                for (m in c.declaredMethods) {
                    val key = m.name + m.parameterTypes.joinToString(",") { it.name }
                    if (!seen.add(key)) continue
                    if (predicate(m)) out.add(m)
                }
            } catch (_: Throwable) {
            }
            c = c.superclass ?: break
        }
        return out
    }

    fun constructorsWhere(cls: Class<*>?, predicate: (Constructor<*>) -> Boolean): List<Constructor<*>> {
        return try {
            cls?.declaredConstructors?.filter(predicate) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun newInstance(ctor: Constructor<*>, vararg args: Any?): Any? {
        return try {
            ctor.isAccessible = true
            ctor.newInstance(*args)
        } catch (_: Throwable) {
            null
        }
    }

    fun invoke(method: Method?, target: Any?, vararg args: Any?): Any? {
        if (method == null) return null
        return try {
            if (!method.isAccessible) method.isAccessible = true
            method.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }
    }

    fun invokeStatic(method: Method?, vararg args: Any?): Any? = invoke(method, null, *args)

    // ---------------------------------------------------------------- hooking

    /** Hook a method, swallowing any installation failure. */
    fun hook(method: Method?, callback: XC_MethodHook, label: String): Boolean {
        if (method == null) {
            Log.hook(false, label, "method not found")
            return false
        }
        return try {
            method.isAccessible = true
            XposedBridge.hookMethod(method, callback)
            Log.hook(true, label + signatureOf(method))
            true
        } catch (t: Throwable) {
            Log.hook(false, label, t.javaClass.simpleName + ": " + t.message)
            false
        }
    }

    fun hookAll(target: Class<*>?, methodName: String, callback: XC_MethodHook, label: String): Int {
        if (target == null) {
            Log.hook(false, label, "class not found")
            return 0
        }
        return try {
            val hooks = XposedBridge.hookAllMethods(target, methodName, callback)
            if (hooks.isEmpty()) {
                Log.hook(false, label, "no method named $methodName")
                0
            } else {
                Log.hook(true, "$label.$methodName (${hooks.size} overloads)")
                hooks.size
            }
        } catch (t: Throwable) {
            Log.hook(false, label, t.javaClass.simpleName + ": " + t.message)
            0
        }
    }

    fun hookAllConstructors(target: Class<*>?, callback: XC_MethodHook, label: String): Int {
        if (target == null) return 0
        return try {
            val hooks = XposedBridge.hookAllConstructors(target, callback)
            Log.hook(true, "$label.<init> (${hooks.size})")
            hooks.size
        } catch (t: Throwable) {
            Log.hook(false, "$label.<init>", t.javaClass.simpleName)
            0
        }
    }

    fun signatureOf(m: Method): String {
        val sb = StringBuilder()
        sb.append('(')
        m.parameterTypes.forEachIndexed { idx, c ->
            if (idx > 0) sb.append(',')
            sb.append(simpleName(c))
        }
        sb.append(") -> ")
        sb.append(simpleName(m.returnType))
        return sb.toString()
    }

    fun simpleName(c: Class<*>): String = when {
        c.isArray -> simpleName(c.componentType!!) + "[]"
        else -> c.name.substringAfterLast('.')
    }

    fun isStatic(m: Method): Boolean = Modifier.isStatic(m.modifiers)

    /**
     * True when [m] takes exactly one parameter that is (or extends) the class named [className].
     * Used to find `xxx(LocationResult)`, `xxx(Location)`, `xxx(ScanResult)` without hard-typing
     * classes that do not exist on every supported API level.
     */
    fun takesOneOf(m: Method, vararg classNames: String): Boolean =
        m.parameterTypes.size == 1 && m.parameterTypes[0] != null &&
            classNames.any { m.parameterTypes[0].name == it }

    /** First argument whose class name contains [namePart] - for AIDL listener/callback params. */
    fun listenerArgument(args: Array<Any?>, namePart: String): Any? =
        args.firstOrNull { it != null && it.javaClass.name.contains(namePart) }

    /** Robolectric-free "is instance of the class with this name" (null-safe, class-missing-safe). */
    fun isInstanceOf(value: Any?, className: String): Boolean {
        if (value == null) return false
        val cls = findClass(className, value.javaClass.classLoader) ?: return false
        return cls.isInstance(value)
    }
}
