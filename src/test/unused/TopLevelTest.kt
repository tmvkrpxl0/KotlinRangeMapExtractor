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

var lambda: (Int) -> Unit = {
    run {

    }
}

fun lambda(i: Int): Unit {
    val a: IntArray = IntArray(1) { 0 }
}

/**
 * random javadoc
 */
fun <T> genericTest() {
    val a: (T) -> T = {
        val b = 123
        it
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

@kotlin.jvm.JvmName("test")
fun testQulifier() {

}