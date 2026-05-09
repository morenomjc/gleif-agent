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
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Route("")
@PageTitle("Chat")
public class ChatView extends VerticalLayout {

    private static final String WELCOME_MESSAGE_SESSION_KEY = "chat.welcome.message";
    private static final String WELCOME_MODEL_SESSION_KEY = "chat.welcome.model";
    private static final String WELCOME_TIMESTAMP_SESSION_KEY = "chat.welcome.timestamp";
    private static final String WELCOME_FALLBACK = "Welcome! I am Gandalf. I can help you look up LEI details.";
    private static final String EMPTY_REPLY_FALLBACK = "I received an empty response. Please try again.";
    private static final String UNSUPPORTED_PROMPT_FALLBACK_MESSAGE =
            "Unsupported request. This chat currently supports LEI detail lookup for one LEI code per request.";
    private static final String AI_PROVIDER_ERROR_MESSAGE =
            "AI provider error. Please verify OPENAI/OpenRouter credentials and try again.";
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
    private static final DateTimeFormatter CHAT_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a");
    private static final List<Extension> MARKDOWN_EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(MARKDOWN_EXTENSIONS)
            .build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder()
            .escapeHtml(true)
            .softbreak("<br/>")
            .extensions(MARKDOWN_EXTENSIONS)
            .build();

    private final RestClient restClient;
    private final String configuredModelName;
    private final VerticalLayout chatPanel = new VerticalLayout();
    private final VerticalLayout messagesLayout = new VerticalLayout();
    private final Div composerShell = new Div();
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
        setPadding(false);
        setSpacing(false);
        setAlignItems(Alignment.CENTER);
        getStyle().set("font-family", "\"Avenir Next\", \"Helvetica Neue\", sans-serif");
        getStyle().set("background",
                "radial-gradient(circle at 20% 10%, #1f2937 0%, #0f172a 45%, #020617 100%)");
        getStyle().set("padding", "24px 16px");
        getStyle().set("box-sizing", "border-box");

        chatPanel.setWidthFull();
        chatPanel.setHeightFull();
        chatPanel.setPadding(false);
        chatPanel.setSpacing(true);
        chatPanel.setMaxWidth("980px");
        chatPanel.addClassName("wizard-chat-panel");
        chatPanel.getStyle().set("background", "linear-gradient(180deg, #f8fafc 0%, #eef2ff 100%)");
        chatPanel.getStyle().set("border", "1px solid rgba(148, 163, 184, 0.35)");
        chatPanel.getStyle().set("border-radius", "22px");
        chatPanel.getStyle().set("padding", "20px");
        chatPanel.getStyle().set("box-shadow", "0 28px 65px rgba(2, 6, 23, 0.45)");
        chatPanel.getStyle().set("backdrop-filter", "blur(2px)");
        add(chatPanel);

        H2 title = new H2("GLEIF Agent Chat");
        title.getStyle().set("margin", "2px 0 4px");
        title.getStyle().set("font-size", "1.65rem");
        title.getStyle().set("font-family", "\"Avenir Next Condensed\", \"Gill Sans\", sans-serif");
        title.getStyle().set("letter-spacing", "0.02em");
        title.getStyle().set("color", "#0f172a");
        chatPanel.add(title);

        Div notice = new Div();
        notice.setText("Gandalf is fighting the Balrog. Responses may be slow.");
        notice.getStyle().set("background", "linear-gradient(90deg, #ffedd5 0%, #fef3c7 100%)");
        notice.getStyle().set("color", "#7c2d12");
        notice.getStyle().set("border", "1px solid #fdba74");
        notice.getStyle().set("border-radius", "12px");
        notice.getStyle().set("padding", "10px 12px");
        notice.getStyle().set("font-size", "0.85rem");
        notice.getStyle().set("font-weight", "600");
        notice.getStyle().set("box-sizing", "border-box");
        notice.setWidthFull();
        chatPanel.add(notice);

