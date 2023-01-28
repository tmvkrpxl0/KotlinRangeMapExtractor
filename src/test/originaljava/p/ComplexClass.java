package p;

import p.a.A;

import java.io.Serializable;
import java.util.List;
import java.util.function.Consumer;

class ComplexClass<T extends CharSequence> extends SimpleClass implements Serializable {
    public static final String dummy = null;
    public static String dummy2 = null;
    static {
        int a;
        System.out.println("Hello");
        dummy2 = "dummy2";
        dummy2.length();
        java.io.File f = new java.io.File("asdf");
    }
    int a = 2;
    public static void staticMethod() {

    }

    public void t(int param) {
        p.SimpleClass.class.getName();
        SimpleClass.class.getName();
        new String();

        A a = new A();

        a.a = 123;

        String[] array;
        new C(2);

        Consumer<Integer> r = (b) -> {
        };
    }

    public strictfp static class C extends ComplexClass {
        public C(int a) {

        }

        public static void run() {

        }
    }

    public ComplexClass() {

    }

    public ComplexClass(int a) {

    }

    public ComplexClass(String delegating) {
        this(12);
    }
}
