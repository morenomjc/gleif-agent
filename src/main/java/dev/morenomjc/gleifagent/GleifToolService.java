package dev.morenomjc.gleifagent;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GleifToolService {

    private static final Pattern LEI_PATTERN = Pattern.compile("^[A-Z0-9]{20}$");
    private static final MediaType GLEIF_MEDIA_TYPE = MediaType.valueOf("application/vnd.api+json");
    private final RestClient gleifRestClient;

    public record LeiLookupResult(
            boolean found,
            String lei,
            String legalName,
            String registrationStatus,
            String entityStatus,
            String legalJurisdiction,
            String legalAddressCountry,
            String headquartersCountry,
            String initialRegistrationDate,
            String lastUpdateDate,
            String nextRenewalDate,
            String error
    ) {}

    @Tool(name = "get_lei_details", description = "Retrieve GLEIF LEI details by LEI number")
    public LeiLookupResult getLeiDetails(
            @ToolParam(description = "The LEI code, exactly 20 alphanumeric characters") String lei
    ) {
        String normalizedLei = normalizeLei(lei);
        if (!LEI_PATTERN.matcher(normalizedLei).matches()) {
            return error(normalizedLei, "Invalid LEI format. Expected 20 alphanumeric characters.");
        }

        try {
            JsonNode root = gleifRestClient.get()
                    .uri("/lei-records/{lei}", normalizedLei)
                    .accept(GLEIF_MEDIA_TYPE)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || root.path("data").isMissingNode() || root.path("data").isNull()) {
                return error(normalizedLei, "No LEI record found.");
            }

            return new LeiLookupResult(
                    true,
                    normalizedLei,
                    textAt(root, "/data/attributes/entity/legalName/name"),
                    textAt(root, "/data/attributes/registration/status"),
                    textAt(root, "/data/attributes/entity/status"),
                    textAt(root, "/data/attributes/entity/jurisdiction"),
                    textAt(root, "/data/attributes/entity/legalAddress/country"),
                    textAt(root, "/data/attributes/entity/headquartersAddress/country"),
                    textAt(root, "/data/attributes/registration/initialRegistrationDate"),
                    textAt(root, "/data/attributes/registration/lastUpdateDate"),
                    textAt(root, "/data/attributes/registration/nextRenewalDate"),
                    null
            );
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return error(normalizedLei, "LEI not found in GLEIF.");
            }
            log.warn("GLEIF API error for LEI {}: {} {}", normalizedLei, ex.getStatusCode(), ex.getMessage());
            return error(normalizedLei, "GLEIF API error: " + ex.getStatusCode().value());
        } catch (Exception ex) {
            log.error("Unexpected error while retrieving LEI {}", normalizedLei, ex);
            return error(normalizedLei, "Unexpected error while retrieving LEI details.");
        }
    }

    private String normalizeLei(String lei) {
        if (lei == null) {
            return "";
        }
        return lei.trim().toUpperCase(Locale.ROOT);
    }

    private LeiLookupResult error(String lei, String message) {
        return new LeiLookupResult(
                false,
                lei,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                message
        );
    }

    private String textAt(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }
}
