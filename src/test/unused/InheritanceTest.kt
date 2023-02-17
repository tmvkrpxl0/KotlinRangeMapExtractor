open class InheritanceTestSuper {
    open fun run(a: Int) {
        val overriding: InheritanceTestOverriding = InheritanceTestOverriding()
        val nonOverriding: InheritanceTestNonOverriding = InheritanceTestNonOverriding()

        overriding.run(a)
        nonOverriding.run(a)
    }

    class InheritanceTestOverriding: InheritanceTestSuper() {
        override fun run(a: Int) {
            System.out.println("a")
            super.run(a)
        }
    }

    class InheritanceTestNonOverriding: InheritanceTestSuper()
}