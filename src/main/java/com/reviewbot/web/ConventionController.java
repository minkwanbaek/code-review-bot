package com.reviewbot.web;

import com.reviewbot.convention.ConventionService;
import com.reviewbot.convention.Conventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web and JSON endpoints for convention learning and editing.
 */
@Controller
public class ConventionController {

    private static final Logger log = LoggerFactory.getLogger(ConventionController.class);

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
     */
    @GetMapping("/api/conventions")
    @ResponseBody
    public Map<String, Object> getConventions() {
        try {
            return success(conventionService.loadConventions());
        } catch (IOException e) {
            return failure("Failed to load conventions: " + e.getMessage());
        }
    }

    /**
     * Learns conventions from free-form text.
     *
     * @param request JSON body with a text field
     * @return response containing updated conventions
     */
    @PostMapping("/api/conventions/learn")
    @ResponseBody
    public Map<String, Object> learnConventions(@RequestBody Map<String, String> request) {
        String text = request.getOrDefault("text", "");
        if (text.isBlank()) {
            return failure("Convention text is required");
        }
        try {
            return success(conventionService.learnFromText(text));
        } catch (Exception e) {
            log.error("Failed to learn conventions", e);
            return failure("Failed to learn conventions: " + e.getMessage());
        }
    }

    /**
     * Saves the full convention document.
     *
     * @param conventions conventions to persist
     * @return response containing saved conventions
     */
    @PostMapping("/api/conventions")
    @ResponseBody
    public Map<String, Object> saveConventions(@RequestBody Conventions conventions) {
        try {
            return success(conventionService.saveConventions(conventions));
        } catch (IOException e) {
            return failure("Failed to save conventions: " + e.getMessage());
        }
    }

    /**
     * Adds a forbidden pattern rule.
     *
     * @param pattern pattern to add
     * @return response containing updated conventions
     */
    @PostMapping("/api/conventions/forbidden-patterns")
    @ResponseBody
    public Map<String, Object> addForbiddenPattern(@RequestBody Conventions.ForbiddenPattern pattern) {
        try {
            return success(conventionService.addForbiddenPattern(pattern));
        } catch (IOException e) {
            return failure("Failed to add pattern: " + e.getMessage());
        }
    }

    /**
     * Updates a forbidden pattern rule.
     *
     * @param index pattern index
     * @param pattern replacement pattern
     * @return response containing updated conventions
     */
    @PutMapping("/api/conventions/forbidden-patterns/{index}")
    @ResponseBody
    public Map<String, Object> updateForbiddenPattern(
            @PathVariable int index,
            @RequestBody Conventions.ForbiddenPattern pattern) {
        try {
            return success(conventionService.updateForbiddenPattern(index, pattern));
        } catch (Exception e) {
            return failure("Failed to update pattern: " + e.getMessage());
        }
    }

    /**
     * Deletes a forbidden pattern rule.
     *
     * @param index pattern index
     * @return response containing updated conventions
     */
    @DeleteMapping("/api/conventions/forbidden-patterns/{index}")
    @ResponseBody
    public Map<String, Object> deleteForbiddenPattern(@PathVariable int index) {
        try {
            return success(conventionService.deleteForbiddenPattern(index));
        } catch (Exception e) {
            return failure("Failed to delete pattern: " + e.getMessage());
        }
    }

    private Map<String, Object> success(Conventions conventions) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("conventions", conventions);
        return response;
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
