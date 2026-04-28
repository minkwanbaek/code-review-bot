package com.reviewbot.convention;

import com.reviewbot.ai.OllamaClient;

/**
 * Backward-compatible facade for the feature-based convention service.
 */
@Deprecated(forRemoval = false)
public class ConventionService extends com.reviewbot.features.convention.service.ConventionService {

    /**
     * Creates a compatibility convention service.
     *
     * @param conventionsFile path to conventions.json
     * @param ollamaClient Ollama client used when AI learning is enabled
     * @param aiEnabled whether AI convention learning should be attempted first
     */
    public ConventionService(String conventionsFile, OllamaClient ollamaClient, boolean aiEnabled) {
        super(conventionsFile, ollamaClient, aiEnabled);
    }
}
