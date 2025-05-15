package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.response.ResponseBoolean;
import searchengine.dto.statistics.response.ResponseError;
import searchengine.dto.statistics.response.ResponseSearch;
import searchengine.dto.statistics.response.SearchResult;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.util.LemmaFinder;
import searchengine.util.PageCrawlerTask;

import java.awt.image.ImageObserver;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingSiteService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SitesList sitesList;
    private ForkJoinPool forkJoinPool;
    private final AtomicBoolean isIndexingRunning = new AtomicBoolean(false);


    public void deleteSiteData(String url) {
        SiteEntity site = siteRepository.findByUrl(url);
        if (site != null) {
            log.info("Удалить данные сайта " + url);
            siteRepository.delete(site);
        } else {
            log.warn("Сайт {} не найден, начинаем индексацию", url);
        }
    }


    public void deleteAllSiteDate() {
        List<Site> sites = sitesList.getSites();
        for (Site site : sites) {
            deleteSiteData(site.getUrl());
        }
    }


    public SiteEntity createEntryInTableSite(Site site) {
        SiteEntity siteEntity = new SiteEntity();
        siteEntity.setUrl(site.getUrl());
        siteEntity.setName(site.getName());
        siteEntity.setStatusTime(Instant.now());
        siteEntity.setStatus(StatusIndexingSite.INDEXING);
        siteEntity.setLastError("");
        return siteRepository.save(siteEntity);

    }


    public ResponseBoolean startIndexing() {
        if (isIndexingRunning.get()) {
            log.info("Индексация уже запущена");
            return new ResponseError(false, "Индексация уже запущена");
        }
        PageCrawlerTask.clearVisitedSite();
        log.info("Очискта списка посещенных сайтов");
        isIndexingRunning.set(true);
        log.info("status true");
        forkJoinPool = new ForkJoinPool();
        if (forkJoinPool.getActiveThreadCount() != 0) {
            isIndexingRunning.set(false);
        }
        deleteAllSiteDate();
        sitesList.getSites().forEach(site -> {
            SiteEntity siteEntity = createEntryInTableSite(site);
            PageCrawlerTask pageCrawlerTask = new PageCrawlerTask(site.getUrl(), site.getUrl(), pageRepository, siteRepository, lemmaRepository, indexRepository, sitesList, siteEntity);
            forkJoinPool.execute(pageCrawlerTask);
        });

        return new ResponseBoolean(true);
    }


    public ResponseBoolean stopIndexing() {
        if (!isIndexingRunning.get()) {
            log.info("Индексация не запущена");
            return new ResponseError(false, "Индексация не запущена");
        }
        log.info("Остновка индексации");
        isIndexingRunning.set(false);
        log.info("status false");
        forkJoinPool.shutdown();
        return new ResponseBoolean(true);
    }

    public ResponseBoolean indexPage(String urlPage) {
        Page page = new Page();
        String validUrl = URLDecoder.decode(urlPage.substring(urlPage.indexOf("h")), StandardCharsets.UTF_8);
        try {
            Connection.Response response = Jsoup.connect(validUrl).userAgent(sitesList.getUserAgent()).referrer(sitesList.getReferrer()).timeout(10_000).ignoreContentType(true).execute();
            String baseUrl = getBaseUrl(validUrl);
            SiteEntity siteEntity = new SiteEntity();
            boolean siteFound = false;
            for (Site site : sitesList.getSites()) {
                boolean correctSite = validUrl.startsWith(site.getUrl());
                if (correctSite) {
                    if (siteRepository.findByUrl(site.getUrl()) == null) {
                        log.info("Если сайта {} нет в базе, то он создается", baseUrl);
                        createEntryInTableSite(site);
                        log.info("Создание сайта {}", baseUrl);
                        siteEntity = siteRepository.findByUrl(site.getUrl());
                        siteFound = true;
                        break;
                    } else {
                        log.info("Сайт {} есть в списке конфигураций", baseUrl);
                        siteEntity = siteRepository.findByUrl(site.getUrl());
                        siteFound = true;
                        break;
                    }
                }
            }

            if (!siteFound) {
                log.info("Сайт {} не найден в списке конфигураций", baseUrl);
                return new ResponseError(false, "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
            }
            if (pageRepository.existsByPath(response.url().getPath())) {
                log.info("Эта страница уже есть в базе. Удаление старой страницы");
                pageRepository.deleteByPath((response.url().getPath()));
            }
            page.setContent(response.body().replace("\u0000", ""));
            page.setSite(siteEntity);
            page.setCode(response.statusCode());
            page.setPath(response.url().getPath());
            log.info("Сохранение страницы {}", page.getPath());
            pageRepository.save(page);
            log.info("Сохранение лемм и индексов");
            getLemmasAndIndex(page, siteEntity);
            siteEntity.setStatus(StatusIndexingSite.INDEXED);
            siteRepository.save(siteEntity);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ResponseBoolean(true);
    }

    public ResponseBoolean search(String query, String siteUrl, Integer offset, Integer limit) {
        if (query.isEmpty()) {
            return new ResponseError(false, "Задан пустой поисковый запрос");
        }

        SiteEntity siteEntity = siteRepository.findByUrl(siteUrl);
        if (siteEntity == null) {
            return new ResponseError(false, "Указанный сайт не найден");
        }
        Set<Lemma> filteredLemmas = getRelevantLemma(query, siteEntity);
        if (filteredLemmas.isEmpty()) {
            return new ResponseError(false, "Ничего не найдено");
        }

        List<Index> index = indexRepository.findByLemma(filteredLemmas.iterator().next());
        Set<Page> resultPages = index.stream()
                .map(Index::getPage)
                .collect(Collectors.toSet());
        int count = 0;
        for (Lemma lemma : filteredLemmas) {
            if (count >= 1) {
                index = indexRepository.findByLemma(lemma);
                Set<Page> currentPage = index.stream()
                        .map(Index::getPage)
                        .collect(Collectors.toSet());
                if (resultPages.isEmpty()) {
                    break;
                }
                resultPages.retainAll(currentPage);
            }
            count++;
        }

        Map<Page, Float> absoluteRelevancePage = calculateAbsoluteRelevance(resultPages, filteredLemmas);
        float maxAbsoluteRelevance = absoluteRelevancePage.values().stream()
                .max(Float::compare).orElse(1.0f);

        List<Page> sortedPages = resultPages.stream()
                .sorted((p1, p2) -> Float.compare(
                        absoluteRelevancePage.get(p2) / maxAbsoluteRelevance,
                        absoluteRelevancePage.get(p1) / maxAbsoluteRelevance
                ))
                .toList();

        List<SearchResult> searchResults = new ArrayList<>();
        for (Page page : sortedPages) {
            String siteName = siteEntity.getName();
            String uri = page.getPath();
            String title = getTitle(page);
            String snippet = getSnippet(page, filteredLemmas);
            float relevance = absoluteRelevancePage.get(page) / maxAbsoluteRelevance;
            searchResults.add(new SearchResult(siteUrl, siteName, uri, title, snippet, relevance));
        }
        return new ResponseSearch(true, searchResults.size(), searchResults);
    }

    public Set<Lemma> getRelevantLemma(String text, SiteEntity siteEntity) {
        double percentageOfOccurrence = 0.8;
        Set<Lemma> filteredLemmas = new TreeSet<>(Comparator.comparing(Lemma::getFrequency).thenComparing(Lemma::getLemma));
        try {
            LemmaFinder lemmaFinder = LemmaFinder.getInstance();
            Set<String> lemmas = lemmaFinder.getLemmaSet(text);
            Integer countPageBySite = pageRepository.countPageBySite(siteEntity);

            for (String l : lemmas) {
                Lemma lemma = lemmaRepository.findByLemmaAndSite(l, siteEntity);
                if (lemma == null) {
                    continue;
                }
                double count = countPageBySite * percentageOfOccurrence;
                if (lemma.getFrequency() < count) {
                    filteredLemmas.add(lemma);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return filteredLemmas;
    }

    public Map<Page, Float> calculateAbsoluteRelevance(Set<Page> resultPages, Set<Lemma> filteredLemma) {
        Map<Page, Float> absoluteRelevancePage = new HashMap<>();
        List<Index> indexList = indexRepository.findByPageInAndLemmaIn(resultPages, filteredLemma);
        Map<Page, List<Index>> pageToIndex = indexList.stream()
                .collect(Collectors.groupingBy(Index::getPage));
        for (Page page : resultPages) {
            List<Index> pageIndex = pageToIndex.getOrDefault(page, Collections.emptyList());
            float absoluteRelevance = (float) pageIndex.stream()
                    .mapToDouble(Index::getRank)
                    .sum();
            absoluteRelevancePage.put(page, absoluteRelevance);
        }

        return absoluteRelevancePage;
    }


    public void getLemmasAndIndex(Page page, SiteEntity siteEntity) throws IOException {
        LemmaFinder lemmaFinder = LemmaFinder.getInstance();
        Map<String, Integer> allLem = lemmaFinder.collectLemmas(page.getContent());
        for (Map.Entry<String, Integer> entry : allLem.entrySet()) {
            String lemmaText = entry.getKey();
            int countLemma = entry.getValue();
            Lemma lemma = lemmaRepository.findByLemmaAndSite(lemmaText, siteEntity);
            if (lemma == null) {
                lemma = new Lemma();
                lemma.setLemma(lemmaText);
                lemma.setSite(siteEntity);
                lemma.setFrequency(1);
            } else {
                lemma.setFrequency(lemma.getFrequency() + 1);
            }
            lemmaRepository.save(lemma);
            Index index = new Index();
            index.setPage(page);
            index.setLemma(lemma);
            index.setRank(countLemma);
            indexRepository.save(index);
        }
    }


    public String getBaseUrl(String url) {
        int protocolIndex = url.indexOf("://");
        int start = (protocolIndex != -1) ? protocolIndex + 3 : 0;
        int end = url.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int index = url.indexOf(c, start);
            if (index != -1 && index < end) {
                end = index;
            }
        }
        String baseUrl = url.substring(0, end);
        return baseUrl;
    }

    private String getTitle(Page page) {
        String title = "";
        try {
            String siteUrl = page.getSite().getUrl();
            String fullUrl = siteUrl + page.getPath();
            Document doc = Jsoup.connect(fullUrl).get();
            title = doc.title();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return title;
    }

    private String getSnippet(Page page, Set<Lemma> lemma) {
        String html = page.getContent();
        String text = Jsoup.parse(html).text();
        Set<String> words = lemma.stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());
        for (String word : words) {
            int pos = text.toLowerCase().indexOf(word.toLowerCase());
            if (pos >= 0) {
                int start = Math.max(0, pos - 50);
                int end = Math.min(text.length(), pos + word.length() + 50);
                return text.substring(start, end)
                        .replaceAll("(?i)(" + word + ")", "<b>$1</b>");
            }
        }
        return text.substring(0, Math.min(200, text.length())) + "...";
    }

}
