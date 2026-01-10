package com.openshiftai.vllm;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Integer max_tokens
) {
    public record Message(String role, List<ContentPart> content) {}

    public record ContentPart(
            String type,
            String text,
            ImageUrl image_url
    ) {
        public static ContentPart text(String t) {
            return new ContentPart("text", t, null);
        }

        public static ContentPart image(String dataUrl) {
            return new ContentPart("image_url", null, new ImageUrl(dataUrl));
        }
    }

    public record ImageUrl(String url) {}
}
