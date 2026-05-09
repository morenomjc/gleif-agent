package dev.morenomjc.gleifagent;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Route("")
@PageTitle("Chat")
public class ChatView extends VerticalLayout {

    private static final String WELCOME_SESSION_KEY = "chat.welcome.shown";
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
    private final VerticalLayout messagesLayout = new VerticalLayout();
    private final Scroller messagesScroller;
    private final TextArea messageInput = new TextArea("Message");
    private final Button sendButton = new Button(new Icon(VaadinIcon.PAPERPLANE));

    public ChatView(
            RestClient.Builder restClientBuilder,
            @Value("${server.port:8080}") int serverPort,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName
    ) {
        this.restClient = restClientBuilder.baseUrl("http://localhost:" + serverPort).build();

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

        messageInput.setWidthFull();
        messageInput.setMaxLength(4000);
        messageInput.setPlaceholder("Ask Gandalf anything...");
        messageInput.setMinHeight("80px");
        messageInput.setMaxHeight("180px");
        messageInput.setClearButtonVisible(true);
        messageInput.getStyle().set("background", "#ffffff");
        messageInput.getStyle().set("border-radius", "12px");

        sendButton.addClickListener(event -> sendMessage());
        sendButton.getStyle().set("background", "#0f172a");
        sendButton.getStyle().set("color", "#ffffff");
        sendButton.getStyle().set("border-radius", "10px");
        sendButton.getElement().setProperty("title", "Send");
        sendButton.getElement().setProperty("aria-label", "Send message");

        HorizontalLayout inputLayout = new HorizontalLayout(messageInput, sendButton);
        inputLayout.setWidthFull();
        inputLayout.setFlexGrow(1, messageInput);
        inputLayout.setAlignItems(Alignment.END);
        add(inputLayout);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        Boolean welcomeShown = (Boolean) VaadinSession.getCurrent().getAttribute(WELCOME_SESSION_KEY);
        if (Boolean.TRUE.equals(welcomeShown)) {
            return;
        }
        VaadinSession.getCurrent().setAttribute(WELCOME_SESSION_KEY, true);
        requestAssistantMessage(buildWelcomePrompt());
    }

    private void sendMessage() {
        String userMessage = messageInput.getValue() == null ? "" : messageInput.getValue().trim();
        if (userMessage.isEmpty()) {
            return;
        }

        addUserMessage(userMessage);
        messageInput.clear();
        requestAssistantMessage(userMessage);
    }

    private void requestAssistantMessage(String prompt) {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }

        PendingAssistantMessage pendingMessage = addPendingGandalfMessage();
        sendButton.setEnabled(false);
        messageInput.setEnabled(false);

        CompletableFuture.supplyAsync(() -> callChatEndpoint(prompt))
                .whenComplete((response, throwable) -> ui.access(() -> {
                    if (throwable != null) {
                        pendingMessage.model.setText("");
                        pendingMessage.message.setText("Unable to reach chat API: " + throwable.getMessage());
                    } else {
                        pendingMessage.model.setText(response.model());
                        pendingMessage.message.setText(response.reply());
                    }
                    sendButton.setEnabled(true);
                    messageInput.setEnabled(true);
                    scrollToBottom();
                }));
    }

    private Chat.ChatSuccessResponse callChatEndpoint(String prompt) {
        Chat.ChatRequest request = new Chat.ChatRequest(prompt);
        Chat.ChatSuccessResponse response = restClient.post()
                .uri("/chat")
                .body(request)
                .retrieve()
                .body(Chat.ChatSuccessResponse.class);
        if (response == null) {
            throw new IllegalStateException("Chat API returned an empty response.");
        }
        return response;
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

    private record PendingAssistantMessage(Span model, Div message) {
    }
}
