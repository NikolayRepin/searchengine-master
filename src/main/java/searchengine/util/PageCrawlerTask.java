package searchengine.util;

import liquibase.pro.packaged.P;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SitesList;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Pattern;

@Slf4j

public class PageCrawlerTask extends RecursiveAction {

    private String url;
    private final String defaultUrl;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final SitesList sitesList;
    private final SiteEntity siteEntity;
    private static Set<String> visitedSite = ConcurrentHashMap.newKeySet();
    private static final Pattern FILE_PATTERN = Pattern
            .compile(".*\\.(jpg|jpeg|png|gif|bmp|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|tar|gz|7z|mp3|wav|mp4|mkv|avi|mov|sql|webp|svg)$", Pattern.CASE_INSENSITIVE);


    public PageCrawlerTask(String url, String defaultUrl, PageRepository pageRepository, SiteRepository siteRepository, LemmaRepository lemmaRepository, IndexRepository indexRepository, SitesList sitesList, SiteEntity siteEntity) {
        this.url = url;
        this.defaultUrl = defaultUrl;
        this.pageRepository = pageRepository;
        this.siteRepository = siteRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.sitesList = sitesList;
        this.siteEntity = siteEntity;

    }

    @Override
    protected void compute() {


        if (visitedSite.contains(url)) {
            return;
        }
        if (Thread.currentThread().isInterrupted() || getPool().isShutdown()) {
            Thread.currentThread().interrupt();
            return;
        }
        visitedSite.add(url);

        Page page = new Page();
        page.setPath(url.substring(siteEntity.getUrl().length()));
        try {
            Connection.Response response = connection();

            updateStatusTime();
            page.setSite(siteEntity);
            page.setCode(response.statusCode());
            page.setContent(response.body().replace("\u0000", ""));
            log.info("Сохранение страницы {}", url);
            pageRepository.save(page);

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


            Document doc = response.parse();
            Elements elements = doc.select("a");
            List<PageCrawlerTask> tasks = new ArrayList<>();
            for (Element element : elements) {
                if (Thread.currentThread().isInterrupted() || getPool().isShutdown()) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String href = element.attr("abs:href").trim();
                if (isValid(href)) {
                    PageCrawlerTask pageCrawlerTask = new PageCrawlerTask(href, defaultUrl, pageRepository, siteRepository, lemmaRepository, indexRepository, sitesList, siteEntity);
                    pageCrawlerTask.fork();
                    tasks.add(pageCrawlerTask);
                }
            }
            for (PageCrawlerTask task : tasks) {
                if (Thread.currentThread().isInterrupted() || getPool().isShutdown()) {
                    Thread.currentThread().interrupt();
                    indexingStoppedByUser();
                    return;
                }
                task.join();
            }
            if (url.equals(defaultUrl)) {
                successfulIndexing();
            }


        } catch (HttpStatusException e) {
            e.printStackTrace();
            int statusCode = e.getStatusCode();
            if (statusCode == 404) {
                error404(page);
            }
            if (statusCode == 500) {
                boolean isRootUrl = url.equals(siteEntity.getUrl()) || url.equals(siteEntity.getUrl() + "/");
                if (isRootUrl) {
                    error500();
                } else {
                    error500ForPage(page);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Ошибка ввода-вывода {}", url, e.getMessage());
        }

    }

    private boolean isValid(String url) {
        return url.startsWith(defaultUrl) && !url.contains("#") && !visitedSite.contains(url) && !FILE_PATTERN.matcher(url).matches();
    }

    private void updateStatusTime() {
        siteEntity.setStatusTime(Instant.now());
        siteRepository.save(siteEntity);
    }

    public Connection.Response connection() throws IOException {
        Connection.Response response = Jsoup.connect(url).userAgent(sitesList.getUserAgent()).referrer(sitesList.getReferrer()).timeout(10_000).ignoreContentType(true).execute();
        return response;
    }

    public void error500() {
        siteEntity.setLastError("Ошибка 500");
        siteEntity.setStatus(StatusIndexingSite.FAILED);
        log.error("Ошибка 500 {}, Статус: {}", url, siteEntity.getStatus());
        updateStatusTime();
    }

    public void error500ForPage(Page page) {
        log.error("Ошибка 500 {}", url);
        page.setSite(siteEntity);
        page.setPath(url.substring(siteEntity.getUrl().length()));
        page.setCode(500);
        page.setContent("");
        pageRepository.save(page);
    }


    public void error404(Page page) {
        log.error("Ошибка 404 {}", url);
        page.setSite(siteEntity);
        page.setPath(url.substring(siteEntity.getUrl().length()));
        page.setCode(404);
        page.setContent("");
        pageRepository.save(page);
    }

    public void successfulIndexing() {
        siteEntity.setStatus(StatusIndexingSite.INDEXED);
        siteEntity.setLastError("");
        siteEntity.setStatusTime(Instant.now());
        log.info("Сайт {} успешно проиндексирован", defaultUrl);
        siteRepository.save(siteEntity);
    }

    public void indexingStoppedByUser() {
        synchronized (siteEntity) {
            if (siteEntity.getStatus() != StatusIndexingSite.FAILED) {
                siteEntity.setStatus(StatusIndexingSite.FAILED);
                siteEntity.setLastError("Индексация остановлена пользователем");
                siteEntity.setStatusTime(Instant.now());
                siteRepository.save(siteEntity);
                log.info("Индексация остановлена пользователем");
            }
        }
    }

    public static void clearVisitedSite() {
        visitedSite.clear();
    }


}