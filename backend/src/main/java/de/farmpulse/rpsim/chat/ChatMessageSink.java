package de.farmpulse.rpsim.chat;

import de.farmpulse.rpsim.domain.NarrationJob;
import de.farmpulse.rpsim.narration.NarrationSink;
import de.farmpulse.rpsim.narration.PromptBuilder;
import de.farmpulse.rpsim.repository.ChatMessageRepository;
import org.springframework.stereotype.Component;

/** Roadmap V3.1 R31-D2: stores the narrated text of a chat message (the subject is only a keyword and dropped). */
@Component
public class ChatMessageSink implements NarrationSink {

    private final ChatMessageRepository messages;

    public ChatMessageSink(ChatMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    public String targetType() {
        return PromptBuilder.TARGET_CHAT;
    }

    @Override
    public void deliver(NarrationJob job, String subject, String body, boolean fallback) {
        messages.findById(job.getTargetId()).ifPresent(m -> {
            String text = body == null || body.isBlank() ? subject : body.strip();
            m.setText(text);
            m.setPending(false);
        });
    }
}
