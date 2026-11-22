package android.app

import net.bytebuddy.ByteBuddy
import net.bytebuddy.android.AndroidClassLoadingStrategy
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.implementation.bind.annotation.AllArguments
import net.bytebuddy.implementation.bind.annotation.Origin
import net.bytebuddy.implementation.bind.annotation.RuntimeType
import net.bytebuddy.implementation.bind.annotation.This
import net.bytebuddy.matcher.ElementMatchers
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object ITaskStackListenerProxy {
    private val dexDir = File("/data/system/reYAMF")

    private val handlers = ConcurrentHashMap<ITaskStackListener, (Array<Any?>, Method) -> Any?>()
    private val generatedClasses = ConcurrentHashMap<ClassLoader, Class<*>>()

    /**
     * Dispatches to the handler registered for the instance that received the callback, so the
     * generated class is cached and shared by every floating window instead of writing a new
     * dex file per window.
     */
    class Interceptor {
        /**
         * The dispatcher map is keyed by the generated stub, so the stub's own `hashCode` is part
         * of every lookup. ByteBuddy intercepts `any()` method, which used to route that
         * `hashCode` straight back into the map: the proxy hashed itself to find its handler, and
         * the lookup hashed again, until the 8 MB stack was gone (StackOverflowError on the first
         * floating window). `Object`'s methods are answered here instead of being dispatched.
         */
        @RuntimeType
        fun intercept(
            @This self: ITaskStackListener,
            @AllArguments allArguments: Array<Any?>,
            @Origin method: Method
        ): Any? = when (method.name) {
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === allArguments.getOrNull(0)
            "toString" -> "ITaskStackListenerProxy\$Stub@${Integer.toHexString(System.identityHashCode(self))}"
            else -> handlers[self]?.invoke(allArguments, method)
        }
    }

    fun newInstance(
        classLoader: ClassLoader,
        intercept: (Array<Any?>, Method) -> Any?
    ): ITaskStackListener {
        val clazz = generatedClasses.getOrPut(classLoader) {
            dexDir.mkdirs()
            ByteBuddy()
                .subclass(ITaskStackListener.Stub::class.java)
                .method(ElementMatchers.any())
                .intercept(MethodDelegation.to(Interceptor()))
                .make()
                .load(classLoader, AndroidClassLoadingStrategy.Wrapping(dexDir))
                .loaded
        }
        @Suppress("UNCHECKED_CAST")
        return (clazz.getDeclaredConstructor().newInstance() as ITaskStackListener).also {
            handlers[it] = intercept
        }
    }
}
