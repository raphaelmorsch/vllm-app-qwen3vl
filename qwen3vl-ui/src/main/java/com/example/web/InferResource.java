package com.example.web;

import com.openshiftai.vllm.ChatCompletionRequest;
import com.openshiftai.vllm.ChatCompletionResponse;
import com.openshiftai.vllm.VllmClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.PartType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Path("/")
@ApplicationScoped
public class InferResource {

    @Inject
    @Location("index.html")
    Template index;

    @Inject
    @RestClient
    VllmClient vllm;

    @Inject
    MeterRegistry registry;

    // Somente para o futuro (quando você habilitar auth)
    @ConfigProperty(name = "vllm.api-key", defaultValue = "")
    String apiKey;

    // Apenas para mensagens amigáveis no UI (o RestClient usa quarkus.rest-client.vllm.url)
    @ConfigProperty(name = "vllm.base-url", defaultValue = "")
    String baseUrl;

    @ConfigProperty(name = "vllm.model", defaultValue = "qwen3-vl-4b-instruct")
    String modelId;

    @ConfigProperty(name = "vllm.default-prompt", defaultValue = "Descreva a imagem em português.")
    String defaultPrompt;

    @ConfigProperty(name = "vllm.max-tokens", defaultValue = "512")
    int maxTokens;

    @ConfigProperty(name = "vllm.temperature", defaultValue = "0.2")
    double temperature;

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String bestMessage(Throwable t) {
        if (t == null) return "(erro desconhecido)";
        if (t.getMessage() != null && !t.getMessage().isBlank()) return t.getMessage();
        Throwable c = t.getCause();
        if (c != null && c != t) return bestMessage(c);
        return t.getClass().getName();
    }

    private String render(String prompt, String answer, String error) {
        return index
                .data("prompt", prompt == null ? "" : prompt)
                .data("answer", answer == null ? "" : answer)
                .data("error", error == null ? "" : error)
                .render();
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String home() {
        return render(defaultPrompt, "", "");
    }

    public static class InferForm {
        @FormParam("prompt")
        public String prompt;

        @FormParam("image")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public byte[] image;

        @FormParam("image")
        @PartType(MediaType.TEXT_PLAIN)
        public String imageFileName;
    }

    @POST
    @Path("/infer")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public String infer(@BeanParam InferForm form) {
        Timer timer = registry.timer("qwen3vl_infer_seconds");

        String prompt = (form == null || form.prompt == null || form.prompt.isBlank())
                ? defaultPrompt
                : form.prompt.trim();

        if (form == null || form.image == null || form.image.length == 0) {
            registry.counter("qwen3vl_infer_total", "status", "bad_request").increment();
            return render(prompt, "", "Imagem vazia.");
        }

        // Só para UX: se você estiver com placeholder/local ainda
        if (baseUrl != null) {
            String bu = baseUrl.trim();
            if (bu.isEmpty() || bu.contains("localhost")) {
                registry.counter("qwen3vl_infer_total", "status", "not_configured").increment();
                return render(prompt, "", "Backend vLLM ainda não configurado (VLLM_BASE_URL).");
            }
        }

        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(form.image);

        ChatCompletionRequest req = new ChatCompletionRequest(
                modelId,
                List.of(new ChatCompletionRequest.Message(
                        "user",
                        List.of(
                                ChatCompletionRequest.ContentPart.text(prompt),
                                ChatCompletionRequest.ContentPart.image(dataUrl)
                        )
                )),
                temperature,
                maxTokens
        );

        long start = System.nanoTime();
        try {
            ChatCompletionResponse resp = vllm.chatCompletions(req);

            timer.record(Duration.ofNanos(System.nanoTime() - start));

            String answer = "(sem resposta)";
            if (resp != null && resp.choices() != null && !resp.choices().isEmpty()
                    && resp.choices().get(0) != null
                    && resp.choices().get(0).message() != null
                    && resp.choices().get(0).message().content() != null) {
                answer = resp.choices().get(0).message().content();
            }

            registry.counter("qwen3vl_infer_total", "status", "success").increment();
            return render(prompt, answer, "");

        } catch (Exception e) {
            timer.record(Duration.ofNanos(System.nanoTime() - start));
            registry.counter("qwen3vl_infer_total", "status", "error").increment();

            String shortMsg = bestMessage(e);
            String details = shortMsg + "\n\n" + stackTrace(e);

            return render(prompt, "", details);
        }
    }
}
