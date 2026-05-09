package dev.morenomjc.gleifagent;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageInputI18n;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Route("")
@PageTitle("Chat")
public class ChatView extends VerticalLayout {

    private static final String WELCOME_MESSAGE_SESSION_KEY = "chat.welcome.message";
    private static final String WELCOME_MODEL_SESSION_KEY = "chat.welcome.model";
    private static final String WELCOME_FALLBACK = "Welcome! I am Gandalf. I can help you look up LEI details.";
    private static final String EMPTY_REPLY_FALLBACK = "I received an empty response. Please try again.";
    private static final String UNSUPPORTED_PROMPT_GENERIC_MESSAGE =
            "Unsupported request. Please provide one LEI code (20 alphanumeric characters).";
    private static final String AI_PROVIDER_ERROR_MESSAGE =
            "AI provider error. Please verify OPENAI/OpenRouter credentials and try again.";
    private static final Pattern BOLD_MARKDOWN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final String[] FUNNY_LOADING_VERBS = {
            "Consulting the crystal ball...",
            "Polishing the wizard staff...",
            "Reading ancient runes...",
            "Summoning better words...",
            "Negotiating with dragons...",
            "Stirring the potion...",
            "Aligning moonbeams...",
            "Untangling prophecy..."
    };

    private final RestClient restClient;
    private final String configuredModelName;
    private final VerticalLayout messagesLayout = new VerticalLayout();
    private final Scroller messagesScroller;
    private final MessageInput composer = new MessageInput();

    public ChatView(
            RestClient.Builder restClientBuilder,
            @Value("${server.port:8080}") int serverPort,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName
    ) {
        this.restClient = restClientBuilder.baseUrl("http://localhost:" + serverPort).build();
        this.configuredModelName = modelName;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        H2 title = new H2("GLEIF Agent Chat");
        add(title);

        Div notice = new Div();
        notice.setText("Gandalf is fighting the Balrog. Responses may be slow.");
        notice.getStyle().set("background", "#fff7ed");
        notice.getStyle().set("color", "#9a3412");
        notice.getStyle().set("border", "1px solid #fdba74");
        notice.getStyle().set("border-radius", "10px");
        notice.getStyle().set("padding", "8px 10px");
        notice.getStyle().set("font-size", "0.85rem");
        notice.getStyle().set("box-sizing", "border-box");
        notice.setWidthFull();
        add(notice);

        messagesLayout.setWidthFull();
        messagesLayout.setPadding(false);
        messagesLayout.setSpacing(true);
        messagesLayout.getStyle().set("background", "#f8fafc");
        messagesLayout.getStyle().set("border", "1px solid #e2e8f0");
        messagesLayout.getStyle().set("border-radius", "14px");
        messagesLayout.getStyle().set("padding", "16px");
        this.messagesScroller = new Scroller(messagesLayout);
        this.messagesScroller.setSizeFull();
        add(this.messagesScroller);
        expand(this.messagesScroller);

        MessageInputI18n i18n = new MessageInputI18n();
        i18n.setMessage("Ask Gandalf anything...");
        i18n.setSend("Send");
        composer.setI18n(i18n);
        composer.setWidthFull();
        composer.addSubmitListener(event -> sendMessage(event.getValue()));
        add(composer);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        String cachedWelcomeMessage = (String) VaadinSession.getCurrent().getAttribute(WELCOME_MESSAGE_SESSION_KEY);
        if (cachedWelcomeMessage != null && !cachedWelcomeMessage.isBlank()) {
            String cachedWelcomeModel = (String) VaadinSession.getCurrent().getAttribute(WELCOME_MODEL_SESSION_KEY);
            addAssistantMessage(
                    cachedWelcomeModel == null || cachedWelcomeModel.isBlank() ? configuredModelName : cachedWelcomeModel,
                    cachedWelcomeMessage
            );
            return;
        }
        requestAssistantMessage(buildWelcomePrompt(), true);
    }

    private void sendMessage(String rawMessage) {
        String userMessage = rawMessage == null ? "" : rawMessage.trim();
        if (userMessage.isEmpty()) {
            return;
        }

        addUserMessage(userMessage);
        requestAssistantMessage(userMessage, false);
    }

    private void requestAssistantMessage(String prompt, boolean welcomeMessage) {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }

        PendingAssistantMessage pendingMessage = addPendingGandalfMessage();
        boolean disableComposer = true;
        if (disableComposer) {
            composer.setEnabled(false);
        }

