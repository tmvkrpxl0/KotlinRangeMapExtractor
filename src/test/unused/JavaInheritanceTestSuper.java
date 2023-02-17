public class JavaInheritanceTestSuper {
    /**
     * t
     * @param a
     */
    public void run(int a) {
        JavaInheritanceTestOverriding overriding = new JavaInheritanceTestOverriding();
        JavaInheritanceTestNonOverriding nonOverriding = new JavaInheritanceTestNonOverriding();

        overriding.run(a);
        nonOverriding.run(a);
    }

    public static class JavaInheritanceTestOverriding extends JavaInheritanceTestSuper {

        @Override
        public void run(int a) {
            java.lang.System.out.println("a");
            super.run(a);
        }
    }

    public static class JavaInheritanceTestNonOverriding extends JavaInheritanceTestSuper {
    }
}
