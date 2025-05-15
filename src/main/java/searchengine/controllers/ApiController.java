package searchengine.controllers;

import com.sun.xml.bind.v2.TODO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.response.ResponseBoolean;
import searchengine.services.IndexingSiteService;
import searchengine.services.StatisticsService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ApiController {

    private final StatisticsService statisticsService;
    private final IndexingSiteService indexingSiteService;


    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/startIndexing")
    public ResponseBoolean startIndexing() {
        return indexingSiteService.startIndexing();

    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/stopIndexing")
    public ResponseBoolean stopIndexing() {
        return indexingSiteService.stopIndexing();
    }

    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/indexPage")
    public ResponseBoolean indexPage(@RequestBody String urlPage) {
        return indexingSiteService.indexPage(urlPage);
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/search")
    public ResponseBoolean search(@RequestParam String query,
                                  @RequestParam String site,
                                  @RequestParam Integer offset,
                                  @RequestParam Integer limit) {
        return indexingSiteService.search(query, site, offset, limit);
    }

}