        CompletableFuture.supplyAsync(() -> callChatEndpoint(prompt))
                .whenComplete((response, throwable) -> ui.access(() -> {
                    if (throwable != null) {
                        pendingMessage.model.setText("");
                        renderAssistantText(pendingMessage.message, resolveUiErrorMessage(throwable));
                    } else {
                        String model = response.model() == null || response.model().isBlank()
                                ? configuredModelName
                                : response.model();
                        String message = sanitizeReply(response.reply(), welcomeMessage);

                        pendingMessage.model.setText(model);
                        renderAssistantText(pendingMessage.message, message);
                        if (welcomeMessage) {
                            VaadinSession.getCurrent().setAttribute(WELCOME_MODEL_SESSION_KEY, model);
                            VaadinSession.getCurrent().setAttribute(WELCOME_MESSAGE_SESSION_KEY, message);
                        }
                    }
                    if (disableComposer) {
                        composer.setEnabled(true);
                    }
                    scrollToBottom();
                }));
    }

    private Chat.ChatSuccessResponse callChatEndpoint(String prompt) {
        Chat.ChatRequest request = new Chat.ChatRequest(prompt);
        try {
            Chat.ChatSuccessResponse response = restClient.post()
                    .uri("/chat")
                    .body(request)
                    .retrieve()
                    .body(Chat.ChatSuccessResponse.class);
            if (response == null) {
                throw new IllegalStateException("Chat API returned an empty response.");
            }
            return response;
        } catch (RestClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            if (statusCode == 422) {
                throw new UiChatException(UNSUPPORTED_PROMPT_GENERIC_MESSAGE);
            }
            if (statusCode == 502) {
                throw new UiChatException(AI_PROVIDER_ERROR_MESSAGE);
            }
            if (statusCode == 400) {
                throw new UiChatException("Invalid chat request. Please try again.");
            }
            throw new UiChatException("Unable to reach chat API right now.");
        }
    }

    private String sanitizeReply(String reply, boolean welcomeMessage) {
        if (reply == null || reply.isBlank()) {
            return welcomeMessage ? WELCOME_FALLBACK : EMPTY_REPLY_FALLBACK;
        }
        return reply;
    }

    private void addUserMessage(String message) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setJustifyContentMode(JustifyContentMode.END);

        Div bubble = new Div();
        bubble.getStyle().set("max-width", "75%");
        bubble.getStyle().set("background", "#dbeafe");
        bubble.getStyle().set("color", "#0f172a");
        bubble.getStyle().set("padding", "10px 12px");
        bubble.getStyle().set("border-radius", "12px");
        bubble.getStyle().set("white-space", "pre-wrap");
        bubble.setText(message);

        row.add(bubble);
        messagesLayout.add(row);
        scrollToBottom();
    }

    private PendingAssistantMessage addPendingGandalfMessage() {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setJustifyContentMode(JustifyContentMode.START);

        Div bubble = new Div();
        bubble.getStyle().set("max-width", "75%");
        bubble.getStyle().set("background", "#ffffff");
        bubble.getStyle().set("color", "#0f172a");
        bubble.getStyle().set("padding", "10px 12px");
        bubble.getStyle().set("border-radius", "12px");
        bubble.getStyle().set("border", "1px solid #e2e8f0");

        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setPadding(false);
        header.setSpacing(false);
        header.setAlignItems(Alignment.CENTER);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);

        Span name = new Span("Gandalf");
        name.getStyle().set("font-weight", "600");

        Span model = new Span("");
        model.getStyle().set("font-size", "0.74rem");
        model.getStyle().set("color", "#94a3b8");
        model.getStyle().set("margin-left", "16px");
        model.getStyle().set("text-align", "right");

        Div message = new Div();
        message.setText(funnyLoadingText());
        message.getStyle().set("white-space", "pre-wrap");
        message.getStyle().set("margin-top", "6px");

        header.add(name, model);
        bubble.add(header, message);
        row.add(bubble);
        messagesLayout.add(row);
        scrollToBottom();

        return new PendingAssistantMessage(model, message);
    }

    private void addAssistantMessage(String model, String message) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setJustifyContentMode(JustifyContentMode.START);

        Div bubble = new Div();
        bubble.getStyle().set("max-width", "75%");
        bubble.getStyle().set("background", "#ffffff");
        bubble.getStyle().set("color", "#0f172a");
        bubble.getStyle().set("padding", "10px 12px");
        bubble.getStyle().set("border-radius", "12px");
        bubble.getStyle().set("border", "1px solid #e2e8f0");

        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setPadding(false);
        header.setSpacing(false);
        header.setAlignItems(Alignment.CENTER);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);

        Span name = new Span("Gandalf");
        name.getStyle().set("font-weight", "600");

        Span modelLabel = new Span(model);
        modelLabel.getStyle().set("font-size", "0.74rem");
        modelLabel.getStyle().set("color", "#94a3b8");
        modelLabel.getStyle().set("margin-left", "16px");
        modelLabel.getStyle().set("text-align", "right");

        Div messageText = new Div();
        renderAssistantText(messageText, message);
        messageText.getStyle().set("white-space", "pre-wrap");
        messageText.getStyle().set("margin-top", "6px");

        header.add(name, modelLabel);
        bubble.add(header, messageText);
        row.add(bubble);
        messagesLayout.add(row);
        scrollToBottom();
    }

    private void addSystemMessage(String message) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setJustifyContentMode(JustifyContentMode.CENTER);
        Span text = new Span(message);
        text.getStyle().set("font-size", "0.8rem");
        text.getStyle().set("color", "#64748b");
        row.add(text);
        messagesLayout.add(row);
        scrollToBottom();
    }

    private void scrollToBottom() {
        messagesScroller.getElement().executeJs("this.scrollTop = this.scrollHeight;");
    }

    private String funnyLoadingText() {
        int index = ThreadLocalRandom.current().nextInt(FUNNY_LOADING_VERBS.length);
        return FUNNY_LOADING_VERBS[index];
    }

    private String buildWelcomePrompt() {
        LocalDate today = LocalDate.now();
        return """
                Provide a short welcome message and include which holiday is on this exact date.
                Date: %s
                If there is no major holiday on that date, say that clearly. Then say Welcome!.
                Keep it under 2 sentences.
                """.formatted(today);
    }

    private String resolveUiErrorMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof UiChatException uiChatException) {
            return uiChatException.getMessage();
        }
        return "Unable to reach chat API right now.";
    }

    private void renderAssistantText(Div target, String text) {
        target.getElement().setProperty("innerHTML", toSafeHtml(text));
    }

    private String toSafeHtml(String text) {
        if (text == null) {
            return "";
        }

        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
        String withBold = BOLD_MARKDOWN.matcher(escaped).replaceAll("<strong>$1</strong>");
        return withBold.replace("\n", "<br/>");
    }

    private record PendingAssistantMessage(Span model, Div message) {
    }

    private static class UiChatException extends RuntimeException {
        private UiChatException(String message) {
            super(message);
        }
    }
}
