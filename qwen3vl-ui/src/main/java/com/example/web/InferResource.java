package com.example.web;

import com.openshiftai.vllm.ChatCompletionRequest;
import com.openshiftai.vllm.ChatCompletionResponse;
import com.openshiftai.vllm.VllmClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.PartType;

import java.util.Base64;
import java.util.List;

@Path("/")
@ApplicationScoped
public class InferResource {

    @Inject Template index;

    @Inject @RestClient VllmClient vllm;

    @Inject MeterRegistry registry;

    @ConfigProperty(name = "vllm.api-key", defaultValue = "")
    String apiKey;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance home() {
        return index.data("prompt", "Descreva a imagem em português.");
    }

    public static class InferForm {
        @FormParam("prompt")
        public String prompt;

        @FormParam("image")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public byte[] image;

        @FormParam("image")
        @PartType(MediaType.TEXT_PLAIN)
        public String imageFileName; // nem sempre vem preenchido; ok
    }

    @POST
    @Path("/infer")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance infer(@BeanParam InferForm form) {
        var timer = registry.timer("qwen3vl_infer_seconds");

        try {
            if (form.image == null || form.image.length == 0) {
                return index.data("prompt", form.prompt).data("error", "Imagem vazia.");
            }

            String prompt = (form.prompt == null || form.prompt.isBlank())
                    ? "Descreva a imagem em português."
                    : form.prompt.trim();

            // Heurística simples: se não souber o mime, use jpeg.
            String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(form.image);

            ChatCompletionRequest req = new ChatCompletionRequest(
                    "Qwen3-VL",
                    List.of(new ChatCompletionRequest.Message("user",
                            List.of(
                                    ChatCompletionRequest.ContentPart.text(prompt),
                                    ChatCompletionRequest.ContentPart.image(dataUrl)
                            ))),
                    0.2,
                    512
            );

            ChatCompletionResponse resp = timer.record(() -> vllm.chatCompletions(req));

            String answer = (resp.choices() != null && !resp.choices().isEmpty()
                    && resp.choices().get(0).message() != null)
                    ? resp.choices().get(0).message().content()
                    : "(sem resposta)";

            registry.counter("qwen3vl_infer_total", "status", "success").increment();
            return index.data("prompt", prompt).data("answer", answer);

        } catch (Exception e) {
            registry.counter("qwen3vl_infer_total", "status", "error").increment();
            return index.data("prompt", form.prompt).data("error", e.getMessage());
        }
    }
}
