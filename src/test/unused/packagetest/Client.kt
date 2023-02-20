package packagetest

import packagetest.lib.Request
import packagetest.lib.handle

fun send() {
    val request = Request(arrayOf(127, 0, 0, 1).toIntArray() to 25565)
    handle(request)

    Request.Internal()
    Request.Internal
}