        messagesLayout.setWidthFull();
        messagesLayout.setPadding(false);
        messagesLayout.setSpacing(false);
        messagesLayout.addClassName("wizard-messages");
        messagesLayout.getStyle().set("background",
                "linear-gradient(180deg, rgba(15, 23, 42, 0.03) 0%, rgba(15, 23, 42, 0.08) 100%)");
        messagesLayout.getStyle().set("border", "1px solid #cbd5e1");
        messagesLayout.getStyle().set("border-radius", "16px");
        messagesLayout.getStyle().set("padding", "18px");
        messagesLayout.getStyle().set("gap", "12px");
        this.messagesScroller = new Scroller(messagesLayout);
        this.messagesScroller.setSizeFull();
        this.messagesScroller.getStyle().set("border-radius", "16px");
        chatPanel.add(this.messagesScroller);
        chatPanel.expand(this.messagesScroller);

        MessageInputI18n i18n = new MessageInputI18n();
        i18n.setMessage("Ask Gandalf ...");
        i18n.setSend("Send");
        composer.setI18n(i18n);
        composer.setWidthFull();
        composer.addClassName("wizard-composer");
        composer.getStyle().set("border", "0");
        composer.getStyle().set("border-radius", "12px");
        composer.getStyle().set("background", "transparent");
        composer.getStyle().set("padding", "0");
        composer.getStyle().set("box-shadow", "none");
        composer.getStyle().set("margin", "0");
        composer.addSubmitListener(event -> sendMessage(event.getValue()));

        composerShell.setWidthFull();
        composerShell.addClassName("wizard-composer-shell");
        composerShell.getStyle().set("background",
                "linear-gradient(180deg, rgba(15, 23, 42, 0.03) 0%, rgba(15, 23, 42, 0.08) 100%)");
        composerShell.getStyle().set("border", "1px solid #cbd5e1");
        composerShell.getStyle().set("border-radius", "16px");
        composerShell.getStyle().set("padding-top", "6px");
        composerShell.getStyle().set("padding-right", "8px");
        composerShell.getStyle().set("padding-bottom", "12px");
        composerShell.getStyle().set("padding-left", "8px");
        composerShell.getStyle().set("box-shadow", "0 8px 18px rgba(15, 23, 42, 0.08)");
        composerShell.getStyle().set("box-sizing", "border-box");
        composerShell.add(composer);
        chatPanel.add(composerShell);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        String cachedWelcomeMessage = (String) VaadinSession.getCurrent().getAttribute(WELCOME_MESSAGE_SESSION_KEY);
        if (cachedWelcomeMessage != null && !cachedWelcomeMessage.isBlank()) {
            String cachedWelcomeModel = (String) VaadinSession.getCurrent().getAttribute(WELCOME_MODEL_SESSION_KEY);
            String cachedWelcomeTimestamp = (String) VaadinSession.getCurrent().getAttribute(WELCOME_TIMESTAMP_SESSION_KEY);
            addAssistantMessage(
                    cachedWelcomeModel == null || cachedWelcomeModel.isBlank() ? configuredModelName : cachedWelcomeModel,
                    cachedWelcomeMessage,
                    cachedWelcomeTimestamp == null || cachedWelcomeTimestamp.isBlank()
                            ? formatTimestamp(Instant.now())
                            : cachedWelcomeTimestamp
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
                    String timestamp = formatTimestamp(throwable == null ? response.timestamp() : Instant.now());
                    pendingMessage.timestamp.setText(timestamp);
                    pendingMessage.loading.setVisible(false);
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
                            VaadinSession.getCurrent().setAttribute(WELCOME_TIMESTAMP_SESSION_KEY, timestamp);
                        }
                    }
                    if (disableComposer) {
                        composer.setEnabled(true);
                        if (!welcomeMessage) {
                            composer.focus();
                        }
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
                throw new UiChatException(resolveChatApiErrorMessage(ex, UNSUPPORTED_PROMPT_FALLBACK_MESSAGE));
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
        return reply.stripTrailing();
    }

    private void addUserMessage(String message) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setJustifyContentMode(JustifyContentMode.END);
        row.getStyle().set("margin", "0");

