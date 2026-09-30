package com.quaxt.codingagent.debug;

import com.sun.jdi.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** A bounded inspection grammar, not a Java interpreter. Never invokes methods or writes target state. */
final class ReadOnlyExpression {
    private static final int MAX_LENGTH = 256, MAX_DEPTH = 16;

    private ReadOnlyExpression() {}

    /** Literal strings stay in the debugger; even evaluating one does not allocate a target String. */
    record Result(Value value, String literalString) {
        static Result of(Value value) { return new Result(value, null); }
        boolean isNull() { return value == null && literalString == null; }
        boolean isString() { return literalString != null || value instanceof StringReference; }
        boolean isObject() { return literalString != null || value instanceof ObjectReference; }
        String string() { return literalString != null ? literalString : ((StringReference) value).value(); }
    }

    static final class Failure extends RuntimeException {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }

    private static Failure fail(String code, String message) { return new Failure(code, message); }

    static void validate(String expression) { new Parser(expression).parse(); }
    static Result evaluate(StackFrame frame, String expression) { return new Parser(expression).parse().read(frame); }

    private interface Node { Result read(StackFrame frame); }
    private interface Access { Result read(StackFrame frame, Result receiver); }

    private record Literal(String kind, String text) implements Node {
        @Override public Result read(StackFrame frame) {
            VirtualMachine vm = frame.virtualMachine();
            return switch (kind) {
                case "null" -> Result.of(null);
                case "boolean" -> Result.of(vm.mirrorOf(Boolean.parseBoolean(text)));
                case "string" -> new Result(null, text);
                default -> {
                    try {
                        if (text.contains(".")) {
                            double number = Double.parseDouble(text);
                            if (!Double.isFinite(number)) throw fail("invalid_expression", "Numeric literal is out of range");
                            yield Result.of(vm.mirrorOf(number));
                        }
                        long number = Long.parseLong(text);
                        yield Result.of(number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                                ? vm.mirrorOf((int) number) : vm.mirrorOf(number));
                    } catch (NumberFormatException invalid) {
                        throw fail("invalid_expression", "Numeric literal is out of range: " + text);
                    }
                }
            };
        }
    }

    private record Path(String root, List<Access> accesses) implements Node {
        @Override public Result read(StackFrame frame) {
            Result result;
            if (root.equals("this")) result = Result.of(frame.thisObject());
            else {
                try {
                    List<LocalVariable> variables = frame.visibleVariables().stream().filter(v -> v.name().equals(root)).toList();
                    if (variables.size() != 1) throw fail("variable_unavailable", "Variable " + root + " is missing or ambiguous; compile with local-variable debug information");
                    result = Result.of(frame.getValue(variables.getFirst()));
                } catch (AbsentInformationException missing) {
                    throw fail("variable_unavailable", "Local variable metadata is unavailable");
                }
            }
            for (Access access : accesses) result = access.read(frame, result);
            return result;
        }
    }

    private record FieldAccess(String name) implements Access {
        @Override public Result read(StackFrame frame, Result receiver) {
            if (receiver.isNull()) throw fail("null_reference", "Cannot read field " + name + " from null");
            if (receiver.value() instanceof ArrayReference array && name.equals("length"))
                return Result.of(frame.virtualMachine().mirrorOf(array.length()));
            if (!(receiver.value() instanceof ObjectReference object))
                throw fail("invalid_expression", "Cannot read field " + name + " from a primitive value");
            Field field = object.referenceType().fieldByName(name);
            if (field == null) throw fail("field_not_found", "No field " + name + " on " + object.referenceType().name());
            return Result.of(field.isStatic() ? field.declaringType().getValue(field) : object.getValue(field));
        }
    }

    private record IndexAccess(Node expression) implements Access {
        @Override public Result read(StackFrame frame, Result receiver) {
            if (receiver.isNull()) throw fail("null_reference", "Cannot index a null array");
            if (!(receiver.value() instanceof ArrayReference array))
                throw fail("invalid_expression", "Array indexing requires an array value");
            Value value = expression.read(frame).value();
            int index = switch (value) {
                case ByteValue b -> b.value();
                case ShortValue s -> s.value();
                case CharValue c -> c.value();
                case IntegerValue i -> i.value();
                case null, default -> throw fail("invalid_expression", "Array index must be a byte, short, char, or int value");
            };
            int length = array.length();
            if (index < 0 || index >= length)
                throw fail("index_out_of_bounds", "Array index " + index + " is out of bounds for length " + length);
            return Result.of(array.getValue(index));
        }
    }

