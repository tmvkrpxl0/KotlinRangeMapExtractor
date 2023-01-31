public class JavaInheritanceTestSuper {
    public void run(int a) {
        JavaInheritanceTestOverriding overriding = new JavaInheritanceTestOverriding();
        JavaInheritanceTestNonOverriding nonOverriding = new JavaInheritanceTestNonOverriding();

        overriding.run(a);
        nonOverriding.run(a);
    }

    public static class JavaInheritanceTestOverriding extends JavaInheritanceTestSuper {

        @Override
        public void run(int a) {
            System.out.println("a");
            super.run(a);
        }
    }

    public static class JavaInheritanceTestNonOverriding extends JavaInheritanceTestSuper {
    }
}
