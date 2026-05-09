# GLEIF Agent

Spring Boot + Vaadin chat app powered by Spring AI.

## What It Does

- Provides a chat UI for LEI lookup workflows.
- Calls a Spring AI tool (`get_lei_details`) backed by the GLEIF API.
- Shows model metadata and timestamps in the chat.
- Displays welcome message at session start.
- Renders assistant output as Markdown (including tables).
- Handles unsupported/tool-call-like model outputs with safe fallback messaging.

## Tech Stack

- Java 21
- Spring Boot 3.5
- Vaadin 24
- Spring AI 1.1

## Prerequisites

- Java 21+
- Network access for model provider + GLEIF API

## Configuration

Set environment variable:

- `OPENAI_API_KEY` (used with OpenRouter base URL in this project)

Main config file:

- `src/main/resources/application.properties`

Default key settings:

- `spring.ai.openai.base-url=https://openrouter.ai/api`
- `spring.ai.openai.chat.options.model=openrouter/free`
- `gleif.api.base-url=https://api.gleif.org/api/v1`

## Run Locally

```bash
./mvnw spring-boot:run
```

App URL:

- `http://localhost:8080`

## Build

```bash
./mvnw -DskipTests compile
```

## Chat API

### Endpoint

- `POST /chat`

### Request

```json
{
  "message": "Check LEI 529900L444MQCT0G8234"
}
```

### Success Response

```json
{
  "reply": "...",
  "model": "...",
  "responseId": "...",
  "timestamp": "..."
}
```

### Error Response

```json
{
  "error": "...",
  "message": "...",
  "timestamp": "..."
}
```

## Notes

- Current guardrails support LEI lookup requests with one LEI per user query.
- If a routed model emits raw tool-call text instead of executing tools, the app returns a clear unsupported-by-model message.
