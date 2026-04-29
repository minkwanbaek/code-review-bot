package com.reviewbot.features.convention.controller;

import com.reviewbot.common.dto.ApiResponse;
import com.reviewbot.convention.Conventions;
import com.reviewbot.features.convention.dto.ConventionResponse;
import com.reviewbot.features.convention.dto.LearnConventionsRequest;
import com.reviewbot.features.convention.service.ConventionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

/**
 * Web and JSON endpoints for convention learning and editing.
 */
@Controller
public class ConventionController {

    private final ConventionService conventionService;

    /**
     * Creates a convention controller.
     *
     * @param conventionService service for convention persistence and learning
     */
    public ConventionController(ConventionService conventionService) {
        this.conventionService = conventionService;
    }

    /**
     * Renders the conventions management page.
     *
     * @param model Spring MVC model
     * @return template name
     * @throws IOException when conventions cannot be loaded
     */
    @GetMapping("/conventions")
    public String conventions(Model model) throws IOException {
        model.addAttribute("title", "Conventions");
        model.addAttribute("conventions", conventionService.loadConventions());
        return "conventions";
    }

    /**
     * Returns the persisted conventions as JSON.
     *
     * @return response containing conventions
     * @throws IOException when conventions cannot be loaded
     */
    @GetMapping("/api/conventions")
    @ResponseBody
    public ApiResponse<ConventionResponse> getConventions() throws IOException {
        return success(conventionService.loadConventions());
    }

    /**
     * Learns conventions from free-form text.
     *
     * @param request JSON body with text field
     * @return response containing updated conventions
     * @throws IOException when conventions cannot be loaded or saved
     */
    @PostMapping("/api/conventions/learn")
    @ResponseBody
    public ApiResponse<ConventionResponse> learnConventions(@RequestBody LearnConventionsRequest request) throws IOException {
        String text = request == null ? "" : request.getText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Convention text is required");
        }
        return success(conventionService.learnFromText(text, Boolean.TRUE.equals(request.getAiEnabled())));
    }

    /**
     * Saves the full convention document.
     *
     * @param conventions conventions to persist
     * @return response containing saved conventions
     * @throws IOException when conventions cannot be saved
     */
    @PostMapping("/api/conventions")
    @ResponseBody
    public ApiResponse<ConventionResponse> saveConventions(@RequestBody Conventions conventions) throws IOException {
        return success(conventionService.saveConventions(conventions));
    }

    /**
     * Adds a forbidden pattern rule.
     *
     * @param pattern pattern to add
     * @return response containing updated conventions
     * @throws IOException when conventions cannot be saved
     */
    @PostMapping("/api/conventions/forbidden-patterns")
    @ResponseBody
    public ApiResponse<ConventionResponse> addForbiddenPattern(@RequestBody Conventions.ForbiddenPattern pattern) throws IOException {
        return success(conventionService.addForbiddenPattern(pattern));
    }

    /**
     * Updates a forbidden pattern rule.
     *
     * @param index pattern index
     * @param pattern replacement pattern
     * @return response containing updated conventions
     * @throws IOException when conventions cannot be saved
     */
    @PutMapping("/api/conventions/forbidden-patterns/{index}")
    @ResponseBody
    public ApiResponse<ConventionResponse> updateForbiddenPattern(
            @PathVariable int index,
            @RequestBody Conventions.ForbiddenPattern pattern) throws IOException {
        return success(conventionService.updateForbiddenPattern(index, pattern));
    }

    /**
     * Deletes a forbidden pattern rule.
     *
     * @param index pattern index
     * @return response containing updated conventions
     * @throws IOException when conventions cannot be saved
     */
    @DeleteMapping("/api/conventions/forbidden-patterns/{index}")
    @ResponseBody
    public ApiResponse<ConventionResponse> deleteForbiddenPattern(@PathVariable int index) throws IOException {
        return success(conventionService.deleteForbiddenPattern(index));
    }

    private ApiResponse<ConventionResponse> success(Conventions conventions) {
        return ApiResponse.success(new ConventionResponse(conventions));
    }
}
