package p;

import java.util.List;
import java.util.function.Consumer;

class InP<T extends CharSequence, B extends List<B>> {
    int a = 2;
    public void t() {
        JavaTest.test();
        JavaTest.class.getName();
        new String();
        C.c(a);

        String[] array;

        Consumer<Integer> r = (b) -> {
            System.out.println(a + b);
        };
    }

    private strictfp abstract static class C extends InP{
        public static void c(int unused) {

        }
    }
}
