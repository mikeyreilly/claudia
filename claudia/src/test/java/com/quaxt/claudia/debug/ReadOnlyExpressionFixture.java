package com.quaxt.claudia.debug;

/** Isolated target for safe inspection; any method/field mutation would be visible in calls/numbers. */
public final class ReadOnlyExpressionFixture {
    public static int calls;
    private final int[] numbers = {40, 42};
    private final String label = new String("ready==go");

    public static void main(String[] args) {
        new ReadOnlyExpressionFixture().inspect();
        System.out.println("READ_ONLY_DONE calls=" + calls);
    }

    private void inspect() {
        int index = 1;
        byte byteIndex = 0;
        short shortIndex = 1;
        char charIndex = 0;
        long longIndex = 1;
        double fractionalIndex = 0.5;
        boolean flag = true;
        char letter = 'A';
        float nan = Float.NaN;
        double infinity = Double.POSITIVE_INFINITY;
        int[] numbers = this.numbers;
        int[] indices = {1, 0};
        int[][] matrix = {{1, 2}, {3, 4}};
        Item[] items = {new Item("alpha", 40), new Item("beta", 42)};
        Item[][] nestedItems = {{items[0]}, {items[1]}};
        Item alias = items[0];
        Item nullable = null;
        int[] nullArray = null;
        String first = new String("ready==go");
        String second = new String("ready==go");
        String escaped = "quote\" slash\\ line\n tab\t Ω";
        int answer = numbers[index]; // READ_ONLY_EXPRESSION_STOP
        System.out.println("READ_ONLY_ANSWER=" + answer); // READ_ONLY_EXPRESSION_AFTER
    }

    private int touch() { calls++; return 99; }

    private record Item(String name, int count) {}
}
