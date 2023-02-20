package packagetest.lib

private var count = 0

fun handle(request: Request) {
    println("received ${request.senderString}, count: $count")
    count++
}

data class Request(val sender: Pair<IntArray, Short>) {
    val senderString: String
        get() = "${sender.first[0]}.${sender.first[1]}.${sender.first[2]}.${sender.first[3]}:${sender.second}"

    class Internal {
        companion object C {

        }

    }
}