package smoke.java;

public class JavaApp {
    private final Greeter greeter;

    public JavaApp(Greeter greeter) {
        this.greeter = greeter;
    }

    public String run(String name) {
        return greeter.greet(name);
    }
}
