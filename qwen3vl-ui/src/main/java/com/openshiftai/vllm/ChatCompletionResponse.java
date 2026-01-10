package com.openshiftai.vllm;

import java.util.List;

public record ChatCompletionResponse(
        List<Choice> choices
) {
    public record Choice(Message message) {}
    public record Message(String role, String content) {}
}
