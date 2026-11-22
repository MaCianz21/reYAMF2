package com.mja.reyamf.common.reflect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReflectionTest {

    private open class Base {
        private val baseField: Int = 41
        fun baseMethod(): String = "base"
    }

    private class Fixture(private val offset: Int) : Base() {
        val visible: String = "visible"

        fun noArgs(): String = "no-args"

        fun withInt(value: Int): Int = value + offset

        fun withTypes(text: String, flag: Boolean): String = "$text:$flag"

        @Suppress("unused")
        private fun privateMethod(): String = "private-value"
    }

    private class Constructed(val value: Int)

    @Test
    fun `args and argTypes keep order and size`() {
        val values = args("a", 1, null)
        assertEquals(3, values.size)
        assertEquals("a", values[0])
        assertEquals(1, values[1])
        assertNull(values[2])

        val types = argTypes(String::class.java, Integer.TYPE)
        assertEquals(2, types.size)
        assertSame(String::class.java, types[0])
        assertSame(Integer.TYPE, types[1])
    }

    @Test
    fun `getObject reads private fields including superclasses`() {
        val fixture = Fixture(1)
        assertEquals("visible", fixture.getObject("visible"))
        assertEquals(41, fixture.getObjectAs<Int>("baseField"))
    }

    @Test
    fun `getObject returns null for a missing field instead of throwing`() {
        assertNull(Fixture(1).getObject("doesNotExist"))
    }

    @Test
    fun `invokeMethod resolves by name and arity`() {
        val fixture = Fixture(10)
        assertEquals("no-args", fixture.invokeMethod("noArgs"))
        assertEquals(17, fixture.invokeMethod("withInt", 7))
        assertEquals("private-value", fixture.invokeMethod("privateMethod"))
    }

    @Test
    fun `invokeMethod resolves by explicit parameter types`() {
        val fixture = Fixture(0)
        assertEquals("t:true", fixture.invokeMethod("withTypes", args("t", true), argTypes(String::class.java, java.lang.Boolean.TYPE)))
        assertEquals(5, fixture.invokeMethod("withInt", args(5), argTypes(Integer.TYPE)))
    }

    @Test
    fun `invokeMethodAs casts the result and supports generic types`() {
        val fixture = Fixture(2)
        val text: String = fixture.invokeMethodAs("noArgs")
        assertEquals("no-args", text)
        val number: Int = fixture.invokeMethodAs("withInt", args(8), argTypes(Integer.TYPE))
        assertEquals(10, number)
    }

    @Test
    fun `invokeMethodAutoAs is the same lookup`() {
        assertEquals("no-args", Fixture(0).invokeMethodAutoAs<String>("noArgs"))
    }

    @Test
    fun `newInstance builds through a declared constructor`() {
        val instance = Constructed::class.java.newInstance(args(11), argTypes(Integer.TYPE))
        assertEquals(11, instance.getObjectAs<Int>("value"))
    }

    @Test
    fun `findMethod walks superclasses and honours checkSuper`() {
        val found = Fixture::class.java.findMethod { name == "baseMethod" }
        assertEquals("baseMethod", found.name)

        val declaredOnly = Fixture::class.java.findMethodOrNull(checkSuper = false) { name == "baseMethod" }
        assertNull(declaredOnly)
    }

    @Test
    fun `loadClass resolves through the given loader and reports misses as null`() {
        assertSame(String::class.java, loadClass("java.lang.String", javaClass.classLoader))
        assertNull(loadClassOrNull("does.not.Exist", javaClass.classLoader))
    }
}
