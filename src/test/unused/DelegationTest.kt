
class ByTest : CharSequence by DelegationTest.toString()
open class DelegationSuperClass(a: Int)

open class DelegationTest<A> public constructor(a: Int) : DelegationSuperClass(a) {
    companion object {

    }

    constructor(b: String) : this(2) {

    }
}