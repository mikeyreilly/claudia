package com.quaxt.codingagent.cli;

import com.quaxt.codingagent.agent.QuestionBroker;
import com.quaxt.codingagent.terminal.Cells;
import com.quaxt.codingagent.tui.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.quaxt.codingagent.CodingAgentCli.stripAnsi;

/** Single-choice question with an always-available, editable custom answer. */
public final class QuestionComponent {
    private final QuestionBroker.Request request;
    private final String agentName;
    private final BooleanSupplier pending;
    private final StringBuilder custom = new StringBuilder();
    private int selected;
    private int cursor;
    private boolean done;
    private QuestionBroker.Answer answer = QuestionBroker.Answer.declined();

    public QuestionComponent(QuestionBroker.Request request, String agentName, BooleanSupplier pending) {
        this.request = request; this.agentName = agentName; this.pending = pending;
    }

    public TuiComponent<QuestionBroker.Answer> component() {
        return new TuiComponent<>(this::render, this::handle, () -> done || !pending.getAsBoolean(), () -> answer);
    }

    public void handle(TuiInput input) {
        if (!(input instanceof TuiInput.Key key) || done) return;
        int customIndex = request.options().size();
        switch (key.type) {
            case ESCAPE, CANCEL, EXIT -> done = true;
            case UP -> selected = Math.max(0, selected - 1);
            case DOWN -> selected = Math.min(customIndex, selected + 1);
            case TAB -> selected = (selected + 1) % (customIndex + 1);
            case ENTER -> {
                String text = selected == customIndex ? custom.toString() : request.options().get(selected).label();
                if (!text.isBlank()) { answer = new QuestionBroker.Answer("answered", text); done = true; }
            }
            case CHARACTER, PASTE -> {
                selected = customIndex;
                String text = clean(key.text == null ? "" : key.text);
                custom.insert(cursor, text); cursor += text.length();
            }
            case LEFT -> { if (cursor > 0) cursor = custom.offsetByCodePoints(cursor, -1); }
            case RIGHT -> { if (cursor < custom.length()) cursor = custom.offsetByCodePoints(cursor, 1); }
            case HOME -> cursor = 0;
            case END -> cursor = custom.length();
            case BACKSPACE -> {
                if (selected == customIndex && cursor > 0) {
                    int previous = custom.offsetByCodePoints(cursor, -1);
                    custom.delete(previous, cursor); cursor = previous;
                }
            }
            case DELETE -> {
                if (selected == customIndex && cursor < custom.length()) custom.delete(cursor, custom.offsetByCodePoints(cursor, 1));
            }
            case CLEAR -> { custom.setLength(0); cursor = 0; }
            default -> { }
        }
    }

    public List<String> render(TuiFrame frame) {
        int width = Math.max(1, frame.width);
        List<String> body = new ArrayList<>();
        addWrapped(body, "Question from " + agentName, width);
        addWrapped(body, request.question(), width);
        body.add("");
        int selectedLine = body.size();
        for (int i = 0; i <= request.options().size(); i++) {
            if (i == selected) selectedLine = body.size();
            String prefix = i == selected ? "> " : "  ";
            if (i < request.options().size()) {
                var option = request.options().get(i);
                addWrapped(body, prefix + option.label(), width);
                if (!option.description().isBlank()) addWrapped(body, "    " + option.description(), width);
            } else {
                addWrapped(body, prefix + (request.options().isEmpty() ? "Answer: " : "Custom answer: ")
                        + custom.substring(0, cursor) + (i == selected ? "|" : "") + custom.substring(cursor), width);
            }
        }
        int available = Math.max(1, frame.height - 1);
        int start = Math.max(0, Math.min(selectedLine - available + 1, body.size() - available));
        List<String> lines = new ArrayList<>(body.subList(start, Math.min(body.size(), start + available)));
        String footer = "Up/Down choose; type a custom answer; Enter submits; Esc declines";
        lines.add(Cells.truncate(footer, width));
        return lines;
    }

    private static void addWrapped(List<String> lines, String text, int width) {
        for (String line : clean(text).split("\n", -1)) {
            if (line.isEmpty()) lines.add("");
            else lines.addAll(Cells.wrap(line, width));
        }
    }

    private static String clean(String text) { return stripAnsi(text).replaceAll("[\\p{Cntrl}&&[^\\n]]", ""); }
}
