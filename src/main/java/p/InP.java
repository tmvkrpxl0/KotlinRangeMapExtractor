package p;

import java.util.List;

class InP<T extends CharSequence, B extends List<B>> {
    int a = 2;
    public void t() {
        JavaTest.test();
        JavaTest.class.getName();
        new String();
        C.c(a);
    }

    private strictfp abstract static class C extends InP{
        public static void c(int unused) {

        }
    }
}
