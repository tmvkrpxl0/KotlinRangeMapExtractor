@file:JvmName("Test")
package test

import java.io.File
import java.net.http.HttpClient
import java.util.function.Consumer
import java.util.function.Function

// Line comment
/* block comment */

val ref = 1

val a = """
    a
    b
    $ref
""".trimIndent()

val test1: File = File("a")
val test2: File = java.io.File("a")

class ByTest: CharSequence by String.toString()

var lambda: (Int) -> Unit = { i ->
    val t = File("a")
    val u = java.io.File("a")
}

fun lambda(i: Int): Unit {
    val a: IntArray = IntArray(1) {0}
}
fun <T> genericTest() {
    val a: (T) -> T = {
        val b = 123
        it
    }
}

open class SuperClass(a: Int)

class DelegationTest : SuperClass {
    constructor(b: String): super(2) {

    }
}

/**
 * random javadoc
 */
public class A() {
    val test = 1
    init {
        ref+2
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
    val (a, b, c, d) = listOf(1, 2, 3, 4)

    for (i in 1..100) {
        println(i)
    }

    java.io.File.listRoots()
}

fun A.a() {
    java.io.File.separator
    ""
    'a'
    "asdf"
}
