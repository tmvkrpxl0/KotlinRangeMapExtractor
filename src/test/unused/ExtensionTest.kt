
class BaseClass
fun BaseClass.a() {
    java.io.File.separator
    ""
    'a'
    "asdf"
}

var BaseClass.extended: Int
    get() = 0
    set(value) {
        println(value)
    }

fun extensionTest() {
    val b = BaseClass()
    println(b.extended)
    b.extended = 123
}