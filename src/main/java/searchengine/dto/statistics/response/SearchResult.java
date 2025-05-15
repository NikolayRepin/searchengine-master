package searchengine.dto.statistics.response;

import searchengine.model.SiteEntity;

public record SearchResult(
        String site,
        String siteName,
        String uri,
        String title,
        String snippet,
        float relevance
) {}