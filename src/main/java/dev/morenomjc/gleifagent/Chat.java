package dev.morenomjc.gleifagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/")
public class Chat {

    private static final Pattern LEI_PATTERN = Pattern.compile("\\b[A-Z0-9]{20}\\b");
    private static final String INTERNAL_WELCOME_PROMPT_PREFIX =
            "Provide a short welcome message and include which holiday is on this exact date.";
    private static final String UNSUPPORTED_PROMPT_GENERIC_MESSAGE =
            "Unsupported request. This chat currently supports LEI detail lookup for one LEI code per request.";

    private final ChatClient chatClient;
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String configuredModel;

    public record ChatRequest(
            @NotBlank(message = "message is required")
            @Size(max = 4000, message = "message must be at most 4000 characters")
            String message
    ) {}

    public record ChatSuccessResponse(
            String reply,
            String model,
            String responseId,
            Instant timestamp
    ) {}

    public record ChatErrorResponse(
            String error,
            String message,
            Instant timestamp
    ) {}

    @PostMapping("chat")
    public ResponseEntity<ChatSuccessResponse> chat(@Valid @RequestBody ChatRequest chatRequest) {
        validateSupportedPrompt(chatRequest.message());

        try {
            ChatResponse chatResponse = this.chatClient.prompt()
                    .user(chatRequest.message())
                    .call()
                    .chatResponse();
            String reply = Optional.ofNullable(chatResponse.getResult())
                    .map(result -> result.getOutput())
                    .map(output -> output.getText())
                    .orElse("");
            String model = resolveModel(chatResponse);
            String responseId = Optional.ofNullable(chatResponse.getMetadata())
                    .map(metadata -> metadata.getId())
                    .orElse(null);

            log.info("Processed chat request successfully. model={}, responseId={}", model, responseId);
            return ResponseEntity.ok(new ChatSuccessResponse(
                    reply,
                    model,
                    responseId,
                    Instant.now()
            ));
        } catch (Exception e) {
            log.error("Error processing chat request via Spring AI: {}", e.getMessage(), e);
            throw new ChatApiException("Failed to communicate with the AI provider.", e);
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ChatErrorResponse> handleValidationError(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request body.");

        return ResponseEntity.badRequest().body(new ChatErrorResponse(
                "VALIDATION_ERROR",
                message,
                Instant.now()
        ));
    }

    @ExceptionHandler(ChatApiException.class)
    public ResponseEntity<ChatErrorResponse> handleChatApiException(ChatApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ChatErrorResponse(
                "AI_PROVIDER_ERROR",
                ex.getMessage(),
                Instant.now()
        ));
    }

    @ExceptionHandler(UnsupportedPromptException.class)
    public ResponseEntity<ChatErrorResponse> handleUnsupportedPrompt(UnsupportedPromptException ex) {
        log.info("Rejected unsupported prompt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ChatErrorResponse(
                "UNSUPPORTED_PROMPT",
                UNSUPPORTED_PROMPT_GENERIC_MESSAGE,
                Instant.now()
        ));
    }

    private String resolveModel(ChatResponse chatResponse) {
        return Optional.ofNullable(chatResponse.getMetadata())
                .map(metadata -> metadata.getModel())
                .filter(model -> !model.isBlank())
                .orElse(configuredModel);
    }

    private void validateSupportedPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new UnsupportedPromptException("Prompt is empty.");
        }

        if (prompt.startsWith(INTERNAL_WELCOME_PROMPT_PREFIX)) {
            return;
        }

        String normalized = prompt.toUpperCase(Locale.ROOT);
        Matcher matcher = LEI_PATTERN.matcher(normalized);

        int matchCount = 0;
        while (matcher.find()) {
            matchCount++;
        }

        if (matchCount == 0) {
            throw new UnsupportedPromptException(
                    "Only LEI detail lookup is supported right now. Provide one 20-character LEI code."
            );
        }

        if (matchCount > 1) {
            throw new UnsupportedPromptException(
                    "Please provide only one LEI per request for now."
            );
        }
    }

    private static class ChatApiException extends RuntimeException {
        private ChatApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class UnsupportedPromptException extends RuntimeException {
        private UnsupportedPromptException(String message) {
            super(message);
        }
    }
}
