package com.example.vllm;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
public class AuthFilter implements ClientRequestFilter {

    //@ConfigProperty(name = "vllm.api-key", defaultValue = "")
    //String apiKey;

    @Override
    public void filter(ClientRequestContext requestContext) {
        //if (apiKey != null && !apiKey.isBlank()) {
        //    requestContext.getHeaders().putSingle("Authorization", "Bearer " + apiKey.trim());
        //}
    }
}
