package com.mja.reyamf.common.reflect

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Plain `java.lang.reflect` helpers for the shared module code.
 *
 * The shared code used to borrow these from EzXHelper 1.0.3. That library references
 * `de.robv.android.xposed`, so it cannot be linked into the modern (Vector) flavour at all.
 * Both flavours therefore compile against this file, while each flavour still brings its own
 * hook framework: 1.0.3 for the legacy entry points, libxposed + EzXHelper 3.x for the vector ones.
 *
 * The semantics mirror the 1.0.3 helpers the call sites were written against: name/arity lookup
 * including superclasses, fields made accessible on first use, and primitives matched by box type.
 */

fun args(vararg values: Any?): Array<Any?> = arrayOf(*values)

fun argTypes(vararg types: Class<*>): Array<Class<*>> = arrayOf(*types)

fun loadClass(name: String, classLoader: ClassLoader): Class<*> =
    Class.forName(name, false, classLoader)

fun loadClassOrNull(name: String, classLoader: ClassLoader): Class<*>? =
    runCatching { loadClass(name, classLoader) }.getOrNull()

private fun Class<*>.hierarchy(): Sequence<Class<*>> =
    generateSequence(this) { it.superclass }

fun Class<*>.findFieldOrNull(name: String): Field? = hierarchy()
    .mapNotNull { clazz -> runCatching { clazz.getDeclaredField(name) }.getOrNull() }
    .firstOrNull()
    ?.apply { isAccessible = true }

fun Any.getObject(name: String): Any? = javaClass.findFieldOrNull(name)?.get(this)

inline fun <reified T> Any.getObjectAs(name: String): T = getObject(name) as T

fun Class<*>.findMethodOrNull(
    checkSuper: Boolean = true,
    predicate: Method.() -> Boolean
): Method? {
    val classes = if (checkSuper) hierarchy() else sequenceOf(this)
    for (clazz in classes) {
        for (method in clazz.declaredMethods) {
            if (method.predicate()) return method.apply { isAccessible = true }
        }
    }
    return null
}

fun Class<*>.findMethod(
    checkSuper: Boolean = true,
    predicate: Method.() -> Boolean
): Method = findMethodOrNull(checkSuper, predicate)
    ?: throw NoSuchMethodException("no method on $name matches the predicate")

private fun Class<*>.acceptsArgument(argument: Any?): Boolean {
    if (argument == null) return !isPrimitive
    if (!isPrimitive) return isInstance(argument)
    return when (this) {
        java.lang.Boolean.TYPE -> argument is Boolean
        java.lang.Byte.TYPE -> argument is Byte
        java.lang.Character.TYPE -> argument is Char
        java.lang.Short.TYPE -> argument is Short
        Integer.TYPE -> argument is Int
        java.lang.Long.TYPE -> argument is Long
        java.lang.Float.TYPE -> argument is Float
        java.lang.Double.TYPE -> argument is Double
        else -> false
    }
}

private fun Any.methodByNameAndArity(name: String, arguments: Array<out Any?>): Method {
    val candidates = javaClass.hierarchy()
        .flatMap { clazz -> clazz.declaredMethods.asSequence() }
        .filter { it.name == name && it.parameterCount == arguments.size }
        .toList()
    val method = candidates.firstOrNull { candidate ->
        candidate.parameterTypes.withIndex().all { (index, type) -> type.acceptsArgument(arguments[index]) }
    } ?: candidates.firstOrNull()
    ?: throw NoSuchMethodException("$name/${arguments.size} on ${javaClass.name}")
    return method.apply { isAccessible = true }
}

private fun Any.methodByNameAndTypes(name: String, types: Array<Class<*>>): Method {
    for (clazz in javaClass.hierarchy()) {
        for (method in clazz.declaredMethods) {
            if (method.name != name) continue
            if (method.parameterTypes.size != types.size) continue
            if (method.parameterTypes.contentEquals(types)) return method.apply { isAccessible = true }
        }
    }
    throw NoSuchMethodException("$name(${types.joinToString { it.name }}) on ${javaClass.name}")
}

fun Any.invokeMethod(name: String, vararg params: Any?): Any? =
    methodByNameAndArity(name, params).invoke(this, *params)

fun Any.invokeMethod(name: String, params: Array<Any?>, types: Array<Class<*>>): Any? =
    methodByNameAndTypes(name, types).invoke(this, *params)

@Suppress("UNCHECKED_CAST")
fun <T> Any.invokeMethodAs(name: String, vararg params: Any?): T =
    invokeMethod(name, *params) as T

@Suppress("UNCHECKED_CAST")
fun <T> Any.invokeMethodAs(name: String, params: Array<Any?>, types: Array<Class<*>>): T =
    invokeMethod(name, params, types) as T

/**
 * Same lookup as [invokeMethod]: the 1.0.3 "auto" variants only differ in error handling, and the
 * call sites here always resolve by name and arity.
 */
fun Any.invokeMethodAuto(name: String, vararg params: Any?): Any? =
    invokeMethod(name, *params)

fun <T> Any.invokeMethodAutoAs(name: String, vararg params: Any?): T =
    invokeMethodAs(name, *params)

fun Class<*>.newInstance(params: Array<Any?>, types: Array<Class<*>>): Any =
    getDeclaredConstructor(*types)
        .apply { isAccessible = true }
        .newInstance(*params)

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

fun runOnMainThread(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}