        Div bubble = new Div();
        bubble.getStyle().set("max-width", "75%");
        bubble.getStyle().set("background", "linear-gradient(160deg, #0f172a 0%, #1e293b 100%)");
        bubble.getStyle().set("color", "#e2e8f0");
        bubble.getStyle().set("padding", "12px 14px");
        bubble.getStyle().set("border-radius", "14px 14px 4px 14px");
        bubble.getStyle().set("border", "1px solid rgba(148, 163, 184, 0.25)");
        bubble.getStyle().set("box-shadow", "0 10px 24px rgba(15, 23, 42, 0.16)");
        Span messageText = new Span(message);
        messageText.getStyle().set("white-space", "pre-wrap");
        messageText.getStyle().set("display", "block");
        Span timestamp = createTimestampLabel(formatTimestamp(Instant.now()));
        timestamp.getStyle().set("text-align", "right");
        timestamp.getStyle().set("margin-top", "8px");
        timestamp.getStyle().set("display", "block");
        timestamp.getStyle().set("color", "#cbd5e1");
        bubble.add(messageText, timestamp);

        row.add(bubble);
        messagesLayout.add(row);
        scrollToBottom();
    }

    private PendingAssistantMessage addPendingGandalfMessage() {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setJustifyContentMode(JustifyContentMode.START);
        row.getStyle().set("margin", "0");

        Div bubble = new Div();
        bubble.getStyle().set("max-width", "75%");
        bubble.getStyle().set("background", "linear-gradient(180deg, #ffffff 0%, #f8fafc 100%)");
        bubble.getStyle().set("color", "#0f172a");
        bubble.getStyle().set("padding", "12px 14px");
        bubble.getStyle().set("border-radius", "14px 14px 14px 4px");
        bubble.getStyle().set("border", "1px solid #cbd5e1");
        bubble.getStyle().set("box-shadow", "0 10px 24px rgba(15, 23, 42, 0.08)");

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
        model.getStyle().set("margin-left", "10px");
        model.getStyle().set("text-align", "right");

        Div message = new Div();
        message.setText(funnyLoadingText());
        message.getStyle().set("white-space", "normal");
        message.getStyle().set("margin-top", "4px");

        Div loading = createThinkingLoader();
        HorizontalLayout rightMeta = new HorizontalLayout(model, loading);
        rightMeta.setPadding(false);
        rightMeta.setSpacing(true);
        rightMeta.setAlignItems(Alignment.CENTER);
        rightMeta.setJustifyContentMode(JustifyContentMode.END);

        HorizontalLayout footer = new HorizontalLayout();
        footer.setWidthFull();
        footer.setPadding(false);
        footer.setSpacing(false);
        footer.setAlignItems(Alignment.CENTER);
        footer.setJustifyContentMode(JustifyContentMode.START);
        footer.getStyle().set("margin-top", "4px");
        Span timestamp = createTimestampLabel("");

        footer.add(timestamp);
        header.add(name, rightMeta);
        bubble.add(header, message, footer);
        row.add(bubble);
        messagesLayout.add(row);
        scrollToBottom();

        return new PendingAssistantMessage(model, message, timestamp, loading);
    }

    private void addAssistantMessage(String model, String message, String timestamp) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setJustifyContentMode(JustifyContentMode.START);
        row.getStyle().set("margin", "0");

        Div bubble = new Div();
        bubble.getStyle().set("max-width", "75%");
        bubble.getStyle().set("background", "linear-gradient(180deg, #ffffff 0%, #f8fafc 100%)");
        bubble.getStyle().set("color", "#0f172a");
        bubble.getStyle().set("padding", "12px 14px");
        bubble.getStyle().set("border-radius", "14px 14px 14px 4px");
        bubble.getStyle().set("border", "1px solid #cbd5e1");
        bubble.getStyle().set("box-shadow", "0 10px 24px rgba(15, 23, 42, 0.08)");

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
        messageText.getStyle().set("white-space", "normal");
        messageText.getStyle().set("margin-top", "4px");
        Span timestampText = createTimestampLabel(timestamp);
        timestampText.getStyle().set("margin-top", "4px");
        timestampText.getStyle().set("display", "block");

        header.add(name, modelLabel);
        bubble.add(header, messageText, timestampText);
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

    private String resolveChatApiErrorMessage(RestClientResponseException ex, String fallbackMessage) {
        try {
            Chat.ChatErrorResponse errorBody = ex.getResponseBodyAs(Chat.ChatErrorResponse.class);
            if (errorBody != null && errorBody.message() != null && !errorBody.message().isBlank()) {
                return errorBody.message();
            }
        } catch (Exception ignored) {
            // Fall through to fallback message if response body can't be parsed.
        }
        return fallbackMessage;
    }

    private void renderAssistantText(Div target, String text) {
        target.getElement().setProperty("innerHTML", toSafeHtml(text));
        target.getElement().executeJs("""
                const root = this;
                root.querySelectorAll('table').forEach((table) => {
                  table.style.width = '100%';
                  table.style.borderCollapse = 'collapse';
                  table.style.marginTop = '8px';
                  table.style.marginBottom = '6px';
                  table.style.fontSize = '0.9rem';
                });
                root.querySelectorAll('th, td').forEach((cell) => {
                  cell.style.border = '1px solid #cbd5e1';
                  cell.style.padding = '6px 8px';
                  cell.style.textAlign = 'left';
                  cell.style.verticalAlign = 'top';
                });
                root.querySelectorAll('th').forEach((th) => {
                  th.style.background = '#e2e8f0';
                  th.style.fontWeight = '600';
                });
                root.querySelectorAll('pre').forEach((pre) => {
                  pre.style.background = '#0f172a';
                  pre.style.color = '#e2e8f0';
                  pre.style.padding = '10px';
                  pre.style.borderRadius = '8px';
                  pre.style.overflowX = 'auto';
                });
                root.querySelectorAll('code').forEach((code) => {
                  if (code.parentElement && code.parentElement.tagName === 'PRE') {
                    return;
                  }
                  code.style.background = '#e2e8f0';
                  code.style.padding = '1px 4px';
                  code.style.borderRadius = '4px';
                });
                root.querySelectorAll('blockquote').forEach((quote) => {
                  quote.style.margin = '8px 0';
                  quote.style.padding = '4px 10px';
                  quote.style.borderLeft = '3px solid #94a3b8';
                  quote.style.color = '#334155';
                });
                root.querySelectorAll('ul, ol').forEach((list) => {
                  list.style.paddingLeft = '20px';
                  list.style.marginTop = '8px';
                  list.style.marginBottom = '6px';
                });
                root.querySelectorAll('p').forEach((p) => {
                  p.style.margin = '0 0 6px 0';
                });
                root.querySelectorAll('p:last-child, ul:last-child, ol:last-child, pre:last-child, table:last-child, blockquote:last-child').forEach((el) => {
                  el.style.marginBottom = '0';
                });
                """);
    }

    private String toSafeHtml(String text) {
        if (text == null) {
            return "";
        }
        return MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(text));
    }

    private String formatTimestamp(Instant instant) {
        Instant safeInstant = instant == null ? Instant.now() : instant;
        return CHAT_TIME_FORMATTER.format(safeInstant.atZone(ZoneId.systemDefault()));
    }

    private Span createTimestampLabel(String text) {
        Span timestamp = new Span(text);
        timestamp.getStyle().set("font-size", "0.72rem");
        timestamp.getStyle().set("color", "#94a3b8");
        return timestamp;
    }

    private Div createThinkingLoader() {
        Div loader = new Div();
        loader.addClassName("wizard-thinking-loader");
        loader.getElement().setProperty("title", "Thinking...");
        for (int i = 0; i < 3; i++) {
            Span dot = new Span();
            dot.addClassName("wizard-thinking-dot");
            loader.add(dot);
        }
        return loader;
    }

    private record PendingAssistantMessage(Span model, Div message, Span timestamp, Div loading) {
    }

    private static class UiChatException extends RuntimeException {
        private UiChatException(String message) {
            super(message);
        }
    }
}
