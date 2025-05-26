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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        SiteEntity siteEntity = new SiteEntity();
        List<SearchResult> searchResults = new ArrayList<>();

        if (siteUrl == null || siteUrl.isEmpty()) {
            log.info("Начинается поиск по всем страницам");
            for (Site site : sitesList.getSites()) {
                siteEntity = siteRepository.findByUrl(site.getUrl());
                Set<Lemma> filteredLemmas = getRelevantLemma(query, siteEntity);
                if (siteEntity != null) {
                    singleSiteSearch(filteredLemmas, searchResults, query, siteEntity);
                }
            }
        } else {
            log.info("Начинается поиск по {}", siteUrl);
            siteEntity = siteRepository.findByUrl(siteUrl);
            if (siteEntity == null) {
                return new ResponseError(false, "Указанный сайт не найден");
            }
            Set<Lemma> filteredLemmas = getRelevantLemma(query, siteEntity);
            if (filteredLemmas.isEmpty()) {
                return new ResponseError(false, "Ничего не найдено");
            }
            singleSiteSearch(filteredLemmas, searchResults, query, siteEntity);
        }

        int currentOffset = offset == null ? 0 : offset;
        int currentLimit = limit == null ? 20 : limit;

        List<SearchResult> finalSearchResult = searchResults.stream()
                .skip(currentOffset)
                .limit(currentLimit)
                .toList();
        if (finalSearchResult.isEmpty()) {
            return new ResponseError(false, "Ничего не найдено");
        }
        return new ResponseSearch(true, searchResults.size(), finalSearchResult);
    }

    private void singleSiteSearch(Set<Lemma> filteredLemmas, List<SearchResult> searchResults, String query, SiteEntity siteEntity) {
        if (filteredLemmas == null || filteredLemmas.isEmpty()) {
            return;
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

        for (Page page : sortedPages) {
            String siteName = siteEntity.getName();
            String uri = page.getPath();
            String title = getTitle(page);
            String snippet = getSnippet(page, query);
            float relevance = absoluteRelevancePage.get(page) / maxAbsoluteRelevance;
            searchResults.add(new SearchResult(siteEntity.getUrl(), siteName, uri, title, snippet, relevance));
        }
    }


    public Set<Lemma> getRelevantLemma(String text, SiteEntity siteEntity) {
        double percentageOfOccurrence = 1.0;
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
                if (lemma.getFrequency() <= count) {
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

    private String getSnippet(Page page, String query) {
        String html = page.getContent();
        String text = Jsoup.parse(html).text();
        String[] words = query.strip().split("\\s+");
        Pattern pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher matcher = pattern.matcher(text);
        int flag = 0;
        int length = text.length();
        String result = "";
        if (matcher.find()) {
            result = findMatchCondition(matcher, length, text, result);
        } else {
            flag = -1;
        }

        if (flag == -1) {
            for (String s : words) {
                pattern = Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                matcher = pattern.matcher(text);
                if (matcher.find()) {
                    result = findMatchCondition(matcher, length, text, result);
                    break;
                }
            }
            int a = 1;
            String newWords = "";
            if (result.isEmpty()) {
                for (String s : words) {

                    for (int i = 0; i <= s.length(); i++) {
                        if (a <= 3) {
                            newWords = s.substring(0, s.length() - a);
                            pattern = Pattern.compile(newWords, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                            matcher = pattern.matcher(text);
                            if (matcher.find()) {
                                result = findMatchCondition(matcher, length, text, result);
                                break;
                            }
                            a++;
                        } else {
                            break;
                        }
                    }
                    result = boldFont(newWords, pattern, matcher, result);
                }
                return result;
            }
        }

        for (String s : words) {
            result = boldFont(s, pattern, matcher, result);
        }
        return result;
    }

    private String boldFont(String str, Pattern pattern, Matcher matcher, String result) {
        String escapedWord = Pattern.quote(str);
        pattern = Pattern.compile(escapedWord, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        matcher = pattern.matcher(result);
        result = matcher.replaceAll("<b>$0</b>");
        return result;
    }

    private String findMatchCondition(Matcher matcher, int length, String text, String result) {
        int startIndex = matcher.start();
        int lastIndex = matcher.end();
        int difference = length - lastIndex;
        if ((lastIndex + 300) < length) {
            int endStr = lastIndex + 300;
            String str = text.substring(startIndex, endStr);
            int firstSpace = str.lastIndexOf(" ", endStr);
            result = str.substring(0, firstSpace) + " ...";
            return result;
        } else {
            result = text.substring(startIndex, lastIndex + difference);
            return result;
        }
    }
}