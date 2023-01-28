package test.f

import java.io.*

// Line comment
/* block comment */

val ref = 1

val a = """
    a
    b
    $ref
""".toLowerCase()

val test1: File = File("a")
val test2: File = java.io.File("a")

class ByTest : CharSequence by kotlin.String.toString()

var lambda: (Int) -> Unit = {
    String.toString()
    val t = File("a")
    val dump = 123
    t.run {

    }
    val u = java.io.File("a")
}

fun lambda(i: Int): Unit {
    val a: IntArray = IntArray(1) { 0 }
}

fun <T> genericTest() {
    val a: (T) -> T = {
        val b = 123
        it
    }
}

open class SuperClass(a: Int)

enum class EnumTest {
    A {
        fun run() {

        }
    },
    B
}

open class DelegationTest<A> public constructor(a: Int) : SuperClass(a) {
    companion object {
        fun static() {

        }
    }

    constructor(b: String) : this(2) {

    }
}

/**
 * random javadoc
 */
public class A {
    val test = 1
    val test2 = test + 123

    constructor() {
        ref - 2
    }

    init {
        ref + 2
    }

    public inner class Inner {

    }

    public class NotInner {

    }

    override fun toString(): String {
        return super.toString()
    }
}

@JvmName("test")
fun test() {
    A.Inner::class.java
    A.Inner::hashCode
    val (a, b, c, d) = listOf(1, 2, 3, 4)

    for (i in 1..100) {
        println(i)
    }

    java.io.File.listRoots()
}

@kotlin.jvm.JvmName("test")
fun testQulifier() {

}

val String.a: Int
    get() = 0

fun A.a() {
    java.io.File.separator
    ""
    'a'
    "asdf"
}
