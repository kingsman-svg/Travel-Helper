package cn.travelbao.trip.controller;

import cn.travelbao.chat.dto.ConversationView;
import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TripController {
    private final TripService trips;
    private final MarkdownExporter exporter;
    public TripController(TripService trips, MarkdownExporter exporter) { this.trips = trips; this.exporter = exporter; }
    public record Generate(@Min(0) int expectedVersion, @NotNull @Valid TravelRequest request) {}
    public record Adjust(@Min(0) int expectedVersion, @NotBlank @Size(max=4000) String message) {}
    public record Edit(@Min(0) int expectedVersion, @NotNull @Valid Itinerary itinerary) {}
    public record Save(@Min(0) int expectedVersion) {}

    @PostMapping("/conversations") ConversationView create() { return trips.create(); }
    @GetMapping("/conversations/{id}") ConversationView conversation(@PathVariable String id) { return trips.conversation(id); }
    @PostMapping("/conversations/{id}/plan") ConversationView generate(@PathVariable String id, @Valid @RequestBody Generate body) {
        return trips.generate(id, body.expectedVersion(), body.request());
    }
    @PostMapping("/conversations/{id}/adjust") ConversationView adjust(@PathVariable String id, @Valid @RequestBody Adjust body) {
        return trips.adjust(id, body.expectedVersion(), body.message());
    }
    @PutMapping("/conversations/{id}/draft") ConversationView edit(@PathVariable String id, @Valid @RequestBody Edit body) {
        return trips.edit(id, body.expectedVersion(), body.itinerary());
    }
    @PostMapping("/conversations/{id}/save") TripService.SavedTripView save(@PathVariable String id, @Valid @RequestBody Save body) {
        return trips.save(id, body.expectedVersion());
    }
    @GetMapping("/trips") List<TripService.SavedTripView> list() { return trips.list(); }
    @GetMapping("/trips/{id}") TripService.SavedTripView get(@PathVariable String id) { return trips.get(id); }
    @GetMapping("/trips/{id}/export") ResponseEntity<String> export(@PathVariable String id) {
        var trip = trips.get(id);
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=travelbao-" + trip.id() + ".md")
                .body(exporter.export(trip));
    }
}
