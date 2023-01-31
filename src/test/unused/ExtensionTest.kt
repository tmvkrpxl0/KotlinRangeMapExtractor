
class BaseClass
fun BaseClass.a() {
    java.io.File.separator
    ""
    'a'
    "asdf"
}

var BaseClass.integer: Int
    get() = 0
    set(value) {
        println(value)
    }

fun extensionTest() {
    val b = BaseClass()
    println(b.integer)
    b.integer = 123
}