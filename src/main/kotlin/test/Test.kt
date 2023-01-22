@file:JvmName("test")
package test

// Line comment
/* block comment */

val ref = 1

val a = """
    a
    b
    $ref
""".trimIndent()

/**
 * SHFoidhszoifsehfo
 */
public class A() {
    init {
        ref+2
    }
    public inner class Inner {

    }
    public class NotInner {

    }
}

fun test() {
    for (i in 1..100) {
        println(i)
    }
    String.hashCode()
}