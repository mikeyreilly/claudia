package com.quaxt.claudia;

import com.quaxt.claudia.agent.QuestionBroker;
import com.quaxt.claudia.cli.QuestionComponent;
import com.quaxt.claudia.tui.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestionComponentTest {
    @Test void selectionRequiresSubmissionAndCustomAnswersSupportUnicodeEditing() {
        var request = new QuestionBroker.Request("q", "main", "Choose", List.of(
                new QuestionBroker.Option("First", "Fast"), new QuestionBroker.Option("Second", "Flexible")));
        var view = new QuestionComponent(request, "Main", () -> true);
        var component = view.component();
        assertFalse(component.complete.getAsBoolean());
        view.handle(new TuiInput.Key(TuiInput.KeyType.DOWN, null));
        view.handle(new TuiInput.Key(TuiInput.KeyType.ENTER, null));
        assertEquals("Second", component.result.get().answer());

        view = new QuestionComponent(request, "Main", () -> true);
        component = view.component();
        view.handle(new TuiInput.Key(TuiInput.KeyType.PASTE, "My \uD83D\uDE00 answer"));
        view.handle(new TuiInput.Key(TuiInput.KeyType.HOME, null));
        view.handle(new TuiInput.Key(TuiInput.KeyType.DELETE, null));
        view.handle(new TuiInput.Key(TuiInput.KeyType.END, null));
        view.handle(new TuiInput.Key(TuiInput.KeyType.ENTER, null));
        assertEquals("y \uD83D\uDE00 answer", component.result.get().answer());
    }

    @Test void blankAnswersWaitAndExternalCancellationClosesTheView() {
        var pending = new AtomicBoolean(true);
        var request = new QuestionBroker.Request("q", "main", "Question\u001b[2J", List.of());
        var view = new QuestionComponent(request, "Main", pending::get);
        view.handle(new TuiInput.Key(TuiInput.KeyType.ENTER, null));
        assertFalse(view.component().complete.getAsBoolean());
        assertTrue(view.render(new TuiFrame(20, 8)).stream().noneMatch(line -> line.contains("\u001b")));
        pending.set(false);
        assertTrue(view.component().complete.getAsBoolean());
        assertEquals("declined", view.component().result.get().status());
    }
}