    private record Comparison(Node left, String operator, Node right) implements Node {
        @Override public Result read(StackFrame frame) {
            Result first = left.read(frame), second = right.read(frame);
            boolean equality;
            Integer order = null;
            if (first.isNull() || second.isNull()) equality = first.isNull() && second.isNull();
            else if (first.isString() && second.isString()) equality = first.string().equals(second.string());
            else if (first.isObject() && second.isObject()) {
                equality = first.value() instanceof ObjectReference a && second.value() instanceof ObjectReference b
                        && a.uniqueID() == b.uniqueID();
            } else if (first.value() instanceof BooleanValue a && second.value() instanceof BooleanValue b) {
                equality = a.value() == b.value();
            } else if (numeric(first.value()) && numeric(second.value())) {
                order = number((PrimitiveValue) first.value()).compareTo(number((PrimitiveValue) second.value()));
                equality = order == 0;
            } else throw fail("invalid_expression", "Comparison operands have incompatible types");
            if (order == null && !operator.equals("==") && !operator.equals("!="))
                throw fail("invalid_expression", "Ordering requires numeric operands");
            boolean match = switch (operator) {
                case "==" -> equality;
                case "!=" -> !equality;
                case "<" -> order < 0;
                case ">" -> order > 0;
                case "<=" -> order <= 0;
                default -> order >= 0;
            };
            return Result.of(frame.virtualMachine().mirrorOf(match));
        }
    }

    private static boolean numeric(Value value) { return value instanceof PrimitiveValue && !(value instanceof BooleanValue); }
    private static BigDecimal number(PrimitiveValue value) {
        if (value instanceof CharValue character) return BigDecimal.valueOf(character.value());
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException invalid) { throw fail("invalid_expression", "Numeric comparison requires finite operands"); }
    }

    /** Parsing completes before any JDI read, including validation of every array subscript. */
    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            if (text.length() > MAX_LENGTH) throw fail("unsafe_expression", "Read-only expressions are limited to " + MAX_LENGTH + " characters");
            this.text = text;
        }

        Node parse() {
            Node node = operand(0);
            whitespace();
            String operator = null;
            for (String candidate : List.of("==", "!=", "<=", ">=", "<", ">")) {
                if (text.startsWith(candidate, position)) {
                    position += candidate.length();
                    operator = candidate;
                    break;
                }
            }
            if (operator != null) node = new Comparison(node, operator, operand(0));
            whitespace();
            if (position != text.length()) throw unsupported();
            return node;
        }

        private Node operand(int depth) {
            if (depth > MAX_DEPTH) throw fail("unsafe_expression", "Read-only array subscripts are limited to " + MAX_DEPTH + " nesting levels");
            whitespace();
            if (position == text.length()) throw unsupported();
            char first = text.charAt(position);
            if (first == '"') return new Literal("string", string());
            if (first == '-' || digit(first)) return new Literal("number", number());
            String name = identifier();
            if (name.equals("true") || name.equals("false")) return new Literal("boolean", name);
            if (name.equals("null")) return new Literal("null", name);
            List<Access> accesses = new ArrayList<>();
            while (true) {
                if (take('.')) accesses.add(new FieldAccess(identifier()));
                else if (take('[')) {
                    accesses.add(new IndexAccess(operand(depth + 1)));
                    if (!take(']')) throw unsupported();
                } else break;
            }
            return new Path(name, List.copyOf(accesses));
        }

        private String identifier() {
            whitespace();
            int start = position;
            if (position == text.length() || !Character.isJavaIdentifierStart(text.charAt(position))) throw unsupported();
            position++;
            while (position < text.length() && Character.isJavaIdentifierPart(text.charAt(position))) position++;
            return text.substring(start, position);
        }

        private String number() {
            int start = position;
            if (text.charAt(position) == '-') position++;
            int digits = position;
            while (position < text.length() && digit(text.charAt(position))) position++;
            if (position == digits) throw unsupported();
            if (position < text.length() && text.charAt(position) == '.') {
                position++;
                digits = position;
                while (position < text.length() && digit(text.charAt(position))) position++;
                if (position == digits) throw unsupported();
            }
            return text.substring(start, position);
        }

        private String string() {
            position++;
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return value.toString();
                if (c < ' ') throw unsupported();
                if (c != '\\') { value.append(c); continue; }
                if (position == text.length()) throw unsupported();
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\' -> value.append(escaped);
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (text.length() - position < 4) throw unsupported();
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int hex = Character.digit(text.charAt(position++), 16);
                            if (hex < 0) throw unsupported();
                            code = (code << 4) | hex;
                        }
                        value.append((char) code);
                    }
                    default -> throw unsupported();
                }
            }
            throw unsupported();
        }

        private boolean take(char expected) {
            whitespace();
            if (position < text.length() && text.charAt(position) == expected) { position++; return true; }
            return false;
        }

        private void whitespace() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
        private static boolean digit(char c) { return c >= '0' && c <= '9'; }
        private Failure unsupported() {
            return fail("unsafe_expression", "Unsupported read-only syntax at character " + (position + 1)
                    + "; only literals, local/this field paths, array subscripts/length, and simple comparisons are supported (no calls or mutation)");
        }
    }
}
