package dev.morenomjc.gleifagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ChatConfiguration {

    @Bean
    RestClient gleifRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${gleif.api.base-url:https://api.gleif.org/api/v1}") String gleifApiBaseUrl
    ) {
        return restClientBuilder.baseUrl(gleifApiBaseUrl).build();
    }

    @Bean
    ChatClient chatClient(
            ChatModel chatModel,
            GleifToolService gleifToolService
    ) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        You are an LEI lookup assistant.
                        For any user prompt that includes an LEI, you must call the get_lei_details tool.
                        Base your answer only on the tool result.
                        Keep responses concise and factual.
                        If the tool reports found=false, clearly state that the LEI was not found.
                        """)
                .defaultTools(gleifToolService)
                .build();
    }
}
