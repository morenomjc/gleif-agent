package dev.morenomjc.gleifagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class UnsupportedPromptReasonService {

    private static final String FALLBACK_REASON =
            "Unsupported request. This chat currently supports LEI detail lookup for one LEI code per request.";

    private final List<String> reasons;

    public UnsupportedPromptReasonService(ObjectMapper objectMapper) {
        this.reasons = loadReasons(objectMapper);
    }

    public String randomReason() {
        if (reasons.isEmpty()) {
            return FALLBACK_REASON;
        }
        int index = ThreadLocalRandom.current().nextInt(reasons.size());
        return reasons.get(index);
    }

    private List<String> loadReasons(ObjectMapper objectMapper) {
        try (InputStream inputStream = new ClassPathResource("unsupported-reasons.json").getInputStream()) {
            List<String> loadedReasons = objectMapper.readValue(inputStream, new TypeReference<>() {});
            return loadedReasons.stream()
                    .filter(reason -> reason != null && !reason.isBlank())
                    .toList();
        } catch (Exception ex) {
            log.warn("Failed to load unsupported prompt reasons. Using fallback message.", ex);
            return List.of(FALLBACK_REASON);
        }
    }
}
