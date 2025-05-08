package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.response.ResponseBoolean;
import searchengine.dto.statistics.response.ResponseError;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.util.LemmaFinder;
import searchengine.util.PageCrawlerTask;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public void getLemmasAndIndex(Page page, SiteEntity siteEntity) throws IOException {
        LemmaFinder lemmaFinder = LemmaFinder.getInstance();
        Map<String, Integer> allLem = lemmaFinder.collectLemmas(page.getContent());
        for (Map.Entry<String, Integer> entry : allLem.entrySet()) {
            String lemmaText = entry.getKey();
            int countLemma = entry.getValue();
            Lemma lemma = lemmaRepository.findByLemmaAndSiteId(lemmaText, siteEntity);
            if (lemma == null) {
                lemma = new Lemma();
                lemma.setLemma(lemmaText);
                lemma.setSiteId(siteEntity);
                lemma.setFrequency(1);
            } else {
                lemma.setFrequency(lemma.getFrequency() + 1);
            }
            lemmaRepository.save(lemma);
            Index index = new Index();
            index.setPageId(page);
            index.setLemmaId(lemma);
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

}
