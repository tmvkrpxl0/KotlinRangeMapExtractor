import java.io.Serializable;

public abstract class SuperClass<T> {
    protected T s;
    abstract public T handle(T data);

    public void dummy() {
        T a;
    }

    public static class Impl extends SuperClass<Serializable> {

        @Override
        public Serializable handle(Serializable data) {
            s = data;
            return data;
        }

        public static class Impl2 extends Impl {
            @Override
            public Serializable handle(Serializable data) {
                if (data != null) {
                    this.handle(null);
                    super.handle(null);
                }
                return null;
            }
        }
    }
}